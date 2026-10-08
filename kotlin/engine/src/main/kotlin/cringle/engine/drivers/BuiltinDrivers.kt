// SPDX-License-Identifier: Apache-2.0

package cringle.engine.drivers

import cringle.contract.BuiltinDriverTypes
import cringle.contract.BlockId
import cringle.contract.Driver
import cringle.contract.DriverSet
import cringle.engine.fabric.DriverFactory
import cringle.engine.fabric.FabricPaths
import java.nio.file.Path
import java.time.Duration
import kotlin.reflect.KClass

/**
 * The built-in drivers of one engine (Architecture chapter 11): exactly one logging service, one port registry per
 * engine. Blocks get views of these that are bound to their identity (fabric and block id), see [factoryFor].
 */
public class BuiltinDrivers(
    engineDir: Path,
    /** The engine-wide TCP service; a caller that wants to see the warnings of its drivers passes its own. */
    public val tcp: TcpService = TcpService(),
    /** The engine-wide serial service; a caller that wants to see the warnings of its drivers passes its own. */
    public val serial: SerialService = SerialService(),
    /** The data warehouse of the engine (`<engine dir>/dwh`, #188). */
    public val dwh: cringle.engine.dwh.Dwh = cringle.engine.dwh.Dwh(engineDir.resolve("dwh")),
) : AutoCloseable {
    /** The engine-wide log service and store. */
    public val logging: LoggingService = LoggingService(engineDir)

    /** A [DriverFactory] for the blocks of fabric [fabricId] whose directories are [paths]. */
    public fun factoryFor(fabricId: String, paths: FabricPaths): DriverFactory = DriverFactory { blockId, required ->
        Blocks(this, fabricId, paths, blockId, required)
    }

    override fun close() {
        tcp.close()
        serial.close()
    }

    private class Blocks(
        services: BuiltinDrivers,
        fabricId: String,
        paths: FabricPaths,
        blockId: BlockId,
        required: List<String>,
    ) : DriverSet, AutoCloseable {
        private val instances = LinkedHashMap<String, Driver>()
        private val tcp = ArrayList<BlockTcp>()
        private val serial = ArrayList<BlockSerial>()

        init {
            for (id in required.distinct()) {
                instances[id] = when (id) {
                    BuiltinDriverTypes.LOGGING.id -> services.logging.driverFor(fabricId, blockId.value, paths.blockLogs(blockId.value))
                    BuiltinDriverTypes.FILESYSTEM.id -> FilesystemSandbox(paths.blockWorking(blockId.value))
                    BuiltinDriverTypes.TCP.id -> services.tcp.driverFor(fabricId, blockId.value).also { tcp += it }
                    BuiltinDriverTypes.SERIAL.id -> services.serial.driverFor(fabricId, blockId.value).also { serial += it }
                    BuiltinDriverTypes.DWH.id -> cringle.engine.dwh.BlockDwhDriver(services.dwh, fabricId, blockId.value)
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

        override suspend fun awaitClosed(timeout: Duration): Boolean = tcp.all { it.awaitClosed(timeout) } && serial.all { it.awaitClosed(timeout) }

        override fun close() {
            instances.values.filterIsInstance<AutoCloseable>().forEach { runCatching { it.close() } }
        }
    }
}
