// SPDX-License-Identifier: Apache-2.0

package cringle.engine.drivers

import cringle.contract.BuiltinDriverTypes
import cringle.contract.BlockId
import cringle.contract.Driver
import cringle.contract.DriverSet
import cringle.engine.fabric.DriverFactory
import cringle.engine.fabric.FabricPaths
import java.nio.file.Path
import kotlin.reflect.KClass

/**
 * The built-in drivers of one engine (Architecture chapter 11): exactly one logging service, one port registry per
 * engine. Blocks get views of these that are bound to their identity (fabric and block id), see [factoryFor].
 */
public class BuiltinDrivers(engineDir: Path) : AutoCloseable {
    /** The engine-wide log service and store. */
    public val logging: LoggingService = LoggingService(engineDir)

    /** The engine-wide TCP service with its port registry. */
    public val tcp: TcpService = TcpService()

    /** A [DriverFactory] for the blocks of fabric [fabricId] whose directories are [paths]. */
    public fun factoryFor(fabricId: String, paths: FabricPaths): DriverFactory = DriverFactory { blockId, required ->
        Blocks(this, fabricId, paths, blockId, required)
    }

    override fun close() {
        tcp.close()
    }

    private class Blocks(
        services: BuiltinDrivers,
        fabricId: String,
        paths: FabricPaths,
        blockId: BlockId,
        required: List<String>,
    ) : DriverSet, AutoCloseable {
        private val instances = LinkedHashMap<String, Driver>()

        init {
            for (id in required.distinct()) {
                instances[id] = when (id) {
                    BuiltinDriverTypes.LOGGING.id -> services.logging.driverFor(fabricId, blockId.value, paths.blockLogs(blockId.value))
                    BuiltinDriverTypes.FILESYSTEM.id -> FilesystemSandbox(paths.blockWorking(blockId.value))
                    BuiltinDriverTypes.TCP.id -> services.tcp.driverFor(fabricId, blockId.value)
                    else -> throw IllegalArgumentException(
                        "block '${blockId.value}' requires unknown driver '$id' (built-in: ${BuiltinDriverTypes.ALL.keys.joinToString()})",
                    )
                }
            }
        }

        override fun <T : Driver> get(type: KClass<T>): T {
            val found = instances.values.firstOrNull { type.isInstance(it) }
                ?: throw IllegalArgumentException("driver ${type.simpleName} is not among the required drivers of this block")
            return type.java.cast(found)
        }

        override fun close() {
            instances.values.filterIsInstance<AutoCloseable>().forEach { runCatching { it.close() } }
        }
    }
}
