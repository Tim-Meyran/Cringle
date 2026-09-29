// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.BlockDefinition
import cringle.contract.BlockProvider
import cringle.contract.DriverSet
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherEvent
import cringle.contract.TetherType
import cringle.engine.drivers.BuiltinDrivers
import cringle.engine.fabric.BlockResolver
import cringle.engine.fabric.FabricException
import cringle.engine.fabric.FabricPaths
import cringle.engine.fabric.FabricRuntime
import cringle.engine.fabric.FabricSpec
import cringle.engine.fabric.PluginTrust
import cringle.engine.fabric.ResolvedBlock
import cringle.engine.fabric.WatchdogConfig
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.DeliveryPolicy
import cringle.packaging.Endpoint
import cringle.packaging.TetherDef
import cringle.schema.SchemaRegistry
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class TcpTetherTest {
    @TempDir
    lateinit var dir: Path

    private val bytes = SchemaRef("cringle.std", "Bytes")
    private val types = setOf(TetherType.TCP)
    private val src = BlockDefinition("src", emptyList(), listOf(PortDefinition("out", PortDirection.OUT, types, bytes)), emptyList())
    private val dst = BlockDefinition("dst", emptyList(), listOf(PortDefinition("in", PortDirection.IN, types, bytes)), emptyList())

    private class TestBlock(val onEvent: suspend (TetherEvent) -> Unit = {}) : Block {
        lateinit var context: BlockContext

        override suspend fun init(context: BlockContext) {
            this.context = context
        }

        override suspend fun onTetherEvent(event: TetherEvent) = onEvent(event)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun fabric(id: String, drivers: BuiltinDrivers, tethers: List<TetherDef>, s: TestBlock, d: TestBlock): FabricRuntime {
        val provider = object : BlockProvider {
            override val definitions = listOf(src, dst)
            override fun createBlock(definitionName: String, drivers: DriverSet): Block = if (definitionName == "src") s else d
        }
        val paths = FabricPaths(dir.resolve("engine"), id)
        return FabricRuntime(
            FabricSpec(
                id = id,
                blueprint = Blueprint("bp", listOf(BlueprintBlock("s", "p/src"), BlueprintBlock("d", "p/dst")), tethers),
                resolver = BlockResolver { ref -> listOf(src, dst).firstOrNull { "p/${it.name}" == ref }?.let { ResolvedBlock(provider, it, PluginTrust.TRUSTED) } },
                drivers = drivers.factoryFor(id, paths),
                paths = paths,
                tethers = TetherConfig(SchemaRegistry(), tcp = { block -> drivers.tcp.driverFor(id, block) }),
                schemas = SchemaRegistry(),
                watchdog = WatchdogConfig(enabled = false),
            ),
        )
    }

    private fun tcp(port: Int?) = TetherDef(TetherType.TCP, Endpoint("s", "out"), Endpoint("d", "in"), DeliveryPolicy.DROP, port)

    @Test
    fun twoBlocksExchangeBytesOverARealTcpConnection(): Unit = runBlocking {
        BuiltinDrivers(dir.resolve("engine")).use { drivers ->
            val opened = CompletableDeferred<TetherEvent.ByteStreamOpened>()
            val s = TestBlock()
            val d = TestBlock { opened.complete(it as TetherEvent.ByteStreamOpened) }
            val port = freePort()
            fabric("f", drivers, listOf(tcp(port)), s, d).use { f ->
                f.start()
                assertEquals("block 'd' of fabric 'f'", drivers.tcp.ports.owner(port))
                val client = s.context.ports.port("out").openByteStream()
                val server = withTimeout(10.seconds) { opened.await() }.stream
                client.write("ping".toByteArray())
                assertEquals("ping", String(withTimeout(10.seconds) { server.incoming.first() }))
                server.write("pong".toByteArray())
                assertEquals("pong", String(withTimeout(10.seconds) { client.incoming.first() }))
                f.stop()
                assertNull(drivers.tcp.ports.owner(port), "the port is free after the fabric stopped")
            }
        }
    }

    @Test
    fun portConflictFailsTheStartWithAClearMessage(): Unit = runBlocking {
        BuiltinDrivers(dir.resolve("engine")).use { drivers ->
            val port = freePort()
            val first = fabric("f1", drivers, listOf(tcp(port)), TestBlock(), TestBlock())
            val second = fabric("f2", drivers, listOf(tcp(port)), TestBlock(), TestBlock())
            first.use { a ->
                second.use { b ->
                    a.start()
                    val e = assertThrows<FabricException> { runBlocking { b.start() } }
                    assertTrue(e.message!!.contains("port $port is already used by block 'd' of fabric 'f1'"), e.message)
                    assertEquals("block 'd' of fabric 'f1'", drivers.tcp.ports.owner(port))
                }
            }
        }
    }

    @Test
    fun wiringNeedsAPortAndADriver() {
        val blocks = listOf(BlueprintBlock("s", "p/src"), BlueprintBlock("d", "p/dst"))
        val defs = mapOf("s" to src, "d" to dst)
        val noPort = assertThrows<TetherWiringException> { TetherNetwork.create(Blueprint("bp", blocks, listOf(tcp(null))), defs, TetherConfig(tcp = { throw IllegalStateException() })) }
        assertTrue(noPort.message!!.contains("needs a port"), noPort.message)
        val noDriver = assertThrows<TetherWiringException> { TetherNetwork.create(Blueprint("bp", blocks, listOf(tcp(1234))), defs, TetherConfig()) }
        assertTrue(noDriver.message!!.contains("no TCP driver"), noDriver.message)
    }
}
