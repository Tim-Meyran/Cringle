// SPDX-License-Identifier: Apache-2.0

package cringle.engine.fabric

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.BlockId
import cringle.contract.BlockPorts
import cringle.contract.DriverSet
import cringle.contract.IsolationLevel
import cringle.contract.Tether
import cringle.contract.TetherEvent
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.schema.SchemaRegistry
import cringle.schema.SchemaValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.util.concurrent.Executors
import cringle.engine.tether.TetherConfig
import cringle.engine.tether.TetherNetwork
import cringle.engine.tether.TetherWiringException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.nanoseconds

/** Everything needed to create a [FabricRuntime]. */
public class FabricSpec(
    /** Id of the fabric instance. */
    public val id: String,
    /** The blueprint to run. */
    public val blueprint: Blueprint,
    /** Finds the providers of the blueprint's blocks. */
    public val resolver: BlockResolver,
    /** Supplies the drivers of the blocks. */
    public val drivers: DriverFactory,
    /** Directories of this fabric. */
    public val paths: FabricPaths,
    /** Creates the tethers behind the ports. */
    public val wiring: PortWiring = UnconnectedPorts,
    /**
     * Settings for the in-process tethers of the blueprint. When set, the runtime wires the blueprint's tethers itself
     * and [wiring] is ignored; when `null`, [wiring] is used.
     */
    public val tethers: TetherConfig? = null,
    /** Restart policy per block id; blocks not listed use [defaultRestart]. */
    public val restart: Map<String, RestartPolicy> = emptyMap(),
    /** Restart policy for blocks without an entry in [restart]. */
    public val defaultRestart: RestartPolicy = RestartPolicy(),
    /** Registry to validate block configs against their config schema; `null` skips the check. */
    public val schemas: SchemaRegistry? = null,
    /** Where the fabric logs to. */
    public val logger: FabricLogger = FabricLogger { _, _ -> },
    /** Settings of the blocked-thread watchdog. */
    public val watchdog: WatchdogConfig = WatchdogConfig(),
    /** Closed when the runtime is closed (for example the fabric's class loaders). */
    public val onClose: AutoCloseable? = null,
)

/**
 * One fabric instance: a blueprint running on exactly one engine, in its own thread with a coroutine scope
 * (Architecture chapter 9 and 19). All calls into blocks run on that thread one after another, so a block that blocks
 * it delays the others; the watchdog reports this.
 *
 * States are explicit ([FabricState]) and observable through [status]. A fabric can be started, stopped and started
 * again; every start creates fresh block instances through their providers.
 */
public class FabricRuntime(private val spec: FabricSpec) : AutoCloseable {
    private class Entry(val instance: BlueprintBlock, val resolved: ResolvedBlock, val config: Map<String, Any?>)

    private companion object {
        /** How long stopping waits for the TCP listeners of the tethers, for all of them together. */
        private val TCP_CLOSE_TIMEOUT: Duration = Duration.ofSeconds(2)
    }

