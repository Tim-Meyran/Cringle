// SPDX-License-Identifier: Apache-2.0

package cringle.contract

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KClass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/** A sample block and provider that exercise the whole contract with fake drivers and a fake tether. */
class SampleBlockTest {
    private class FakeDwh : DwhDriver {
        override val type = DriverType("dwh", IsolationLevel.SHARED)
        val entries = mutableListOf<DwhEntry>()
        var started = false

        override suspend fun start() {
            started = true
        }

        override suspend fun write(entry: DwhEntry) {
            entries += entry
        }
    }

    private class FakeTether(override val type: TetherType) : Tether {
        val sent = mutableListOf<Any>()

        override suspend fun send(message: Any) {
            check(type == TetherType.MESSAGE) { "send is not valid for $type" }
            sent += message
        }

        override suspend fun request(request: Any): Any = throw IllegalStateException("request is not valid for $type")

        override suspend fun openStream(): TetherStream = throw IllegalStateException("openStream is not valid for $type")

        override suspend fun openByteStream(): TetherByteStream =
            throw IllegalStateException("openByteStream is not valid for $type")
    }

    private class FakePorts(
        private val plain: Map<String, Tether> = emptyMap(),
        private val varArg: Map<String, List<Tether>> = emptyMap(),
    ) : BlockPorts {
        override fun port(name: String): Tether = plain[name] ?: throw IllegalArgumentException("No plain port '$name'")

        override fun varArgPort(name: String): List<Tether> =
            varArg[name] ?: throw IllegalArgumentException("No VarArg port '$name'")
    }

    private class FakeContext(override val ports: BlockPorts, override val config: Map<String, Any?> = emptyMap()) : BlockContext {
        override val blockId = BlockId("uppercase-1")
    }

    private class FakeDriverSet(vararg drivers: Driver) : DriverSet {
        private val byInterface: Map<KClass<*>, Driver> =
            drivers.flatMap { d -> listOf(DwhDriver::class, Driver::class).filter { it.isInstance(d) }.map { it to d } }.toMap()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Driver> get(type: KClass<T>): T =
            byInterface[type] as T? ?: throw IllegalArgumentException("Driver ${type.simpleName} was not declared")
    }

    /** Answers requests and forwards messages in upper case; records its lifecycle. */
    private class UppercaseBlock(private val dwh: DwhDriver, private val log: MutableList<String>) : Block {
        private lateinit var output: Tether

        override suspend fun init(context: BlockContext) {
            log += "init ${context.blockId}"
            output = context.ports.port("output")
        }

        override suspend fun start() {
            log += "start"
        }

        override suspend fun stop() {
            log += "stop"
        }

        override suspend fun destroy() {
            log += "destroy"
        }

        override suspend fun onTetherEvent(event: TetherEvent) {
            when (event) {
                is TetherEvent.Message -> {
                    log += "message ${event.port.name}"
                    dwh.write(DwhEntry("message", event.value))
                    output.send((event.value as String).uppercase())
                }
                is TetherEvent.Request -> {
                    log += "request ${event.port.name}"
                    dwh.write(DwhEntry("request", event.value))
                    event.respond((event.value as String).uppercase())
                }
                is TetherEvent.StreamOpened, is TetherEvent.ByteStreamOpened -> log += "unsupported ${event.port.name}"
            }
        }
    }

    /** Sends every message to all tethers of the VarArg port `outputs`. */
    private class FanOutBlock : Block {
        private lateinit var outputs: List<Tether>

        override suspend fun init(context: BlockContext) {
            outputs = context.ports.varArgPort("outputs")
        }

        override suspend fun onTetherEvent(event: TetherEvent) {
            if (event is TetherEvent.Message) outputs.forEach { it.send(event.value) }
        }
    }

    private class SampleProvider(private val log: MutableList<String>) : BlockProvider {
        private val text = SchemaRef("acme.text", "Text")

