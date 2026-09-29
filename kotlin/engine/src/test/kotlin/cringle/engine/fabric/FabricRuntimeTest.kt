// SPDX-License-Identifier: Apache-2.0

package cringle.engine.fabric

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.BlockDefinition
import cringle.contract.BlockId
import cringle.contract.BlockProvider
import cringle.contract.DriverSet
import cringle.contract.IsolationLevel
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.PortRef
import cringle.contract.SchemaRef
import cringle.contract.TetherEvent
import cringle.contract.TetherType
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.schema.SchemaRegistry
import cringle.testkit.TestDriverSet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FabricRuntimeTest {
    @TempDir
    lateinit var dir: Path

    /** Shared, per-test record of what happened; each fabric gets its own via its own provider instances. */
    class Recorder {
        val events = CopyOnWriteArrayList<String>()
        val created = AtomicInteger()
        val threads = CopyOnWriteArrayList<String>()
    }

    class RecordingBlock(private val name: String, private val rec: Recorder, private val failStart: () -> Boolean) : Block {
        var config: Map<String, Any?> = emptyMap()
        var received = 0

        override suspend fun init(context: BlockContext) {
            config = context.config
            rec.events += "$name.init"
            rec.threads += Thread.currentThread().name
        }

        override suspend fun start() {
            if (failStart()) throw IllegalStateException("start failed")
            rec.events += "$name.start"
        }

        override suspend fun stop() {
            rec.events += "$name.stop"
        }

        override suspend fun destroy() {
            rec.events += "$name.destroy"
        }

        override suspend fun onTetherEvent(event: TetherEvent) {
            if (event is TetherEvent.Message) {
                if (event.value == "boom") throw IllegalStateException("kaboom")
                if (event.value == "block") CountDownLatch(1).await(10, TimeUnit.SECONDS)
                received++
                rec.events += "$name.got:${event.value}"
            }
        }
    }

    private val schema = SchemaRef("cringle.std", "String")

    private fun definition(name: String) = BlockDefinition(
        name,
        emptyList(),
        listOf(
            PortDefinition("in", PortDirection.IN, setOf(TetherType.MESSAGE), schema),
            PortDefinition("out", PortDirection.OUT, setOf(TetherType.MESSAGE), schema),
            PortDefinition("many", PortDirection.OUT, setOf(TetherType.MESSAGE), schema, varArg = true),
        ),
        emptyList(),
    )

    private class Provider(val defs: List<BlockDefinition>, val make: (String) -> Block) : BlockProvider {
        override val definitions: List<BlockDefinition> = defs

        override fun createBlock(definitionName: String, drivers: DriverSet): Block = make(definitionName)
    }

    private fun spec(
        id: String,
        rec: Recorder,
        blocks: List<BlueprintBlock> = listOf(BlueprintBlock("a", "p/one"), BlueprintBlock("b", "p/two")),
        trust: PluginTrust = PluginTrust.TRUSTED,
        restart: Map<String, RestartPolicy> = emptyMap(),
        failStart: (String) -> Boolean = { false },
        logger: FabricLogger = FabricLogger { _, _ -> },
        watchdog: WatchdogConfig = WatchdogConfig(enabled = false),
        wiring: PortWiring = UnconnectedPorts,
    ): FabricSpec {
        val defs = listOf(definition("one"), definition("two"))
        val provider = Provider(defs) { name -> RecordingBlock(name, rec) { failStart(name) } }
        return FabricSpec(
            id = id,
            blueprint = Blueprint("main", blocks, emptyList()),
            resolver = BlockResolver { ref -> defs.firstOrNull { "p/${it.name}" == ref }?.let { ResolvedBlock(provider, it, trust) } },
            drivers = DriverFactory { _, _ -> TestDriverSet() },
            paths = FabricPaths(dir.resolve("engine"), id),
            restart = restart,
            schemas = SchemaRegistry(),
            logger = logger,
            watchdog = watchdog,
            wiring = wiring,
        )
    }

    private fun FabricRuntime.awaitStatus(timeout: kotlin.time.Duration = 10.seconds, predicate: (FabricStatus) -> Boolean): FabricStatus =
        runBlocking { withTimeout(timeout) { status.first(predicate) } }

    @Test
    fun sampleBlueprintRunsStopsAndRestartsCleanly() = runBlocking {
        val rec = Recorder()
        FabricRuntime(spec("f1", rec)).use { fabric ->
            assertEquals(FabricState.CREATED, fabric.status.value.state)
            fabric.start()
            assertEquals(FabricState.RUNNING, fabric.status.value.state)
            assertEquals(listOf(BlockState.RUNNING, BlockState.RUNNING), fabric.status.value.blocks.map { it.state })
            fabric.deliver("a", TetherEvent.Message(PortRef("in"), "hi"))
            fabric.stop()
            assertEquals(FabricState.STOPPED, fabric.status.value.state)
            assertEquals(
                listOf("one.init", "one.start", "two.init", "two.start", "one.got:hi", "two.stop", "two.destroy", "one.stop", "one.destroy"),
                rec.events.toList(),
            )
            rec.events.clear()
            fabric.start()
            assertEquals(FabricState.RUNNING, fabric.status.value.state)
            assertEquals(listOf("one.init", "one.start", "two.init", "two.start"), rec.events.toList())
            fabric.stop()
            fabric.stop()
        }
    }

    @Test
    fun startingTwiceOrStoppingBeforeStartIsHandled() = runBlocking {
        FabricRuntime(spec("f1", Recorder())).use { fabric ->
            fabric.stop()
            assertEquals(FabricState.CREATED, fabric.status.value.state)
            fabric.start()
            assertThrows<IllegalStateException> { fabric.start() }
        }
    }

    @Test
    fun blocksRunOnTheFabricsOwnThreadAndDirectoriesAreCreated() = runBlocking {
        val rec = Recorder()
        FabricRuntime(spec("f-dirs", rec)).use { fabric ->
            fabric.start()
            assertTrue(rec.threads.all { it.startsWith("fabric-f-dirs") }, rec.threads.toString())
            val paths = FabricPaths(dir.resolve("engine"), "f-dirs")
            for (b in listOf("a", "b")) {
                assertTrue(Files.isDirectory(paths.blockWorking(b)) && Files.isDirectory(paths.blockLogs(b)))
            }
            assertNotEquals(paths.blockWorking("a"), paths.blockWorking("b"))
        }
    }

    @Test
    fun crashTriggersRestartsUpToTheRetryLimitThenTheBlockFails() = runBlocking {
        val rec = Recorder()
        val policy = RestartPolicy(maxRetries = 2, backoff = 1.milliseconds, maxBackoff = 4.milliseconds)
        FabricRuntime(spec("f1", rec, restart = mapOf("a" to policy))).use { fabric ->
            fabric.start()
            fabric.deliver("a", TetherEvent.Message(PortRef("in"), "boom"))
            fabric.awaitStatus { it.blocks[0].state == BlockState.RUNNING && it.blocks[0].restarts == 1 }
            fabric.deliver("a", TetherEvent.Message(PortRef("in"), "boom"))
            fabric.awaitStatus { it.blocks[0].state == BlockState.RUNNING && it.blocks[0].restarts == 2 }
            fabric.deliver("a", TetherEvent.Message(PortRef("in"), "boom"))
            val failed = fabric.awaitStatus { it.blocks[0].state == BlockState.FAILED }
            assertEquals(2, failed.blocks[0].restarts)
            assertEquals("IllegalStateException: kaboom", failed.blocks[0].lastError)
            assertEquals(FabricState.FAILED, failed.state)
            assertEquals(BlockState.RUNNING, failed.blocks[1].state)
            assertThrows<FabricException> { fabric.deliver("a", TetherEvent.Message(PortRef("in"), "x")) }
            assertEquals(3, rec.events.count { it == "one.init" })
            fabric.stop()
            assertEquals(FabricState.STOPPED, fabric.status.value.state)
        }
    }

    @Test
    fun blocksWithoutRetriesFailImmediatelyAndOthersKeepRunning() = runBlocking {
        FabricRuntime(spec("f1", Recorder())).use { fabric ->
            fabric.start()
            fabric.deliver("b", TetherEvent.Message(PortRef("in"), "boom"))
            val s = fabric.status.value
            assertEquals(listOf(BlockState.RUNNING, BlockState.FAILED), s.blocks.map { it.state })
            assertEquals(FabricState.FAILED, s.state)
        }
    }

    @Test
    fun startFailureIsRetriedThroughThePolicy() = runBlocking {
        val rec = Recorder()
        val attempts = AtomicInteger()
        val policy = RestartPolicy(maxRetries = 3, backoff = 1.milliseconds)
        FabricRuntime(spec("f1", rec, restart = mapOf("a" to policy), failStart = { it == "one" && attempts.incrementAndGet() <= 2 })).use { fabric ->
            fabric.start()
            val s = fabric.awaitStatus { it.blocks[0].state == BlockState.RUNNING }
            assertEquals(2, s.blocks[0].restarts)
            assertEquals(FabricState.RUNNING, s.state)
        }
    }

    @Test
    fun stopCancelsPendingRestarts() = runBlocking {
        val rec = Recorder()
        val policy = RestartPolicy(maxRetries = 1, backoff = 30.seconds)
        FabricRuntime(spec("f1", rec, restart = mapOf("a" to policy))).use { fabric ->
            fabric.start()
            fabric.deliver("a", TetherEvent.Message(PortRef("in"), "boom"))
            assertEquals(BlockState.RESTARTING, fabric.status.value.blocks[0].state)
            fabric.stop()
            assertEquals(BlockState.STOPPED, fabric.status.value.blocks[0].state)
            assertEquals(1, rec.events.count { it == "one.init" })
        }
    }

    @Test
    fun untrustedPluginFailsClosedBeforeAnythingIsStarted() = runBlocking {
        val rec = Recorder()
        FabricRuntime(spec("f1", rec, trust = PluginTrust.UNTRUSTED)).use { fabric ->
            val e = assertThrows<FabricException> { fabric.start() }
            assertTrue(e.message!!.contains("needs isolation level PROCESS"), e.message)
            assertEquals(FabricState.FAILED, fabric.status.value.state)
            assertTrue(rec.events.isEmpty())
            assertFalse(Files.exists(FabricPaths(dir.resolve("engine"), "f1").working))
        }
    }

    @Test
    fun processIsolationWishAlsoFailsClosed() = runBlocking {
        val blocks = listOf(BlueprintBlock("a", "p/one", isolation = IsolationLevel.PROCESS))
        FabricRuntime(spec("f1", Recorder(), blocks = blocks)).use { fabric ->
            assertThrows<FabricException> { fabric.start() }
            assertTrue(fabric.status.value.failure!!.contains("block 'a'"))
        }
    }

    @Test
    fun isolationRuleTakesTheStricterLevel() {
        assertEquals(IsolationLevel.SHARED, IsolationResolver.resolve(PluginTrust.TRUSTED, IsolationLevel.SHARED))
        assertEquals(IsolationLevel.PROCESS, IsolationResolver.resolve(PluginTrust.TRUSTED, IsolationLevel.PROCESS))
        assertEquals(IsolationLevel.PROCESS, IsolationResolver.resolve(PluginTrust.UNTRUSTED, IsolationLevel.SHARED))
        assertEquals(IsolationLevel.PROCESS, IsolationResolver.resolve(PluginTrust.UNTRUSTED, IsolationLevel.PROCESS))
    }

    @Test
    fun twoInstancesOfTheSameBlueprintRunConcurrentlyWithoutSharedState() = runBlocking {
        val r1 = Recorder()
        val r2 = Recorder()
        FabricRuntime(spec("i1", r1)).use { one ->
            FabricRuntime(spec("i2", r2)).use { two ->
                one.start()
                two.start()
                one.deliver("a", TetherEvent.Message(PortRef("in"), "only-one"))
                two.deliver("a", TetherEvent.Message(PortRef("in"), "only-two"))
                one.deliver("b", TetherEvent.Message(PortRef("in"), "boom"))
                assertEquals(FabricState.FAILED, one.status.value.state)
                assertEquals(FabricState.RUNNING, two.status.value.state)
                assertTrue("one.got:only-one" in r1.events && "one.got:only-two" !in r1.events)
                assertTrue("one.got:only-two" in r2.events && "one.got:only-one" !in r2.events)
                assertTrue(r1.threads.all { it.startsWith("fabric-i1") } && r2.threads.all { it.startsWith("fabric-i2") })
                assertTrue(Files.isDirectory(FabricPaths(dir.resolve("engine"), "i1").working))
                assertTrue(Files.isDirectory(FabricPaths(dir.resolve("engine"), "i2").working))
            }
        }
    }

    @Test
    fun watchdogWarnsAboutABlockedFabricThreadAndNamesTheBlock() = runBlocking {
        val warned = CountDownLatch(1)
        val messages = CopyOnWriteArrayList<String>()
        val logger = FabricLogger { level, message ->
            if (level == FabricLogger.Level.WARN) {
                messages += message
                warned.countDown()
            }
        }
        val release = CountDownLatch(1)
        val rec = Recorder()
        val base = spec("f1", rec, logger = logger, watchdog = WatchdogConfig(interval = 20.milliseconds, threshold = 60.milliseconds))
        val defs = listOf(definition("one"))
        val blocking = object : Block {
            override suspend fun onTetherEvent(event: TetherEvent) {
                release.await(20, TimeUnit.SECONDS)
            }
        }
        val provider = Provider(defs) { blocking }
        val spec = FabricSpec(
            id = "f1", blueprint = Blueprint("main", listOf(BlueprintBlock("a", "p/one")), emptyList()),
            resolver = BlockResolver { ref -> if (ref == "p/one") ResolvedBlock(provider, defs[0], PluginTrust.TRUSTED) else null },
            drivers = base.drivers, paths = base.paths, logger = logger,
            watchdog = WatchdogConfig(interval = 20.milliseconds, threshold = 60.milliseconds),
        )
        FabricRuntime(spec).use { fabric ->
            fabric.start()
            val delivering = kotlinx.coroutines.GlobalScope.launch { fabric.deliver("a", TetherEvent.Message(PortRef("in"), "x")) }
            assertTrue(warned.await(15, TimeUnit.SECONDS), "no watchdog warning")
            release.countDown()
            delivering.join()
        }
        assertTrue(messages.first().contains("block 'a' onTetherEvent"), messages.first())
        assertTrue(messages.first().contains("must not block"), messages.first())
    }

    @Test
    fun creationProblemsAreCollected() {
        val rec = Recorder()
        val bad = spec("f1", rec, blocks = listOf(BlueprintBlock("a", "p/nope"), BlueprintBlock("a", "p/one")), restart = mapOf("ghost" to RestartPolicy()))
        val e = assertThrows<FabricException> { FabricRuntime(bad) }
        assertTrue(e.message!!.contains("unknown block 'p/nope'") && e.message!!.contains("duplicate block id 'a'") && e.message!!.contains("restart policy for unknown block 'ghost'"), e.message)
    }

    @Test
    fun configIsConvertedToPlainValues() = runBlocking {
        val cfg = JsonObject(mapOf("s" to JsonPrimitive("x"), "n" to JsonPrimitive(3), "d" to JsonPrimitive(1.5), "b" to JsonPrimitive(true)))
        var seen: Map<String, Any?> = emptyMap()
        val defs = listOf(definition("one"))
        val provider = Provider(defs) {
            object : Block {
                override suspend fun init(context: BlockContext) {
                    seen = context.config
                }
            }
        }
        val base = spec("f1", Recorder())
        val spec = FabricSpec(
            "f1", Blueprint("main", listOf(BlueprintBlock("a", "p/one", config = cfg)), emptyList()),
            BlockResolver { ResolvedBlock(provider, defs[0], PluginTrust.TRUSTED) }, base.drivers, base.paths, watchdog = WatchdogConfig(enabled = false),
        )
        FabricRuntime(spec).use { it.start() }
        assertEquals(mapOf("s" to "x", "n" to 3L, "d" to 1.5, "b" to true), seen)
    }

    @Test
    fun unconnectedPortsFailWithAClearMessage() = runBlocking {
        val defs = listOf(definition("one"))
        var ports: cringle.contract.BlockPorts? = null
        val provider = Provider(defs) {
            object : Block {
                override suspend fun init(context: BlockContext) {
                    ports = context.ports
                }
            }
        }
        val base = spec("f1", Recorder())
        val spec = FabricSpec(
            "f1", Blueprint("main", listOf(BlueprintBlock("a", "p/one", varArgCounts = mapOf("many" to 2))), emptyList()),
            BlockResolver { ResolvedBlock(provider, defs[0], PluginTrust.TRUSTED) }, base.drivers, base.paths, watchdog = WatchdogConfig(enabled = false),
        )
        FabricRuntime(spec).use {
            it.start()
            assertEquals(2, ports!!.varArgPort("many").size)
            val e = assertThrows<UnsupportedOperationException> { runBlocking { ports!!.port("out").send("x") } }
            assertTrue(e.message!!.contains("port 'out' of block 'a' is not connected"), e.message)
        }
    }

    @Test
    fun restartPolicyBackoffDoublesUpToTheMaximum() {
        val p = RestartPolicy(3, 100.milliseconds, 350.milliseconds)
        assertEquals(listOf(100.milliseconds, 200.milliseconds, 350.milliseconds, 350.milliseconds), (1..4).map { p.delayFor(it) })
    }
}
