// SPDX-License-Identifier: Apache-2.0

package cringle.engine.fabric

import cringle.contract.BlockDefinition
import cringle.contract.BlockId
import cringle.contract.BlockProvider
import cringle.contract.DriverSet
import cringle.contract.IsolationLevel
import cringle.contract.PortDefinition
import cringle.contract.Tether
import cringle.contract.TetherByteStream
import cringle.contract.TetherStream
import cringle.contract.TetherType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Lifecycle states of a fabric instance. */
public enum class FabricState {
    /** Created, never started. */
    CREATED,

    /** Blocks are being started. */
    STARTING,

    /** All blocks run (or are waiting for a restart). */
    RUNNING,

    /** Blocks are being stopped. */
    STOPPING,

    /** Stopped; can be started again. */
    STOPPED,

    /** A block failed for good, or the fabric could not be started. */
    FAILED,
}

/** Lifecycle states of a block inside a fabric. */
public enum class BlockState {
    /** Not started yet. */
    CREATED,

    /** `init` and `start` are running. */
    STARTING,

    /** Running. */
    RUNNING,

    /** Crashed; waiting for the backoff time before the next start attempt. */
    RESTARTING,

    /** `stop` and `destroy` are running. */
    STOPPING,

    /** Stopped. */
    STOPPED,

    /** Crashed and no retries left. */
    FAILED,
}

/** Status of one block. */
public data class BlockStatus(val id: String, val state: BlockState, val restarts: Int, val lastError: String?)

/** Status of a fabric instance, including its blocks in blueprint order. */
public data class FabricStatus(
    val id: String,
    val blueprint: String,
    val state: FabricState,
    val blocks: List<BlockStatus>,
    val failure: String? = null,
)

/**
 * Automatic restart of a crashed block: at most [maxRetries] restarts per fabric run (0 disables restarts), waiting
 * [backoff] before the first, then doubling up to [maxBackoff].
 */
public data class RestartPolicy(
    val maxRetries: Int = 0,
    val backoff: Duration = 1.seconds,
    val maxBackoff: Duration = 30.seconds,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must not be negative" }
        require(!backoff.isNegative() && !maxBackoff.isNegative()) { "backoff must not be negative" }
    }

    /** The wait before restart number [attempt] (1 for the first restart). */
    public fun delayFor(attempt: Int): Duration {
        var d = backoff
        repeat(attempt - 1) { d = if (d * 2 > maxBackoff) maxBackoff else d * 2 }
        return if (d > maxBackoff) maxBackoff else d
    }
}

/** Trust status of a plugin, set centrally in the repository. */
public enum class PluginTrust {
    /** The operator trusts the plugin. */
    TRUSTED,

    /** Not trusted; its blocks need process isolation. */
    UNTRUSTED,
}

/** The isolation rule: the stricter of the plugin's trust status and the block's wish wins (decisions.md). */
public object IsolationResolver {
    /** Returns the resulting level; an untrusted plugin always yields [IsolationLevel.PROCESS]. */
    public fun resolve(trust: PluginTrust, wish: IsolationLevel): IsolationLevel {
        val required = if (trust == PluginTrust.UNTRUSTED) IsolationLevel.PROCESS else IsolationLevel.SHARED
        return maxOf(required, wish)
    }
}

/** Thrown when a fabric cannot be created or started; the message is meant for the operator. */
public class FabricException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A block definition together with the provider that creates it and the trust status of its plugin. */
public class ResolvedBlock(public val provider: BlockProvider, public val definition: BlockDefinition, public val trust: PluginTrust)

/** Finds the provider and definition for a blueprint block reference `pluginName/blockName`. */
public fun interface BlockResolver {
    /** Returns the block, or `null` if the reference is unknown. */
    public fun resolve(blockRef: String): ResolvedBlock?
}

/** Supplies the drivers of a block (the engine has one instance per driver type; concrete drivers come with #11). */
public fun interface DriverFactory {
    /** The drivers for [blockId], which needs the driver ids [requiredDrivers]. */
    public fun driversFor(blockId: BlockId, requiredDrivers: List<String>): DriverSet
}

/** Creates the tether behind one port slot. Tether transport is implemented by #10. */
public fun interface PortWiring {
    /** The tether of [port] (VarArg slot [index]) of block [blockId]. */
    public fun tether(blockId: BlockId, port: PortDefinition, index: Int?): Tether
}

/** Placeholder wiring: every operation fails with a clear message until tethers exist. */
public object UnconnectedPorts : PortWiring {
    override fun tether(blockId: BlockId, port: PortDefinition, index: Int?): Tether = object : Tether {
        override val type: TetherType = port.tetherTypes.first()
        private fun unavailable(): Nothing = throw UnsupportedOperationException(
            "port '${port.name}' of block '$blockId' is not connected: tether transport is not available yet",
        )

        override suspend fun send(message: Any): Unit = unavailable()

        override suspend fun request(request: Any): Any = unavailable()

        override suspend fun openStream(): TetherStream = unavailable()

        override suspend fun openByteStream(): TetherByteStream = unavailable()
    }
}

/** Log sink of a fabric. */
public fun interface FabricLogger {
    /** Logs [message] at [level]. */
    public fun log(level: Level, message: String)

    /** Severity of a log line. */
    public enum class Level {
        /** Normal events. */
        INFO,

        /** Something needs attention. */
        WARN,

        /** A failure. */
        ERROR,
    }
}

/** The directories of a fabric instance below the engine directory (Architecture 15.1). */
public class FabricPaths(engineDir: Path, fabricId: String) {
    private val fabrics: Path = engineDir.resolve("fabrics")

    /** `<engine dir>/fabrics/<fabric id>`. */
    public val root: Path = contained(fabrics, fabrics.resolve(fabricId))

    /** Working directory of the fabric. */
    public val working: Path = root.resolve("working")

    /** Log directory of the fabric. */
    public val logs: Path = root.resolve("logs")

    /** Working directory of one block. */
    public fun blockWorking(blockId: String): Path = contained(working, working.resolve(blockId))

    /** Log directory of one block. */
    public fun blockLogs(blockId: String): Path = contained(logs, logs.resolve(blockId))

    /** Creates the fabric and block directories. */
    public fun create(blockIds: List<String>) {
        Files.createDirectories(working)
        Files.createDirectories(logs)
        for (id in blockIds) {
            Files.createDirectories(blockWorking(id))
            Files.createDirectories(blockLogs(id))
        }
    }

    /** A logger that appends to `<logs>/fabric.log`. */
    public fun fileLogger(): FabricLogger = FabricLogger { level, message ->
        Files.createDirectories(logs)
        Files.writeString(
            logs.resolve("fabric.log"),
            "${java.time.Instant.now()} $level $message\n",
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
    }
}

/** A name that is no name at all must not be able to build a path outside [root], whatever the file system does with it. */
private fun contained(root: Path, path: Path): Path {
    if (!path.normalize().startsWith(root.normalize())) throw FabricException("path '$path' leaves '$root'")
    return path
}

/** Watchdog settings: every [interval] a heartbeat is posted to the fabric thread; no answer within [threshold] is reported. */
public data class WatchdogConfig(val interval: Duration = 1.seconds, val threshold: Duration = 5.seconds, val enabled: Boolean = true)

