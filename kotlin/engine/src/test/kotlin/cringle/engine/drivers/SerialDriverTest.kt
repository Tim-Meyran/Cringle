// SPDX-License-Identifier: Apache-2.0

package cringle.engine.drivers

import cringle.contract.Parity
import cringle.contract.SerialSettings
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
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

class SerialDriverTest {
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

    @Test
    fun openClaimsTheDeviceAndSettingsReachTheFactory(): Unit = runBlocking {
        val factory = FakeFactory()
        val service = SerialService(factory)
        val driver = service.driverFor("f", "b")
        driver.open("/dev/ttyUSB0", SerialSettings(115200, 7, Parity.EVEN, 2))
        assertEquals("/dev/ttyUSB0" to SerialSettings(115200, 7, Parity.EVEN, 2), factory.opened.single())
        assertEquals("block 'b' of fabric 'f'", service.devices.owner("/dev/ttyUSB0"))
    }

    @Test
    fun secondOpenOfTheSameDeviceFailsNamingTheOwner(): Unit = runBlocking {
        val factory = FakeFactory()
        val service = SerialService(factory)
        val driver = service.driverFor("f", "b")
        driver.open("/dev/ttyUSB0", SerialSettings())
        val e = assertThrows<DeviceInUseException> { runBlocking { driver.open("/dev/ttyUSB0", SerialSettings()) } }
        assertTrue(e.message!!.contains("already used by block 'b' of fabric 'f'"), e.message)
    }

    @Test
    fun missingDeviceFailsAndTheDeviceIsFreeAgain(): Unit = runBlocking {
        val factory = FakeFactory()
        val service = SerialService(factory)
        val driver = service.driverFor("f", "b")
        factory.failFor = "/dev/ttyUSB0"
        assertThrows<IOException> { runBlocking { driver.open("/dev/ttyUSB0", SerialSettings()) } }
        assertNull(service.devices.owner("/dev/ttyUSB0"))
        factory.failFor = null
        driver.open("/dev/ttyUSB0", SerialSettings())
        assertEquals("block 'b' of fabric 'f'", service.devices.owner("/dev/ttyUSB0"))
    }

    @Test
    fun closeReleasesTheDeviceAndAwaitClosedReturnsTrue(): Unit = runBlocking {
        val factory = FakeFactory()
        val service = SerialService(factory)
        val driver = service.driverFor("f", "b")
        driver.open("/dev/ttyUSB0", SerialSettings())
        driver.close()
        assertNull(service.devices.owner("/dev/ttyUSB0"))
        assertTrue(driver.awaitClosed(Duration.ofSeconds(1)))
    }

    @Test
    fun bytesFlowBothWaysThroughTheConnection(): Unit = runBlocking {
        val factory = FakeFactory()
        val service = SerialService(factory)
        val driver = service.driverFor("f", "b")
        val connection = driver.open("/dev/ttyUSB0", SerialSettings())
        connection.write("ping".toByteArray())
        assertEquals("ping", String(withTimeout(5.seconds) { factory.handles.single().written.receive() }))
        factory.handles.single().emit("pong".toByteArray())
        assertEquals("pong", String(withTimeout(5.seconds) { connection.incoming.first() }))
    }
}