        override val definitions: List<BlockDefinition> = listOf(
            BlockDefinition(
                name = "uppercase",
                schemas = listOf(text),
                ports = listOf(
                    PortDefinition("input", PortDirection.IN, setOf(TetherType.MESSAGE, TetherType.REQUEST_RESPONSE), text),
                    PortDefinition("output", PortDirection.OUT, setOf(TetherType.MESSAGE), text),
                ),
                requiredDrivers = listOf("dwh"),
            ),
            BlockDefinition(
                name = "fan-out",
                schemas = listOf(text),
                ports = listOf(
                    PortDefinition("input", PortDirection.IN, setOf(TetherType.MESSAGE), text),
                    PortDefinition("outputs", PortDirection.OUT, setOf(TetherType.MESSAGE), text, varArg = true),
                ),
                requiredDrivers = emptyList(),
            ),
        )

        override fun createBlock(definitionName: String, drivers: DriverSet): Block = when (definitionName) {
            "uppercase" -> UppercaseBlock(drivers[DwhDriver::class], log)
            "fan-out" -> FanOutBlock()
            else -> throw IllegalArgumentException("Unknown block '$definitionName'")
        }
    }

    private suspend inline fun <reified T : Throwable> assertFailsSuspending(block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        throw AssertionError("Expected ${T::class.simpleName} to be thrown")
    }

    @Test
    fun blockRunsThroughItsWholeLifecycleAndHandlesTetherEvents() = runTest {
        val log = mutableListOf<String>()
        val dwh = FakeDwh()
        val output = FakeTether(TetherType.MESSAGE)
        val block = SampleProvider(log).createBlock("uppercase", FakeDriverSet(dwh))
        val responses = mutableListOf<Any>()

        block.init(FakeContext(FakePorts(plain = mapOf("output" to output))))
        block.start()
        block.onTetherEvent(TetherEvent.Message(PortRef("input"), "hello"))
        block.onTetherEvent(TetherEvent.Request(PortRef("input"), "abc") { responses += it })
        block.stop()
        block.destroy()

        assertEquals(
            listOf("init uppercase-1", "start", "message input", "request input", "stop", "destroy"),
            log,
        )
        assertEquals(listOf<Any>("HELLO"), output.sent)
        assertEquals(listOf<Any>("ABC"), responses)
        assertEquals(listOf("message", "request"), dwh.entries.map { it.key })
        assertEquals(listOf<Any>("hello", "abc"), dwh.entries.map { it.value })
    }

    @Test
    fun blockGetsItsDriversOnlyThroughTheDriverSet() {
        val provider = SampleProvider(mutableListOf())
        assertEquals(listOf("dwh"), provider.definitions.first { it.name == "uppercase" }.requiredDrivers)

        val error = assertThrows<IllegalArgumentException> { provider.createBlock("uppercase", FakeDriverSet()) }
        assertTrue("DwhDriver" in error.message.orEmpty())
    }

    @Test
    fun providerRejectsUnknownDefinitionNames() {
        assertThrows<IllegalArgumentException> { SampleProvider(mutableListOf()).createBlock("nope", FakeDriverSet()) }
    }

    @Test
    fun varArgPortIsAFixedListOfTethers() = runTest {
        val outputs = List(3) { FakeTether(TetherType.MESSAGE) }
        val block = SampleProvider(mutableListOf()).createBlock("fan-out", FakeDriverSet())

        block.init(FakeContext(FakePorts(varArg = mapOf("outputs" to outputs))))
        block.onTetherEvent(TetherEvent.Message(PortRef("input"), "x"))

        assertTrue(outputs.all { it.sent == listOf<Any>("x") })
    }

    @Test
    fun driverLifecycleAndWrongOperationsOnATether() = runTest {
        val dwh = FakeDwh()
        dwh.start()
        assertTrue(dwh.started)

        val stream = FakeTether(TetherType.STREAM)
        assertFailsSuspending<IllegalStateException> { stream.send("not valid for a stream tether") }
        assertFailsSuspending<IllegalStateException> { stream.request("x") }
    }

    @Test
    fun streamEventsCarryTheirStreams() {
        val stream = object : TetherStream {
            override val incoming = emptyFlow<Any>()

            override suspend fun send(item: Any) {}

            override suspend fun close() {}
        }
        val event = TetherEvent.StreamOpened(PortRef("data", 2), stream)
        assertEquals(PortRef("data", 2), event.port)
        assertTrue(event.stream === stream)
    }
}
