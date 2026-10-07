// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.BlockDefinition
import cringle.contract.BlockProvider
import cringle.contract.DriverSet
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.PortRef
import cringle.contract.SchemaRef
import cringle.contract.Tether
import cringle.contract.TetherEvent
import cringle.contract.TetherType
import cringle.engine.fabric.BlockResolver
import cringle.engine.fabric.DriverFactory
import cringle.engine.fabric.FabricException
import cringle.engine.fabric.FabricPaths
import cringle.engine.fabric.FabricRuntime
import cringle.engine.fabric.FabricSpec
import cringle.engine.fabric.PluginTrust
import cringle.engine.fabric.ResolvedBlock
import cringle.engine.fabric.WatchdogConfig
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.Endpoint
import cringle.packaging.RemoteEndpoint
import cringle.packaging.TetherDef
import cringle.schema.SchemaRegistry
import cringle.testkit.TestDriverSet
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class TetherNetworkTest {
    @TempDir
    lateinit var dir: Path

    private val string = SchemaRef("cringle.std", "String")
    private val int = SchemaRef("cringle.std", "Int")
    private val all = setOf(TetherType.MESSAGE, TetherType.REQUEST_RESPONSE, TetherType.STREAM, TetherType.BYTE_STREAM)

    private val srcDef = BlockDefinition(
        "src",
        emptyList(),
        listOf(
            PortDefinition("out", PortDirection.OUT, all, string),
            PortDefinition("outInt", PortDirection.OUT, setOf(TetherType.MESSAGE), int),
            PortDefinition("unused", PortDirection.OUT, setOf(TetherType.MESSAGE), string),
            PortDefinition("back", PortDirection.IN, setOf(TetherType.MESSAGE), string),
        ),
        emptyList(),
    )
    private val dstDef = BlockDefinition(
        "dst",
        emptyList(),
        listOf(
            PortDefinition("in", PortDirection.IN, all, string),
            PortDefinition("inInt", PortDirection.IN, setOf(TetherType.MESSAGE), int),
        ),
        emptyList(),
    )

    /** A block that keeps its ports and hands every event to [handler]. */
    private class TestBlock(private val handler: suspend (TetherEvent) -> Unit) : Block {
        lateinit var context: BlockContext

        override suspend fun init(context: BlockContext) {
            this.context = context
        }

        override suspend fun onTetherEvent(event: TetherEvent) = handler(event)
    }

    private inner class Setup(
        tethers: List<TetherDef>,
        config: TetherConfig,
        handler: suspend (TetherEvent) -> Unit,
    ) : AutoCloseable {
        val src = TestBlock { }
        val dst = TestBlock(handler)
        private val provider = object : BlockProvider {
            override val definitions = listOf(srcDef, dstDef)
            override fun createBlock(definitionName: String, drivers: DriverSet): Block = if (definitionName == "src") src else dst
        }
        val fabric = FabricRuntime(
            FabricSpec(
                id = "f",
                blueprint = Blueprint("bp", listOf(BlueprintBlock("s", "p/src"), BlueprintBlock("d", "p/dst")), tethers),
                resolver = BlockResolver { ref -> listOf(srcDef, dstDef).firstOrNull { "p/${it.name}" == ref }?.let { ResolvedBlock(provider, it, PluginTrust.TRUSTED) } },
                drivers = DriverFactory { _, _ -> TestDriverSet() },
                paths = FabricPaths(dir.resolve("engine"), "f"),
                tethers = config,
                schemas = config.schemas,
                watchdog = WatchdogConfig(enabled = false),
            ),
        )

        init {
            runBlocking { fabric.start() }
        }

        fun out(port: String): Tether = src.context.ports.port(port)
        fun inPort(port: String): Tether = dst.context.ports.port(port)

        override fun close() = fabric.close()
    }

    private fun tether(type: TetherType, from: String = "out", to: String = "in") =
        TetherDef(type, Endpoint("s", from), Endpoint("d", to))

    private fun config(capacity: Int = 64, timeout: Duration = Duration.ofSeconds(10), observer: TetherObserver? = null, interceptor: TetherInterceptor? = null) =
        TetherConfig(SchemaRegistry(), capacity, timeout, observer, interceptor)

    private fun setup(tethers: List<TetherDef>, config: TetherConfig = config(), handler: suspend (TetherEvent) -> Unit = {}) =
        Setup(tethers, config, handler)

    @Test
    fun messageIsDeliveredToTheReceivingPort(): Unit = runBlocking {
        val received = CompletableDeferred<TetherEvent>()
        setup(listOf(tether(TetherType.MESSAGE))) { received.complete(it) }.use { s ->
            s.out("out").send("hi")
            val event = withTimeout(10.seconds) { received.await() } as TetherEvent.Message
            assertEquals("hi", event.value)
            assertEquals(PortRef("in"), event.port)
        }
    }

    @Test
    fun requestGetsTheResponseOfTheReceiver(): Unit = runBlocking {
        setup(listOf(tether(TetherType.REQUEST_RESPONSE))) { e -> (e as TetherEvent.Request).respond("pong:" + e.value) }.use { s ->
            assertEquals("pong:ping", withTimeout(10.seconds) { s.out("out").request("ping") })
        }
    }

    @Test
    fun streamWorksInBothDirectionsAndCloses(): Unit = runBlocking {
        val opened = CompletableDeferred<TetherEvent.StreamOpened>()
        setup(listOf(tether(TetherType.STREAM))) { opened.complete(it as TetherEvent.StreamOpened) }.use { s ->
            val a = s.out("out").openStream()
            val b = withTimeout(10.seconds) { opened.await() }.stream
            a.send("one")
            a.send("two")
            a.close()
            assertEquals(listOf<Any>("one", "two"), withTimeout(10.seconds) { b.incoming.toList() })
            b.send("reply")
            assertEquals(listOf<Any>("reply"), withTimeout(10.seconds) { a.incoming.take(1).toList() })
            b.close()
        }
    }

    @Test
    fun byteStreamCarriesBytes(): Unit = runBlocking {
        val opened = CompletableDeferred<TetherEvent.ByteStreamOpened>()
        setup(listOf(tether(TetherType.BYTE_STREAM))) { opened.complete(it as TetherEvent.ByteStreamOpened) }.use { s ->
            val a = s.out("out").openByteStream()
            val b = withTimeout(10.seconds) { opened.await() }.stream
            a.write(byteArrayOf(1, 2, 3))
            a.close()
            val chunks = withTimeout(10.seconds) { b.incoming.toList() }
            assertEquals(listOf(1, 2, 3), chunks.flatMap { it.toList() }.map { it.toInt() })
        }
    }

    @Test
    fun slowConsumerSuspendsTheProducerWithoutBlockingItsDispatcher(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val seen = CopyOnWriteArrayList<Any>()
        setup(listOf(tether(TetherType.MESSAGE)), config(capacity = 2)) { e ->
            gate.await()
            seen += (e as TetherEvent.Message).value
        }.use { s ->
            val single = Dispatchers.Default.limitedParallelism(1)
            var sent = 0
            val producer = launch(single) {
                repeat(10) {
                    s.out("out").send("m$it")
                    sent++
                }
            }
            delay(500)
            assertTrue(sent < 10, "producer must be suspended by backpressure, sent=$sent")
            // The producer's dispatcher is still free: other work on it runs while the producer is suspended.
            assertEquals(42, async(single) { 42 }.await())
            gate.complete(Unit)
            withTimeout(10.seconds) { producer.join() }
            withTimeout(10.seconds) { while (seen.size < 10) delay(10) }
            assertEquals((0 until 10).map { "m$it" }, seen.toList())
        }
    }

    /**
     * Second half of the M2 criterion ("Fertig, wenn" in `docs/Architecture.md`): a schema violation at a local tether is
     * detected at runtime (`TetherValidationException`). The schema check applies to `MESSAGE`, `REQUEST_RESPONSE` and
     * `STREAM`; the first half, two blocks exchanging bytes over TCP, is `TcpTetherTest`.
     */
    @Test
    fun schemaViolationsAreReportedWithSchemaAndPath(): Unit = runBlocking {
        setup(listOf(tether(TetherType.MESSAGE), tether(TetherType.MESSAGE, "outInt", "inInt"))).use { s ->
            val e = assertThrows<TetherValidationException> { runBlocking { s.out("out").send(42) } }
            assertTrue(e.message!!.contains("cringle.std/String"), e.message)
            assertTrue(e.message!!.contains("s.out -> d.in"), e.message)
            assertTrue(e.problems.isNotEmpty())
            assertThrows<TetherValidationException> { runBlocking { s.out("outInt").send("not a number") } }
            assertThrows<TetherValidationException> { runBlocking { s.out("out").send(Any()) } }
        }
    }

    @Test
    fun responsesAreValidatedToo(): Unit = runBlocking {
        setup(listOf(tether(TetherType.REQUEST_RESPONSE))) { e -> runCatching { (e as TetherEvent.Request).respond(1) } }.use { s ->
            // the invalid response is rejected in the receiver; the caller times out instead of getting bad data
            assertThrows<TetherTimeoutException> { runBlocking { s.out("out").let { _ -> withTimeout(10.seconds) { s.out("out").request("x") } } } }
        }
    }

    @Test
    fun requestWithoutResponseTimesOut(): Unit = runBlocking {
        setup(listOf(tether(TetherType.REQUEST_RESPONSE)), config(timeout = Duration.ofMillis(200))).use { s ->
            val e = assertThrows<TetherTimeoutException> { runBlocking { s.out("out").request("x") } }
            assertTrue(e.message!!.contains("s.out -> d.in"))
        }
    }

    @Test
    fun operationsOfTheWrongTypeAreRejected(): Unit = runBlocking {
        setup(listOf(tether(TetherType.MESSAGE))).use { s ->
            val e = assertThrows<IllegalStateException> { runBlocking { s.out("out").request("x") } }
            assertTrue(e.message!!.contains("MESSAGE"), e.message)
            assertThrows<IllegalStateException> { runBlocking { s.out("out").openStream() } }
            val input = assertThrows<IllegalStateException> { runBlocking { s.inPort("in").send("x") } }
            assertTrue(input.message!!.contains("input port"), input.message)
            val unconnected = assertThrows<IllegalStateException> { runBlocking { s.out("unused").send("x") } }
            assertTrue(unconnected.message!!.contains("not connected"), unconnected.message)
        }
    }

    @Test
    fun wiringProblemsAreCollectedAndFailFabricCreation() {
        val defs = mapOf("s" to srcDef, "d" to dstDef)
        val blocks = listOf(BlueprintBlock("s", "p/src"), BlueprintBlock("d", "p/dst"))
        val bad = listOf(
            TetherDef(TetherType.STREAM, Endpoint("s", "outInt"), Endpoint("d", "inInt")), // type not supported
            TetherDef(TetherType.MESSAGE, Endpoint("s", "nope"), Endpoint("d", "in")), // unknown port
            TetherDef(TetherType.MESSAGE, Endpoint("d", "in"), Endpoint("s", "back")), // wrong direction
            TetherDef(TetherType.MESSAGE, Endpoint("s", "outInt"), Endpoint("d", "in")), // schema mismatch
        )
        val e = assertThrows<TetherWiringException> { TetherNetwork.create(Blueprint("bp", blocks, bad), defs, config()) }
        assertTrue(e.message!!.contains("does not support STREAM"), e.message)
        assertTrue(e.message!!.contains("no port 'nope'"), e.message)
        assertTrue(e.message!!.contains("expected OUT"), e.message)
        assertTrue(e.message!!.contains("not assignable"), e.message)
        assertThrows<FabricException> { setup(bad).close() }
    }

    @Test
    fun remoteTetherNeedsTheRemoteDriverAndTheMessageType() {
        val defs = mapOf("s" to srcDef, "d" to dstDef)
        val blocks = listOf(BlueprintBlock("s", "p/src"), BlueprintBlock("d", "p/dst"))
        val remote = RemoteEndpoint("10.0.0.7:7443", "ab".repeat(32), "shop", "sink", "in")
        val message = listOf(TetherDef(TetherType.MESSAGE, Endpoint("s", "out"), null, remote = remote))
        val noDriver = assertThrows<TetherWiringException> { TetherNetwork.create(Blueprint("bp", blocks, message), defs, config()) }
        assertTrue(noDriver.message!!.contains("this engine cannot run tethers to other engines"), noDriver.message)
        val request = listOf(TetherDef(TetherType.REQUEST_RESPONSE, Endpoint("s", "out"), null, remote = remote))
        val driver = object : RemoteTetherPorts {
            override fun register(receivers: List<RemoteReceiver>): AutoCloseable = AutoCloseable { }
            override suspend fun connect(sender: RemoteSender): RemoteCall = throw AssertionError("not connected in this test")
        }
        val config = TetherConfig(remote = driver)
        val unsupported = assertThrows<TetherWiringException> { TetherNetwork.create(Blueprint("bp", blocks, request), defs, config) }
        assertTrue(unsupported.message!!.contains("a remote REQUEST_RESPONSE tether is not supported across engines yet"), unsupported.message)
        // the local end of a remote tether is checked like a local one: an IN port cannot send
        val wrongDirection = listOf(TetherDef(TetherType.MESSAGE, Endpoint("d", "in"), null, remote = remote))
        val direction = assertThrows<TetherWiringException> { TetherNetwork.create(Blueprint("bp", blocks, wrongDirection), defs, config) }
        assertTrue(direction.message!!.contains("expected OUT"), direction.message)
    }

    @Test
    fun endpointCanOnlyBeConnectedOnce() {
        val defs = mapOf("s" to srcDef, "d" to dstDef)
        val blocks = listOf(BlueprintBlock("s", "p/src"), BlueprintBlock("d", "p/dst"))
        val twice = listOf(tether(TetherType.MESSAGE), tether(TetherType.MESSAGE))
        val e = assertThrows<TetherWiringException> { TetherNetwork.create(Blueprint("bp", blocks, twice), defs, config()) }
        assertTrue(e.message!!.contains("already connected"), e.message)
    }

    @Test
    fun observerAndInterceptorHooksSeeTheTraffic(): Unit = runBlocking {
        val observed = CopyOnWriteArrayList<String>()
        val held = CompletableDeferred<Unit>()
        val received = CompletableDeferred<Unit>()
        val cfg = config(
            observer = TetherObserver { t, kind, payload -> observed += "${t.id}:$kind:$payload" },
            interceptor = TetherInterceptor { _, _, _ -> held.await() },
        )
        setup(listOf(tether(TetherType.MESSAGE)), cfg) { received.complete(Unit) }.use { s ->
            s.out("out").send("x")
            delay(300)
            assertTrue(!received.isCompleted, "interceptor must hold the message back")
            held.complete(Unit)
            withTimeout(10.seconds) { received.await() }
            assertEquals(listOf("s.out -> d.in:MESSAGE:x"), observed.toList())
        }
    }
}
