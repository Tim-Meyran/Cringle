// SPDX-License-Identifier: Apache-2.0

package cringle.engine.drivers

import com.fazecast.jSerialComm.SerialPort
import cringle.contract.BuiltinDriverTypes
import cringle.contract.DriverType
import cringle.contract.Parity
import cringle.contract.SerialConnection
import cringle.contract.SerialDriver
import cringle.contract.SerialSettings
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Thrown by [SerialDriver.open] when the device is already used; the message says by whom. */
public class DeviceInUseException(public val device: String, message: String) : RuntimeException(message)

/** The engine-wide registry of serial devices in use: one owner per device, conflicts are detected before opening. */
public class DeviceRegistry {
    private val owners = HashMap<String, String>()

    /** Claims [device] for [owner] or throws [DeviceInUseException] naming the current owner. */
    public fun claim(device: String, owner: String) {
        synchronized(owners) {
            val current = owners[device]
            if (current != null) throw DeviceInUseException(device, "device '$device' is already used by $current; $owner cannot use it")
            owners[device] = owner
        }
    }

    /** Frees [device]. */
    public fun release(device: String) {
        synchronized(owners) { owners.remove(device) }
    }

    /** The owner of [device], or `null` if it is free. */
    public fun owner(device: String): String? = synchronized(owners) { owners[device] }

    /** All devices in use with their owners. */
    public fun snapshot(): Map<String, String> = synchronized(owners) { HashMap(owners) }
}

/** One open serial port as seen by [SerialService]; the seam that keeps jSerialComm out of the tests. */
internal interface SerialPortHandle {
    /** The bytes arriving from the device, in chunks. The flow completes when the port is closed. */
    val incoming: Flow<ByteArray>

    /** Writes [bytes] to the device, suspending while the port cannot take them. */
    suspend fun write(bytes: ByteArray)

    /** Closes the port and stops the reader. */
    fun close()
}

/** Opens a serial device; the production implementation wraps jSerialComm, tests use a fake. */
internal interface SerialPortFactory {
    /** Opens [device] with [settings] and returns the handle. */
    suspend fun open(device: String, settings: SerialSettings): SerialPortHandle
}

/** The engine-wide serial service: the [DeviceRegistry] and the open ports. */
public class SerialService internal constructor(
    internal val factory: SerialPortFactory,
) : AutoCloseable {
    /** The registry of devices in use. */
    public val devices: DeviceRegistry = DeviceRegistry()
    private val handles = CopyOnWriteArrayList<SerialPortHandle>()

    /** Creates a service backed by jSerialComm that reports warnings to the standard error stream. */
    public constructor() : this(JSerialCommPortFactory { System.err.println("WARNING: $it") })

    /** A driver whose devices belong to block [block] of [fabric]; close it when the block goes away. */
    public fun driverFor(fabric: String, block: String): BlockSerial = BlockSerial(this, "block '$block' of fabric '$fabric'")

    internal suspend fun open(device: String, settings: SerialSettings): SerialPortHandle = factory.open(device, settings).also { handles += it }

    /** Closes every open port at once and returns. */
    override fun close() {
        handles.forEach { runCatching { it.close() } }
        handles.clear()
    }
}

/** The serial driver of one block. Closing it closes every connection the block opened and frees its devices. */
public class BlockSerial internal constructor(
    private val service: SerialService,
    private val owner: String,
) : SerialDriver, AutoCloseable {
    override val type: DriverType = BuiltinDriverTypes.SERIAL
    private val connections = CopyOnWriteArrayList<Connection>()

    override suspend fun open(device: String, settings: SerialSettings): SerialConnection {
        service.devices.claim(device, owner)
        val handle = try {
            service.open(device, settings)
        } catch (e: Throwable) {
            service.devices.release(device)
            throw e
        }
        return Connection(device, handle).also { connections += it }
    }

    /** Closes every connection at once and returns; the devices are freed by the connections. */
    override fun close() {
        connections.forEach { it.closeNow() }
    }

    override suspend fun awaitClosed(timeout: Duration): Boolean {
        // one timeout for all connections, not one per connection
        val pending = connections.map { it.closed }.toTypedArray()
        if (pending.isEmpty()) return true
        return withTimeoutOrNull(timeout.toMillis()) {
            pending.forEach { it.await() }
            true
        } ?: false
    }

    private inner class Connection(override val device: String, private val handle: SerialPortHandle) : SerialConnection {
        /** Completed when the connection has let go of the port and freed the device. */
        val closed = CompletableDeferred<Unit>()
        override val incoming: Flow<ByteArray> = handle.incoming

        override suspend fun write(bytes: ByteArray) = handle.write(bytes)

        override suspend fun close() = closeNow()

        /** Closes the port without waiting and frees the device. */
        fun closeNow() {
            runCatching { handle.close() }
            connections.remove(this)
            service.devices.release(device)
            closed.complete(Unit)
        }
    }
}

/** Opens serial devices through jSerialComm. */
internal class JSerialCommPortFactory(private val onWarning: (String) -> Unit) : SerialPortFactory {
    override suspend fun open(device: String, settings: SerialSettings): SerialPortHandle = withContext(Dispatchers.IO) {
        val port = SerialPort.getCommPort(device)
        port.setComPortParameters(
            settings.baudRate,
            settings.dataBits,
            if (settings.stopBits == 2) SerialPort.TWO_STOP_BITS else SerialPort.ONE_STOP_BIT,
            when (settings.parity) {
                Parity.NONE -> SerialPort.NO_PARITY
                Parity.EVEN -> SerialPort.EVEN_PARITY
                Parity.ODD -> SerialPort.ODD_PARITY
            },
        )
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0)
        if (!port.openPort()) throw IOException("cannot open serial device '$device'")
        JSerialCommHandle(device, port, onWarning)
    }
}

/** One open jSerialComm port: a reader coroutine feeds [incoming], writes go straight to the output stream. */
private class JSerialCommHandle(
    private val device: String,
    private val port: SerialPort,
    private val onWarning: (String) -> Unit,
) : SerialPortHandle {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chunks = Channel<ByteArray>(16)
    override val incoming: Flow<ByteArray> = chunks.receiveAsFlow()

    init {
        scope.launch {
            try {
                val input = port.getInputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    chunks.send(buffer.copyOf(n))
                }
            } catch (e: IOException) {
                onWarning("serial device '$device': ${e.message}")
            } finally {
                chunks.close()
            }
        }
    }

    override suspend fun write(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            port.getOutputStream().apply {
                write(bytes)
                flush()
            }
        }
    }

    override fun close() {
        runCatching { port.closePort() }
        scope.cancel()
        chunks.close()
    }
}
