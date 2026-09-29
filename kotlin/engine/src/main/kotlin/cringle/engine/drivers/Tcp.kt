// SPDX-License-Identifier: Apache-2.0

package cringle.engine.drivers

import cringle.contract.BuiltinDriverTypes
import cringle.contract.DriverType
import cringle.contract.PortInUseException
import cringle.contract.TcpConnection
import cringle.contract.TcpDriver
import cringle.contract.TcpListener
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The engine-wide registry of TCP ports in use: one owner per port, conflicts are detected before binding. */
public class PortRegistry {
    private val owners = HashMap<Int, String>()

    /** Claims [port] for [owner] or throws [PortInUseException] naming the current owner. */
    public fun claim(port: Int, owner: String) {
        synchronized(owners) {
            val current = owners[port]
            if (current != null) throw PortInUseException(port, "port $port is already used by $current; $owner cannot use it")
            owners[port] = owner
        }
    }

    /** Frees [port]. */
    public fun release(port: Int) {
        synchronized(owners) { owners.remove(port) }
    }

    /** The owner of [port], or `null` if it is free. */
    public fun owner(port: Int): String? = synchronized(owners) { owners[port] }

    /** All ports in use with their owners. */
    public fun snapshot(): Map<Int, String> = synchronized(owners) { HashMap(owners) }
}

/** The engine-wide TCP service: the [PortRegistry] and the threads that serve sockets. */
public class TcpService : AutoCloseable {
    /** The registry of ports in use. */
    public val ports: PortRegistry = PortRegistry()
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A driver whose ports and connections belong to block [block] of [fabric]; close it when the block goes away. */
    public fun driverFor(fabric: String, block: String): BlockTcp = BlockTcp(this, "block '$block' of fabric '$fabric'")

    override fun close() {
        scope.cancel()
    }
}

/** The TCP driver of one block. Closing it closes every listener and connection the block opened and frees its ports. */
public class BlockTcp internal constructor(private val service: TcpService, private val owner: String) : TcpDriver, AutoCloseable {
    override val type: DriverType = BuiltinDriverTypes.TCP
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val connections = CopyOnWriteArrayList<Connection>()

    override suspend fun listen(port: Int, anyInterface: Boolean): TcpListener {
        require(port in 0..65535) { "port must be between 0 and 65535: $port" }
        if (port != 0) service.ports.claim(port, owner)
        val server = try {
            withContext(Dispatchers.IO) {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(if (anyInterface) InetAddress.getByName("0.0.0.0") else InetAddress.getLoopbackAddress(), port))
                }
            }
        } catch (e: IOException) {
            if (port != 0) service.ports.release(port)
            if (e is BindException) throw PortInUseException(port, "port $port is in use by another process: ${e.message}")
            throw e
        }
        val bound = server.localPort
        if (port == 0) service.ports.claim(bound, owner)
        return Listener(server, bound).also {
            listeners += it
            it.start()
        }
    }

    override suspend fun connect(host: String, port: Int): TcpConnection {
        val socket = withContext(Dispatchers.IO) { Socket().also { it.connect(InetSocketAddress(host, port), 10_000) } }
        return Connection(socket).also {
            connections += it
            it.start()
        }
    }

    override fun close() {
        listeners.forEach { it.closeNow() }
        connections.forEach { it.closeNow() }
    }

    private inner class Listener(private val server: ServerSocket, override val port: Int) : TcpListener {
        private val accepted = Channel<TcpConnection>(Channel.UNLIMITED)
        override val connections: Flow<TcpConnection> = accepted.receiveAsFlow()

        private val stopped = java.util.concurrent.CountDownLatch(1)

        fun start() {
            service.scope.launch {
                try {
                    while (!server.isClosed) {
                        val socket = server.accept()
                        accepted.trySend(Connection(socket).also { c ->
                            this@BlockTcp.connections += c
                            c.start()
                        })
                    }
                } catch (_: IOException) {
                    // closed
                } finally {
                    // the port is free only once the accepting thread has let go of the socket
                    runCatching { server.close() }
                    service.ports.release(port)
                    accepted.close()
                    listeners.remove(this@Listener)
                    stopped.countDown()
                }
            }
        }

        /** Closes the socket and waits (briefly) until the port is really free. */
        fun closeNow() {
            runCatching { server.close() }
            stopped.await(2, java.util.concurrent.TimeUnit.SECONDS)
        }

        override suspend fun close() = withContext(Dispatchers.IO) { closeNow() }
    }

    private inner class Connection(private val socket: Socket) : TcpConnection {
        private val chunks = Channel<ByteArray>(16)
        override val remoteAddress: String = "${socket.inetAddress.hostAddress}:${socket.port}"
        override val incoming: Flow<ByteArray> = chunks.receiveAsFlow()

        fun start() {
            service.scope.launch {
                try {
                    val input = socket.getInputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        chunks.send(buffer.copyOf(n))
                    }
                } catch (_: IOException) {
                    // closed or reset
                } finally {
                    chunks.close()
                }
            }
        }

        override suspend fun write(bytes: ByteArray) {
            withContext(Dispatchers.IO) {
                socket.getOutputStream().apply {
                    write(bytes)
                    flush()
                }
            }
        }

        fun closeNow() {
            runCatching { socket.close() }
            chunks.close()
            connections.remove(this)
        }

        override suspend fun close() = closeNow()
    }
}