    private val entries: List<Entry>
    private val network: TetherNetwork?
    private val wiring: PortWiring
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "fabric-${spec.id}").also { it.isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val lifecycle = Mutex()
    private val hosts: List<BlockHost>
    private val watchdog = spec.watchdog.takeIf { it.enabled }?.let { Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fabric-${spec.id}-watchdog").also { t -> t.isDaemon = true } } }

    @Volatile private var phase = FabricState.CREATED

    @Volatile private var startFailure: String? = null

    @Volatile private var currentExecution: String? = null

    private val mutableStatus: MutableStateFlow<FabricStatus>

    /** The current status; a new value is published on every change of the fabric or of one of its blocks. */
    public val status: StateFlow<FabricStatus> get() = mutableStatus

    init {
        entries = resolveEntries()
        network = spec.tethers?.let { config ->
            try {
                TetherNetwork.create(spec.blueprint, entries.associate { it.instance.id to it.resolved.definition }, config) { info, e ->
                    log(FabricLogger.Level.WARN, "tether ${info.id}: ${e.message}")
                }
            } catch (e: TetherWiringException) {
                throw FabricException("fabric '${spec.id}' cannot be created:\n${e.message}")
            }
        }
        wiring = network ?: spec.wiring
        hosts = entries.map { BlockHost(it) }
        mutableStatus = MutableStateFlow(computeStatus())
        startWatchdog()
    }

    private fun resolveEntries(): List<Entry> {
        val problems = ArrayList<String>()
        val ids = HashSet<String>()
        val result = ArrayList<Entry>()
        for (b in spec.blueprint.blocks) {
            if (!ids.add(b.id)) problems += "duplicate block id '${b.id}'"
            val resolved = spec.resolver.resolve(b.block)
            if (resolved == null) {
                problems += "block '${b.id}': unknown block '${b.block}'"
                continue
            }
            val schema = resolved.definition.configSchema
            val registry = spec.schemas
            if (schema != null && registry != null) {
                for (e in SchemaValidator(registry).validate(b.config, schema)) problems += "block '${b.id}': config${e.path.removePrefix("$")}: ${e.message}"
            }
            result += Entry(b, resolved, toKotlin(b.config) as Map<String, Any?>)
        }
        for (unknown in spec.restart.keys - ids) problems += "restart policy for unknown block '$unknown'"
        if (problems.isNotEmpty()) throw FabricException("fabric '${spec.id}' cannot be created:\n" + problems.joinToString("\n") { "  $it" })
        return result
    }

    private fun computeStatus(): FabricStatus {
        val blocks = hosts.map { BlockStatus(it.id, it.state, it.restarts, it.lastError) }
        val state = when {
            startFailure != null -> FabricState.FAILED
            phase == FabricState.RUNNING || phase == FabricState.FAILED ->
                if (blocks.any { it.state == BlockState.FAILED }) FabricState.FAILED else FabricState.RUNNING
            else -> phase
        }
        return FabricStatus(spec.id, spec.blueprint.name, state, blocks, startFailure)
    }

    private fun publish() {
        mutableStatus.value = computeStatus()
    }

    private fun log(level: FabricLogger.Level, message: String) {
        try {
            spec.logger.log(level, message)
        } catch (_: Exception) {
            // logging must never break the fabric
        }
    }

    /**
     * Starts all blocks in blueprint order. Fails with [FabricException] before starting anything if a block would need
     * more isolation than is available (fail closed). A block that fails to start goes through its restart policy;
     * this call returns once every block has been attempted.
     */
    public suspend fun start() {
        lifecycle.withLock {
            check(phase == FabricState.CREATED || phase == FabricState.STOPPED) { "fabric '${spec.id}' cannot be started in state $phase" }
            for (e in entries) {
                val level = IsolationResolver.resolve(e.resolved.trust, e.instance.isolation)
                if (level != IsolationLevel.SHARED) {
                    val message = "block '${e.instance.id}' needs isolation level $level (plugin ${e.resolved.trust}, block wish " +
                        "${e.instance.isolation}), which is not supported yet; fabric '${spec.id}' is not started"
                    startFailure = message
                    publish()
                    throw FabricException(message)
                }
            }
            startFailure = null
            phase = FabricState.STARTING
            publish()
            try {
                spec.paths.create(entries.map { it.instance.id })
                log(FabricLogger.Level.INFO, "starting fabric '${spec.id}' (blueprint '${spec.blueprint.name}')")
                // The tethers are open before the first block runs: a message a block sends in start() finds the tether
                // open, and a block that is not running yet is handled by the delivery policy of its tethers.
                network?.open { blockId, event -> deliver(blockId, event) }
                withContext(dispatcher) {
                    hosts.forEach { it.reset() }
                    for (host in hosts) host.bringUp()
                }
            } catch (e: CancellationException) {
                // a cancelled start leaves nothing behind, so the fabric can be started again
                network?.close()
                phase = FabricState.STOPPED
                publish()
                throw e
            } catch (e: Exception) {
                val message = "fabric '${spec.id}' cannot be started: ${e.message}"
                startFailure = message
                network?.close()
                phase = FabricState.STOPPED
                publish()
                throw FabricException(message, e)
            }
            phase = FabricState.RUNNING
            publish()
        }
    }

    /** Stops all blocks in reverse order and destroys them. Does nothing if the fabric is not running. */
    public suspend fun stop() {
        lifecycle.withLock {
            if (phase == FabricState.CREATED || phase == FabricState.STOPPED) return
            phase = FabricState.STOPPING
            publish()
            network?.close()
            // the sockets of the TCP tethers are closed without blocking; the ports are free a moment later
            if (network != null && network.awaitClosed(TCP_CLOSE_TIMEOUT) == false) {
                log(FabricLogger.Level.WARN, "fabric '${spec.id}': TCP listeners of the tethers were not closed within $TCP_CLOSE_TIMEOUT")
            }
            withContext(dispatcher) { hosts.asReversed().forEach { it.stopHost() } }
            phase = FabricState.STOPPED
            log(FabricLogger.Level.INFO, "fabric '${spec.id}' stopped")
            publish()
        }
    }

    /** Delivers [event] to block [blockId]; an exception thrown by the block counts as a crash. */
    public suspend fun deliver(blockId: String, event: TetherEvent) {
        val host = hosts.firstOrNull { it.id == blockId } ?: throw IllegalArgumentException("unknown block '$blockId'")
        withContext(dispatcher) { host.deliver(event) }
    }

    /** Reports that block [blockId] crashed for a reason detected outside its own calls (for example by a driver). */
    public suspend fun reportCrash(blockId: String, cause: Throwable) {
        val host = hosts.firstOrNull { it.id == blockId } ?: throw IllegalArgumentException("unknown block '$blockId'")
        withContext(dispatcher) { host.crashed(cause) }
    }

    /** Replaces the instances of the services that the sending tethers call; see [TetherNetwork.updateServiceBindings]. */
    public fun updateServiceBindings(remotes: Map<String, cringle.packaging.RemoteEndpoint>) {
        network?.updateServiceBindings(remotes)
    }

    /** Replaces the engines that may call the provided service ports of the blueprint; see [TetherNetwork.setServiceCallers]. */
    public fun setServiceCallers(fingerprints: List<String>) {
        val n = network ?: throw FabricException("fabric '${spec.id}' has no tethers")
        try {
            n.setServiceCallers(fingerprints)
        } catch (e: cringle.engine.tether.TetherWiringException) {
            throw FabricException("fabric '${spec.id}': ${e.message}", e)
        }
    }

    /** Stops the fabric, then releases its thread, watchdog and [FabricSpec.onClose]. Do not call from a block. */
    override fun close() {
        try {
            runBlocking { stop() }
        } finally {
            network?.release()
            watchdog?.shutdownNow()
            scope.cancel()
            executor.shutdown()
            spec.onClose?.close()
        }
    }

    private fun startWatchdog() {
        val wd = watchdog ?: return
        val intervalMs = spec.watchdog.interval.inWholeMilliseconds.coerceAtLeast(1)
        val thresholdNs = spec.watchdog.threshold.inWholeNanoseconds
        var pendingSince = 0L
        var pending = false
        var warned = false
        val lock = Any()
        wd.scheduleWithFixedDelay(
            {
                val now = System.nanoTime()
                synchronized(lock) {
                    if (pending) {
                        if (!warned && now - pendingSince > thresholdNs) {
                            warned = true
                            log(
                                FabricLogger.Level.WARN,
                                "fabric '${spec.id}': the fabric thread has not answered for more than ${thresholdNs.nanoseconds}; " +
                                    "currently executing: ${currentExecution ?: "nothing of a block (blocked elsewhere)"}. " +
                                    "Blocks must not block their execution context.",
                            )
                        }
                    } else {
                        pending = true
                        pendingSince = now
                        warned = false
                        try {
                            executor.execute { synchronized(lock) { pending = false } }
                        } catch (_: RejectedExecutionException) {
                            pending = false
                        }
                    }
                }
            },
            intervalMs,
            intervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private inner class BlockHost(private val entry: Entry) {
        val id: String = entry.instance.id
        private val blockId = BlockId(id)
        private val policy = spec.restart[id] ?: spec.defaultRestart
        private val definition = entry.resolved.definition
        private var block: Block? = null
        private var driverSet: DriverSet? = null
        private var restartJob: Job? = null
        private var generation = 0

        @Volatile var state = BlockState.CREATED
            private set

        @Volatile var restarts = 0
            private set

        @Volatile var lastError: String? = null
            private set

        private val ports: BlockPorts = buildPorts()

        private val context = object : BlockContext {
            override val blockId: BlockId = this@BlockHost.blockId
            override val config: Map<String, Any?> = entry.config
            override val ports: BlockPorts = this@BlockHost.ports
        }

        private fun buildPorts(): BlockPorts {
            val plain = HashMap<String, Tether>()
            val varArg = HashMap<String, List<Tether>>()
            for (p in definition.ports) {
                if (p.varArg) {
                    val size = entry.instance.varArgCounts[p.name] ?: 0
                    varArg[p.name] = List(size) { wiring.tether(blockId, p, it) }
                } else {
                    plain[p.name] = wiring.tether(blockId, p, null)
                }
            }
            return object : BlockPorts {
                override fun port(name: String): Tether = plain[name] ?: throw IllegalArgumentException("No plain port '$name'")

                override fun varArgPort(name: String): List<Tether> = varArg[name] ?: throw IllegalArgumentException("No VarArg port '$name'")
            }
        }

        private fun set(s: BlockState) {
            state = s
            publish()
        }

        fun reset() {
            restarts = 0
            lastError = null
            state = BlockState.CREATED
        }

        private suspend fun <T> call(name: String, body: suspend () -> T): T {
            currentExecution = "block '$id' $name"
            try {
                return body()
            } finally {
                currentExecution = null
            }
        }

        suspend fun bringUp() {
            val gen = generation
            set(BlockState.STARTING)
            try {
                val drivers = spec.drivers.driversFor(blockId, definition.requiredDrivers)
                driverSet = drivers
                val created = entry.resolved.provider.createBlock(definition.name, drivers)
                block = created
                call("init") { created.init(context) }
                if (gen != generation) return teardown()
                call("start") { created.start() }
                if (gen != generation) return teardown()
                set(BlockState.RUNNING)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                crashed(e)
            }
        }

        suspend fun deliver(event: TetherEvent) {
            val b = block
            if (state != BlockState.RUNNING || b == null) throw FabricException("block '$id' is not running (state $state)")
            try {
                call("onTetherEvent") { b.onTetherEvent(event) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                crashed(e)
            }
        }

        suspend fun crashed(cause: Throwable) {
            if (state == BlockState.STOPPING || state == BlockState.STOPPED) return
            lastError = "${cause.javaClass.simpleName}: ${cause.message}"
            log(FabricLogger.Level.ERROR, "block '$id' crashed: $lastError")
            teardown()
            if (restarts < policy.maxRetries) {
                restarts++
                val wait = policy.delayFor(restarts)
                log(FabricLogger.Level.INFO, "restarting block '$id' in $wait (restart $restarts of ${policy.maxRetries})")
                set(BlockState.RESTARTING)
                val gen = generation
                restartJob = scope.launch {
                    delay(wait)
                    if (state == BlockState.RESTARTING && gen == generation) bringUp()
                }
            } else {
                log(FabricLogger.Level.ERROR, "block '$id' failed for good after $restarts restart(s)")
                set(BlockState.FAILED)
            }
        }

        private suspend fun teardown() {
            try {
                teardownBlock()
            } finally {
                // releases what the drivers hold for this block (ports, connections)
                val set = driverSet
                driverSet = null
                (set as? AutoCloseable)?.let { runCatching { it.close() } }
                if (set != null && !set.awaitClosed(TCP_CLOSE_TIMEOUT)) {
                    log(FabricLogger.Level.WARN, "block '$id': the drivers were not closed within $TCP_CLOSE_TIMEOUT")
                }
            }
        }

        private suspend fun teardownBlock() {
            val b = block ?: return
            block = null
            try {
                call("stop") { b.stop() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log(FabricLogger.Level.WARN, "block '$id' failed while stopping: $e")
            }
            try {
                call("destroy") { b.destroy() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log(FabricLogger.Level.WARN, "block '$id' failed while being destroyed: $e")
            }
        }

        suspend fun stopHost() {
            if (state == BlockState.CREATED || state == BlockState.STOPPED) return
            generation++
            restartJob?.cancel()
            restartJob = null
            set(BlockState.STOPPING)
            teardown()
            set(BlockState.STOPPED)
        }
    }

    private fun toKotlin(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.content == "true" -> true
            e.content == "false" -> false
            else -> e.content.toLongOrNull() ?: e.content.toDouble()
        }
        is JsonArray -> e.map { toKotlin(it) }
        is JsonObject -> e.mapValues { toKotlin(it.value) }
    }
}
