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
import cringle.engine.drivers.TcpService
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private val src = BlockDefinition(
        "src",
        emptyList(),
        listOf(
            PortDefinition("out", PortDirection.OUT, types, bytes),
            PortDefinition("out2", PortDirection.OUT, types, bytes),
        ),
        emptyList(),
    )
    private val dst = BlockDefinition(
        "dst",
        emptyList(),
        listOf(
            PortDefinition("in", PortDirection.IN, types, bytes),
            PortDefinition("in2", PortDirection.IN, types, bytes),
        ),
        emptyList(),
    )

    private class TestBlock(val onEvent: suspend (TetherEvent) -> Unit = {}) : Block {
        lateinit var context: BlockContext

        override suspend fun init(context: BlockContext) {
            this.context = context
        }

        override suspend fun onTetherEvent(event: TetherEvent) = onEvent(event)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun fabric(id: String, drivers: BuiltinDrivers, tethers: List<TetherDef>, s: TestBlock, d: TestBlock, capacity: Int = 64): FabricRuntime {
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
                tethers = TetherConfig(SchemaRegistry(), bufferCapacity = capacity, tcp = { block -> drivers.tcp.driverFor(id, block) }),
                schemas = SchemaRegistry(),
                watchdog = WatchdogConfig(enabled = false),
            ),
        )
    }

    private fun tcp(port: Int?) = TetherDef(TetherType.TCP, Endpoint("s", "out"), Endpoint("d", "in"), DeliveryPolicy.DROP, port)

    private fun tcp2(port: Int?) = TetherDef(TetherType.TCP, Endpoint("s", "out2"), Endpoint("d", "in2"), DeliveryPolicy.DROP, port)

    /**
     * First half of the M2 criterion ("Fertig, wenn" in `docs/Architecture.md`): two blocks exchange bytes over TCP. TCP
     * is a byte transport for external communication; the second half, a schema violation at a local tether, is
     * `TetherNetworkTest.schemaViolationsAreReportedWithSchemaAndPath`.
     */
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

    /**
     * M2 (TCP tether) with several connections: every connection of one tether is its own stream. Five clients send their
     * own number at the same time, the receiver echoes on the stream the bytes came in on, and each client gets exactly
     * its own echo back.
     */
    @Test
    fun severalConnectionsOfOneTcpTetherAreIndependentStreams(): Unit = runBlocking {
        BuiltinDrivers(dir.resolve("engine")).use { drivers ->
            val opened = Channel<TetherEvent.ByteStreamOpened>(Channel.UNLIMITED)
            val s = TestBlock()
            val d = TestBlock { if (it is TetherEvent.ByteStreamOpened) opened.send(it) }
            fabric("f", drivers, listOf(tcp(freePort())), s, d).use { f ->
                f.start()
                val clients = (0 until 5).map { s.context.ports.port("out").openByteStream() }
                val servers = launch {
                    repeat(5) {
                        val stream = withTimeout(10.seconds) { opened.receive() }.stream
                        launch { stream.write("echo:".toByteArray() + stream.incoming.first()) }
                    }
                }
                clients.forEachIndexed { i, client -> client.write("c$i".toByteArray()) }
                clients.forEachIndexed { i, client -> assertEquals("echo:c$i", String(withTimeout(10.seconds) { client.incoming.first() })) }
                servers.join()
                f.stop()
            }
        }
    }

    /**
     * Restarting a fabric while a connection is open: stopping closes the connection (the client sees the end of the
     * stream instead of waiting forever), frees the port, and after the next start the tether works again on the same
     * port with a new connection.
     */
    @Test
    fun restartingTheFabricWithAnOpenConnectionClosesItAndTheTetherWorksAgain(): Unit = runBlocking {
        BuiltinDrivers(dir.resolve("engine")).use { drivers ->
            val opened = Channel<TetherEvent.ByteStreamOpened>(Channel.UNLIMITED)
            val s = TestBlock()
            val d = TestBlock { if (it is TetherEvent.ByteStreamOpened) opened.send(it) }
            val port = freePort()
            fabric("f", drivers, listOf(tcp(port)), s, d).use { f ->
                f.start()
                val first = s.context.ports.port("out").openByteStream()
                val firstServer = withTimeout(10.seconds) { opened.receive() }.stream
                first.write("one".toByteArray())
                assertEquals("one", String(withTimeout(10.seconds) { firstServer.incoming.first() }))

                f.stop()
                assertNull(drivers.tcp.ports.owner(port), "the port is free after the stop")
                // the client of the open connection learns that it is closed: its stream ends or fails, it does not hang
                val ended = runCatching { withTimeout(10.seconds) { first.incoming.collect { } } }
                assertTrue(ended.exceptionOrNull() !is kotlinx.coroutines.TimeoutCancellationException, "the open connection was left open by the stop")

                f.start()
                val second = s.context.ports.port("out").openByteStream()
                val secondServer = withTimeout(10.seconds) { opened.receive() }.stream
                second.write("two".toByteArray())
                assertEquals("two", String(withTimeout(10.seconds) { secondServer.incoming.first() }))
                secondServer.write("back".toByteArray())
                assertEquals("back", String(withTimeout(10.seconds) { second.incoming.first() }))
            }
        }
    }

    /**
     * The check of the port (`SO_REUSEADDR` in `Tcp.kt`): a port that another socket holds is reported as a conflict, on
     * every platform. On Windows `SO_REUSEADDR` would let a second socket bind to a port that is in use, so this is the
     * test that shows the start fails there as well, with a message, and leaves no claim on the port behind.
     */
    @Test
    fun aPortThatAnotherSocketHoldsIsReportedAsAConflict(): Unit = runBlocking {
        BuiltinDrivers(dir.resolve("engine")).use { drivers ->
            ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress()).use { other ->
                val port = other.localPort
                fabric("f", drivers, listOf(tcp(port)), TestBlock(), TestBlock()).use { f ->
                    val e = assertThrows<FabricException> { runBlocking { f.start() } }
                    assertTrue(e.message!!.contains("port $port is in use by another process"), e.message)
                    assertNull(drivers.tcp.ports.owner(port), "a failed start must not keep the claim on the port")
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

    @Test
    fun connectionsOverTheAcceptBufferAreClosedAndLoggedAndTheFabricKeepsWorking(): Unit = runBlocking {
        val warnings = CopyOnWriteArrayList<String>()
        BuiltinDrivers(dir.resolve("engine"), TcpService { warnings += it }).use { drivers ->
            // the receiving block takes the first connection and then stops taking any, so the buffer fills up
            val gate = CompletableDeferred<Unit>()
            val taken = AtomicInteger()
            val d = TestBlock { if (it is TetherEvent.ByteStreamOpened) { taken.incrementAndGet(); gate.await() } }
            val s = TestBlock()
            fabric("f", drivers, listOf(tcp(freePort())), s, d, capacity = 1).use { f ->
                f.start()
                val streams = (1..4).map { s.context.ports.port("out").openByteStream() }.toMutableList()
                withTimeout(20.seconds) { while (warnings.size < 2) delay(10) }
                assertEquals(2, warnings.size, "one connection is in the block and one in the buffer of 1: $warnings")
                assertTrue(warnings.all { it.contains("accept buffer of 1 is full") }, warnings.toString())
                gate.complete(Unit)
                withTimeout(20.seconds) { while (taken.get() < 2) delay(10) }
                // the fabric is still usable for new connections
                streams += s.context.ports.port("out").openByteStream()
                withTimeout(20.seconds) { while (taken.get() < 3) delay(10) }
                streams.forEach { it.close() }
            }
        }
    }

    @Test
    fun twoTcpTethersCloseTogetherWithoutBlockingAThread(): Unit = runBlocking {
        BuiltinDrivers(dir.resolve("engine")).use { drivers ->
            val ports = listOf(freePort(), freePort())
            fabric("f", drivers, listOf(tcp(ports[0]), tcp2(ports[1])), TestBlock(), TestBlock()).use { f ->
                f.start()
                ports.forEach { assertEquals("block 'd' of fabric 'f'", drivers.tcp.ports.owner(it)) }
                val one = Dispatchers.Default.limitedParallelism(1)
                val start = System.nanoTime()
                val stopper = async(one) { f.stop() }
                // the thread that stops the fabric keeps running other work: closing suspends instead of blocking
                assertEquals(42, withTimeout(2.seconds) { async(one) { 42 }.await() })
                withTimeout(10.seconds) { stopper.await() }
                val millis = (System.nanoTime() - start) / 1_000_000
                assertTrue(millis < 2_000, "both tethers close together, took $millis ms")
                ports.forEach { assertNull(drivers.tcp.ports.owner(it), "port $it is free after the fabric stopped") }
            }
        }
    }
}
