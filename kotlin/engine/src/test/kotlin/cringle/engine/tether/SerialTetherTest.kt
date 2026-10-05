// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.contract.BlockDefinition
import cringle.contract.BlockId
import cringle.contract.Parity
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.SerialSettings
import cringle.contract.TetherEvent
import cringle.contract.TetherType
import cringle.engine.drivers.SerialPortFactory
import cringle.engine.drivers.SerialPortHandle
import cringle.engine.drivers.SerialService
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.DeliveryPolicy
import cringle.packaging.Endpoint
import cringle.packaging.SerialTetherConfig
import cringle.packaging.TetherDef
import cringle.schema.SchemaRegistry
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SerialTetherTest {
    private class FakeHandle : SerialPortHandle {
        val written = Channel<ByteArray>(Channel.UNLIMITED)
        private val incomingChannel = Channel<ByteArray>(Channel.UNLIMITED)
        override val incoming: Flow<ByteArray> = incomingChannel.receiveAsFlow()

        override suspend fun write(bytes: ByteArray) {
            written.send(bytes)
        }

        suspend fun emit(bytes: ByteArray) {
            incomingChannel.send(bytes)
        }

        override fun close() {
            incomingChannel.close()
        }
    }

    private class FakeFactory : SerialPortFactory {
        val opened = CopyOnWriteArrayList<Pair<String, SerialSettings>>()
        val handles = CopyOnWriteArrayList<FakeHandle>()
        var failFor: String? = null

        override suspend fun open(device: String, settings: SerialSettings): SerialPortHandle {
            if (device == failFor) throw IOException("no such device")
            opened += device to settings
            return FakeHandle().also { handles += it }
        }
    }

    private val bytes = SchemaRef("cringle.std", "Bytes")
    private val types = setOf(TetherType.SERIAL)
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

    private fun serial(
        device: String,
        baudRate: Int = 9600,
        dataBits: Int = 8,
        parity: Parity = Parity.NONE,
        stopBits: Int = 1,
        from: String = "out",
        to: String = "in",
    ) = TetherDef(
        TetherType.SERIAL,
        Endpoint("s", from),
        Endpoint("d", to),
        DeliveryPolicy.DROP,
        serial = SerialTetherConfig(device, baudRate, dataBits, parity, stopBits),
    )

    private fun network(factory: FakeFactory, tethers: List<TetherDef>): Pair<TetherNetwork, SerialService> {
        val service = SerialService(factory)
        val blueprint = Blueprint("bp", listOf(BlueprintBlock("s", "p/src"), BlueprintBlock("d", "p/dst")), tethers)
        val n = TetherNetwork.create(
            blueprint,
            mapOf("s" to src, "d" to dst),
            TetherConfig(SchemaRegistry(), serial = { block -> service.driverFor("f", block) }),
        )
        return n to service
    }

    @Test
    fun bytesFlowBothWaysAndSettingsReachTheFactory(): Unit = runBlocking {
        val factory = FakeFactory()
        val (n, _) = network(factory, listOf(serial("/dev/ttyUSB0", 115200, 7, Parity.EVEN, 2)))
        val opened = CompletableDeferred<TetherEvent.ByteStreamOpened>()
        n.open { _, e -> if (e is TetherEvent.ByteStreamOpened) opened.complete(e) }
        assertEquals("/dev/ttyUSB0" to SerialSettings(115200, 7, Parity.EVEN, 2), factory.opened.single())
        val inbound = withTimeout(5.seconds) { opened.await() }.stream
        val out = n.tether(BlockId("s"), src.ports.first { it.name == "out" }, null).openByteStream()
        out.write("ping".toByteArray())
        assertEquals("ping", String(withTimeout(5.seconds) { factory.handles.single().written.receive() }))
        factory.handles.single().emit("pong".toByteArray())
        assertEquals("pong", String(withTimeout(5.seconds) { inbound.incoming.first() }))
        n.close()
    }

    @Test
    fun missingDeviceFailsTheStartWithANamedMessageAndTheStartCanRepeat(): Unit = runBlocking {
        val factory = FakeFactory()
        val (n, service) = network(factory, listOf(serial("/dev/ttyUSB0")))
        factory.failFor = "/dev/ttyUSB0"
        val e = assertThrows<TetherWiringException> { runBlocking { n.open { _, _ -> } } }
        assertTrue(e.message!!.contains("/dev/ttyUSB0"), e.message)
        assertTrue(e.message!!.contains("s.out -> d.in"), e.message)
        assertNull(service.devices.owner("/dev/ttyUSB0"))
        factory.failFor = null
        n.open { _, _ -> }
        assertEquals("block 'd' of fabric 'f'", service.devices.owner("/dev/ttyUSB0"))
        n.close()
    }

    @Test
    fun sameDeviceInTwoTethersFailsTheStartNamingTheOwner(): Unit = runBlocking {
        val factory = FakeFactory()
        val (n, _) = network(factory, listOf(serial("/dev/ttyUSB0"), serial("/dev/ttyUSB0", from = "out2", to = "in2")))
        val e = assertThrows<TetherWiringException> { runBlocking { n.open { _, _ -> } } }
        assertTrue(e.message!!.contains("already used by block 'd' of fabric 'f'"), e.message)
    }

    @Test
    fun stopClosesTheDevice(): Unit = runBlocking {
        val factory = FakeFactory()
        val (n, service) = network(factory, listOf(serial("/dev/ttyUSB0")))
        n.open { _, _ -> }
        assertEquals("block 'd' of fabric 'f'", service.devices.owner("/dev/ttyUSB0"))
        n.close()
        assertNull(service.devices.owner("/dev/ttyUSB0"))
        assertTrue(n.awaitClosed(Duration.ofSeconds(1)))
    }
}
