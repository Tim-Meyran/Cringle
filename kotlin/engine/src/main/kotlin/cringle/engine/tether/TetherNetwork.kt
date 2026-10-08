// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.contract.BlockId
import cringle.contract.BlockDefinition
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.PortInUseException
import cringle.contract.PortRef
import cringle.contract.SerialConnection
import cringle.contract.SerialDriver
import cringle.contract.SerialSettings
import cringle.contract.TcpConnection
import cringle.contract.TcpDriver
import cringle.contract.Tether
import cringle.contract.TetherByteStream
import cringle.contract.TetherEvent
import cringle.contract.TetherStream
import cringle.contract.TetherType
import cringle.engine.drivers.DeviceInUseException
import cringle.engine.fabric.FabricException
import cringle.engine.fabric.PortWiring
import cringle.packaging.Backoff
import cringle.packaging.Blueprint
import cringle.packaging.DeliveryPolicy
import cringle.packaging.Endpoint
import cringle.packaging.RemoteEndpoint
import cringle.packaging.RetryConfig
import cringle.packaging.SerialTetherConfig
import cringle.schema.SchemaValidator
import cringle.wire.Bytes
import cringle.wire.Message
import cringle.wire.Request
import cringle.wire.Response
import cringle.wire.StreamClose
import cringle.wire.StreamItem
import cringle.wire.StreamOpen
import cringle.wire.TetherMode
import cringle.wire.WireFrame
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** Hands an event to a block; implemented by the fabric runtime. */
public fun interface TetherDeliverer {
    /** Delivers [event] to block [blockId]. Throws if the block cannot take it. */
    public suspend fun deliver(blockId: String, event: TetherEvent)
}

/**
 * The in-process tethers of one fabric (Architecture chapter 10): the wiring of a blueprint with every tether's type
 * fixed, asynchronous delivery through bounded buffers, and schema validation of every value.
 *
 * Everything is built on coroutines and channels. A tether has a buffer of [TetherConfig.bufferCapacity] entries; a
 * sender suspends (never blocks a thread) while it is full. Request/response is a message plus a response that
 * completes a deferred value. Streams are two bounded channels, one per direction. Messages of one tether arrive in
 * the order they were sent. A message the receiver cannot take (block not running) is dropped and reported to the
 * fabric logger via [onDeliveryFailure]; a request fails with [TetherDeliveryException] instead.
 *
 * The network is a [PortWiring]. It carries no traffic until [open] and stops at [close]; both can repeat, so the
 * fabric can be stopped and started again with the same tether handles.
 */
public class TetherNetwork private constructor(
    private val config: TetherConfig,
    private val connections: Map<String, Connection>,
    private val onDeliveryFailure: (TetherInfo, Throwable) -> Unit,
) : PortWiring, AutoCloseable {
    private val validator = config.schemas?.let { SchemaValidator(it) }

    @Volatile private var deliverer: TetherDeliverer? = null

    /** Runs the short jobs that must outlive [close]: telling the senders that the fabric stops. */
    private val notifier = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** What the receiving ends of this network registered at the remote driver; see [release]. */
    @Volatile private var registration: AutoCloseable? = null

    @Volatile private var scope: CoroutineScope? = null
    private val streamChannels = ConcurrentHashMap.newKeySet<Channel<*>>()
    private val tcpDrivers = CopyOnWriteArrayList<TcpDriver>()
    private val serialDrivers = CopyOnWriteArrayList<SerialDriver>()

    /** The TCP drivers the last [close] closed; [awaitClosed] waits for them. */
    @Volatile private var closingDrivers: List<TcpDriver> = emptyList()

    /** The serial drivers the last [close] closed; [awaitClosed] waits for them. */
    @Volatile private var closingSerialDrivers: List<SerialDriver> = emptyList()
    private val pendingRequests = ConcurrentHashMap.newKeySet<CompletableDeferred<Any>>()

    /** The reason the queues and streams were closed last, or `null` while the network is open. */
    @Volatile private var stopping: TetherDeliveryException? = null

    /** Completed by [close]: a sender that waits for room in a buffer waits for this as well. */
    @Volatile private var stopped: CompletableDeferred<Unit> = CompletableDeferred()

    /** The number of stream channels the network still has to close; read by the tests. */
    internal val openStreamChannelCount: Int get() = streamChannels.size

    /** All tethers of the blueprint. */
    public val tethers: List<TetherInfo> = connections.values.map { it.info }.distinctBy { it.id }

    /** Starts the delivery of messages to [deliverer]. */
    public suspend fun open(deliverer: TetherDeliverer) {
        close()
        stopping = null
        stopped = CompletableDeferred()
        this.deliverer = deliverer
        val s = CoroutineScope(SupervisorJob() + config.dispatcher)
        scope = s
        for (c in connections.values.distinctBy { it.info.id }) {
            if (c.info.type == TetherType.TCP || c.info.type == TetherType.SERIAL) continue
            c.queue = Channel(c.bufferCapacity)
            s.launch { pump(c) }
        }
        try {
            for (c in connections.values.distinctBy { it.info.id }) {
                when (c.info.type) {
                    TetherType.TCP -> openTcp(c, s, deliverer)
                    TetherType.SERIAL -> openSerial(c, deliverer)
                    else -> if (c.remoteSends) openRemote(c)
                }
            }
        } catch (e: TetherWiringException) {
            close()
            throw e
        } catch (e: PortInUseException) {
            close()
            throw TetherWiringException("tether cannot start: ${e.message}")
        } catch (e: java.io.IOException) {
            close()
            throw TetherWiringException("tether cannot start: ${e.message}")
        }
    }

    // ---- tethers that end on another engine (spec/tether.md, "Tethers between engines") ----

    /** Opens the call of a tether that sends to another engine; the call is accepted by the other engine before this returns. */
    private suspend fun openRemote(c: Connection) {
        val ports = config.remote ?: throw TetherWiringException("tether ${c.info.id}: this engine cannot run tethers to other engines")
        val remote = checkNotNull(c.remote)
        c.lost = null
        c.remoteReason = null
        c.lostSignal = CompletableDeferred()
        c.call = ports.connect(RemoteSender(c.info.id, remote, c.fromPort.schema.namespace, c.wireMode, SenderInbound(c)))
    }

    /** The other engine ended the call: everything that waits for it fails at once, as when the fabric stops. */
    private fun connectionLost(c: Connection, cause: Throwable?) {
        if (stopping != null) return
        val why = c.remoteReason
        val reason = when {
            why != null -> TetherDeliveryException(why, cause)
            cause is TetherDeliveryException -> cause
            else -> TetherDeliveryException("tether ${c.info.id}: the connection to ${c.remote?.address} was lost", cause)
        }
        c.lost = reason
        c.lostSignal.complete(Unit)
        c.queue?.close(reason)
        for (r in c.remoteRequests.values) r.completeExceptionally(reason)
        c.remoteRequests.clear()
        for (st in c.remoteStreams.values) st.fail(reason)
        c.remoteStreams.clear()
        onDeliveryFailure(c.info, reason)
    }

    /** What the other engine sends to the sending end of [c]: responses, stream items and errors. */
    private inner class SenderInbound(private val c: Connection) : RemoteInbound {
        override suspend fun onFrame(frame: WireFrame) {
            val id = frame.correlationOrStreamId.toLong()
            when (frame) {
                is Response -> c.remoteRequests.remove(id)?.let { pending ->
                    try {
                        validate(c, frame.value, "response")
                        val value = fromJson(frame.value) ?: JsonNull
                        hook(c, TrafficKind.RESPONSE, value)
                        pending.complete(value)
                    } catch (e: TetherValidationException) {
                        pending.completeExceptionally(e)
                    }
                }
                is StreamItem -> c.remoteStreams[id]?.let { receiveItem(c, it, frame.value) }
                is Bytes -> c.remoteStreams[id]?.let { send(it.fromWire, frame.payload) }
                is StreamClose -> c.remoteStreams.remove(id)?.closedByTheOtherEngine()
                is cringle.wire.Error -> {
                    val failure = RemoteErrors.exception(frame.value)
                    when {
                        c.remoteRequests.remove(id)?.completeExceptionally(failure) != null -> {}
                        c.remoteStreams.remove(id)?.fail(failure) != null -> {}
                        else -> {
                            if (id == 0L && RemoteErrors.code(frame.value) == RemoteErrors.STOPPING) c.remoteReason = failure.message
                            onDeliveryFailure(c.info, failure)
                        }
                    }
                }
                else -> {}
            }
        }

        override fun onEnded(cause: Throwable?) = connectionLost(c, cause)

        /** The target is looked up again: what waited for the old connection fails, the tether goes on. */
        override fun onInterrupted(cause: Throwable?) {
            if (stopping != null) return
            val reason = TetherDeliveryException("tether ${c.info.id}: the connection to the fabric '${c.remote?.fabric}' was interrupted, it is being looked up again", cause)
            for (r in c.remoteRequests.values) r.completeExceptionally(reason)
            c.remoteRequests.clear()
            for (st in c.remoteStreams.values) st.fail(reason)
            c.remoteStreams.clear()
            onDeliveryFailure(c.info, reason)
        }
    }

    /** A sender is allowed for [c]: what it sends is queued like the traffic of a local tether. */
    private inner class ReceiverInbound(private val c: Connection, private val session: RemoteSession, val caller: String? = null) : RemoteInbound {
        private val streams = ConcurrentHashMap<Long, RemoteStream>()
        private val ns get() = c.toPort.schema.namespace

        override suspend fun onFrame(frame: WireFrame) {
            val id = frame.correlationOrStreamId.toLong()
            val type = c.info.type
            when {
                frame is Message && type == TetherType.MESSAGE -> {
                    val queue = running(c)
                    validate(c, frame.value, "message")
                    send(queue, Envelope.Message(fromJson(frame.value) ?: JsonNull))
                }
                frame is Request && type == TetherType.REQUEST_RESPONSE -> {
                    val queue = running(c)
                    validate(c, frame.value, "request")
                    val response = CompletableDeferred<Any>()
                    send(queue, Envelope.Request(fromJson(frame.value) ?: JsonNull, response))
                    // the answer goes back whenever the block gives it, the call goes on meanwhile
                    scope?.launch {
                        try {
                            session.reply(Response(id.toULong(), ns, toJson(response.await())))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            runCatching { session.reply(cringle.wire.Error(id.toULong(), RemoteErrors.value(e))) }
                        }
                    }
                }
                frame is StreamOpen && (type == TetherType.STREAM || type == TetherType.BYTE_STREAM) -> {
                    val queue = running(c)
                    val st = RemoteStream(Channel(c.bufferCapacity), Channel(c.bufferCapacity))
                    streamChannels += st.toWire
                    streamChannels += st.fromWire
                    streams[id] = st
                    pumpToWire(c, id, st, { session.reply(it) }) { streams.remove(id) }
                    // the block gets the stream like one of a local tether: what comes from the wire is `toReceiver`
                    send(queue, if (type == TetherType.STREAM) Envelope.Stream(st.fromWire, st.toWire) else Envelope.Bytes(st.fromWire.cast(), st.toWire.cast()))
                }
                frame is StreamItem && type == TetherType.STREAM -> streams[id]?.let { receiveItem(c, it, frame.value) }
                frame is Bytes && type == TetherType.BYTE_STREAM -> streams[id]?.let { send(it.fromWire, frame.payload) }
                frame is StreamClose && (type == TetherType.STREAM || type == TetherType.BYTE_STREAM) -> streams.remove(id)?.closedByTheOtherEngine()
                else -> throw TetherDeliveryException("tether ${c.info.id}: a ${frame.frameType} frame is not valid on a $type tether")
            }
        }

        override fun onEnded(cause: Throwable?) {
            c.sessions -= this
            val reason = TetherDeliveryException("tether ${c.info.id}: the connection to the sender ended", cause)
            for (st in streams.values) st.fail(reason)
            streams.clear()
        }

        /** The fabric stops: the sender is told, then the call ends. */
        fun stop(reason: String) {
            notifier.launch {
                runCatching { session.reply(cringle.wire.Error(0u, RemoteErrors.value(RemoteErrors.STOPPING, reason))) }
                session.end()
            }
        }
    }

    private fun running(c: Connection): Channel<Envelope> = c.queue ?: throw TetherDeliveryException("tether ${c.info.id}: the fabric is not running")

    /** Validates a stream item from the wire against the schema of the local port and hands it to the reader of the stream. */
    private suspend fun receiveItem(c: Connection, st: RemoteStream, value: JsonElement) {
        validate(c, value, "stream item")
        send(st.fromWire, fromJson(value) ?: JsonNull)
    }

    /**
     * Forwards what the local side writes to a remote stream as frames: items (or bytes, in frames of at most
     * [MAX_BYTES_FRAME]), and a `STREAM_CLOSE` when the local side closes its end. Closing is for both directions: the
     * reading end is closed with it. [forget] drops the stream when it is over.
     */
    private fun pumpToWire(c: Connection, id: Long, st: RemoteStream, write: suspend (WireFrame) -> Unit, forget: () -> Unit) {
        val s = scope ?: return
        s.launch {
            try {
                for (item in st.toWire) {
                    if (item is ByteArray) {
                        var offset = 0
                        while (offset < item.size) {
                            val end = minOf(item.size, offset + MAX_BYTES_FRAME)
                            write(Bytes(id.toULong(), item.copyOfRange(offset, end)))
                            offset = end
                        }
                    } else {
                        write(StreamItem(id.toULong(), c.fromPort.schema.namespace, toJson(item)))
                    }
                }
                if (!st.closedByWire) write(StreamClose(id.toULong()))
                st.fromWire.close()
                forget()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                st.fail(e as? TetherDeliveryException ?: TetherDeliveryException("tether ${c.info.id}: ${e.message}", e))
                forget()
            }
        }
    }

    /** The sending end of a request over the wire. */
    private suspend fun remoteRequest(c: Connection, request: Any): Any {
        val call = c.call ?: throw c.lost ?: TetherDeliveryException("tether ${c.info.id}: the fabric is not running")
        val value = validate(c, request, "request")
        hook(c, TrafficKind.REQUEST, value)
        val id = c.nextId.getAndIncrement()
        val response = CompletableDeferred<Any>()
        c.remoteRequests[id] = response
        pendingRequests += response
        response.invokeOnCompletion {
            c.remoteRequests.remove(id)
            pendingRequests.remove(response)
        }
        try {
            call.send(Request(id.toULong(), c.fromPort.schema.namespace, toJson(value)))
            return withTimeout(c.requestTimeout.toMillis()) { response.await() }
        } catch (e: RemoteUnavailableException) {
            response.cancel()
            throw TetherDeliveryException(e.message.orEmpty(), e)
        } catch (e: TimeoutCancellationException) {
            response.cancel()
            throw TetherTimeoutException("tether ${c.info.id}: no response within ${c.requestTimeout}")
        } catch (e: Throwable) {
            response.cancel()
            throw e
        }
    }

    /** The sending end of a stream (or byte stream) over the wire: the stream is opened on the other engine before this returns. */
    private suspend fun <T> openRemoteStream(c: Connection, wrap: (Channel<Any>, Channel<Any>) -> T): T {
        val call = c.call ?: throw c.lost ?: TetherDeliveryException("tether ${c.info.id}: the fabric is not running")
        val st = RemoteStream(Channel(c.bufferCapacity), Channel(c.bufferCapacity))
        val id = c.nextId.getAndIncrement()
        streamChannels += st.toWire
        streamChannels += st.fromWire
        c.remoteStreams[id] = st
        try {
            hook(c, TrafficKind.STREAM_OPENED, null)
            call.send(StreamOpen(id.toULong()))
        } catch (e: CancellationException) {
            c.remoteStreams.remove(id)
            st.fail(e)
            throw e
        } catch (e: Throwable) {
            c.remoteStreams.remove(id)
            val failure = e as? TetherDeliveryException ?: TetherDeliveryException("tether ${c.info.id}: ${e.message}", e)
            st.fail(failure)
            throw failure
        }
        pumpToWire(c, id, st, { call.send(it) }) { c.remoteStreams.remove(id) }
        return wrap(st.toWire, st.fromWire)
    }

    private suspend fun openTcp(c: Connection, s: CoroutineScope, deliverer: TetherDeliverer) {
        val provider = config.tcp ?: throw TetherWiringException("tether ${c.info.id}: this fabric has no TCP driver")
        val port = checkNotNull(c.tcpPort)
        val receiver = provider(c.info.to.block).also { tcpDrivers += it }
        c.sender = provider(c.info.from.block).also { tcpDrivers += it }
        val listener = receiver.listen(port, acceptCapacity = c.bufferCapacity)
        val target = PortRef(c.info.to.port, c.info.to.index)
        s.launch {
            listener.connections.collect { connection ->
                try {
                    deliverer.deliver(c.info.to.block, TetherEvent.ByteStreamOpened(target, TcpByteStream(c, connection)))
                } catch (e: CancellationException) {
                    connection.close()
                    throw e
                } catch (e: Throwable) {
                    connection.close()
                    onDeliveryFailure(c.info, TetherDeliveryException("tether ${c.info.id}: delivery to '${c.info.to.block}' failed: ${e.message}", e))
                }
            }
        }
    }

    private suspend fun openSerial(c: Connection, deliverer: TetherDeliverer) {
        val provider = config.serial ?: throw TetherWiringException("tether ${c.info.id}: this fabric has no serial driver")
        val serial = checkNotNull(c.serialConfig)
        val driver = provider(c.info.to.block).also { serialDrivers += it }
        val connection = try {
            driver.open(serial.device, SerialSettings(serial.baudRate, serial.dataBits, serial.parity, serial.stopBits))
        } catch (e: DeviceInUseException) {
            throw TetherWiringException("tether ${c.info.id}: ${e.message}")
        } catch (e: java.io.IOException) {
            throw TetherWiringException("tether ${c.info.id}: cannot open serial device '${serial.device}': ${e.message}")
        }
        c.serialConnection = connection
        val target = PortRef(c.info.to.port, c.info.to.index)
        try {
            deliverer.deliver(c.info.to.block, TetherEvent.ByteStreamOpened(target, connection))
        } catch (e: CancellationException) {
            connection.close()
            throw e
        } catch (e: Throwable) {
            connection.close()
            onDeliveryFailure(c.info, TetherDeliveryException("tether ${c.info.id}: delivery to '${c.info.to.block}' failed: ${e.message}", e))
        }
    }

    private inner class TcpByteStream(private val c: Connection, private val connection: TcpConnection) : TetherByteStream {
        override val incoming: Flow<ByteArray> = connection.incoming

        override suspend fun write(bytes: ByteArray) {
            hook(c, TrafficKind.BYTES, bytes)
            connection.write(bytes)
        }

        override suspend fun close() = connection.close()
    }

    /**
     * Stops delivery, fails waiting senders and requests and closes open streams; the drivers of the TCP tethers get
     * their sockets closed but are not waited for, see [awaitClosed]. Never blocks a thread, and may be called more
     * than once.
     */
    override fun close() {
        val reason = TetherDeliveryException("the fabric is stopping")
        stopping = reason
        // before the queues are closed: a sender that waits for room does not learn anything from close(cause)
        stopped.complete(Unit)
        scope?.cancel()
        scope = null
        deliverer = null
        for (c in connections.values.distinctBy { it.info.id }) {
            c.queue?.close(reason)
            c.call?.close()
            c.call = null
            for (session in c.sessions) session.stop("tether ${c.info.id}: the fabric is stopping")
            c.sessions.clear()
            c.remoteRequests.clear()
            c.remoteStreams.clear()
        }
        for (ch in streamChannels) ch.close(reason)
        streamChannels.clear()
        for (r in pendingRequests) r.completeExceptionally(reason)
        pendingRequests.clear()
        for (d in tcpDrivers) (d as? AutoCloseable)?.close()
        closingDrivers = tcpDrivers.toList()
        tcpDrivers.clear()
        for (d in serialDrivers) (d as? AutoCloseable)?.close()
        closingSerialDrivers = serialDrivers.toList()
        serialDrivers.clear()
    }

    /**
     * Ends what [create] registered at the remote driver: the senders that the receiving ends of this network allow.
     * Called when the fabric is removed; [close] only stops the traffic.
     */
    public fun release() {
        close()
        registration?.close()
        registration = null
        synchronized(serviceRegistrations) {
            serviceRegistrations.values.forEach { it.close() }
            serviceRegistrations.clear()
        }
    }

    /**
     * Replaces the instances of the services that sending tethers of this network call (#173): [remotes] maps a service
     * name to its new far end with the alternatives. A tether that is open switches to the first reachable instance; one
     * that is not open uses the new ones when it opens. Services that are not named keep their instances.
     */
    public fun updateServiceBindings(remotes: Map<String, RemoteEndpoint>) {
        for (c in connections.values.distinctBy { it.info.id }) {
            if (!c.remoteSends) continue
            val next = remotes[c.remote?.service ?: continue] ?: continue
            c.remote = next
            c.call?.updateRemote(next)
        }
    }

    /** The registrations at the remote driver of the callers of the provided service ports, by public key fingerprint. */
    private val serviceRegistrations = HashMap<String, AutoCloseable>()

    /**
     * Lets exactly the engines with the public key fingerprints [fingerprints] call the provided service ports of the
     * blueprint (#177). A new caller is accepted from now on. A caller that is no longer in the list is refused at its next
     * call, and its open calls are ended (the sender is told). Callers that stay are not touched. Without provided service
     * ports this does nothing.
     */
    public fun setServiceCallers(fingerprints: Collection<String>) {
        val services = connections.values.filter { it.serviceName != null }.distinctBy { it.info.id }
        if (services.isEmpty()) return
        val wanted = fingerprints.toSet()
        val driver = config.remote ?: throw TetherWiringException("this engine cannot run tethers to other engines")
        val removed = ArrayList<String>()
        synchronized(serviceRegistrations) {
            for (fingerprint in serviceRegistrations.keys.toList()) {
                if (fingerprint !in wanted) {
                    serviceRegistrations.remove(fingerprint)?.close()
                    removed += fingerprint
                }
            }
            for (fingerprint in wanted) {
                if (fingerprint in serviceRegistrations) continue
                serviceRegistrations[fingerprint] = driver.register(
                    services.map { c ->
                        val local = c.info.to
                        RemoteReceiver(c.info.id, local.block, local.port, local.index, fingerprint, c.wireMode) { session ->
                            ReceiverInbound(c, session, fingerprint).also { c.sessions += it }
                        }
                    },
                )
            }
        }
        for (c in services) {
            for (session in c.sessions.toList()) {
                if (session.caller in removed) session.stop("tether ${c.info.id}: this engine is no longer allowed to call the service")
            }
        }
    }

    /** The reason a sender gets when the network stops under it. */
    private fun stopReason(): TetherDeliveryException = stopping ?: TetherDeliveryException("the fabric is stopping")

    /**
     * Puts [value] into a bounded [channel] and suspends while it is full, as every other send does. A closed
     * channel alone would leave a waiting sender suspended, so the stop is awaited beside the send.
     */
    private suspend fun <T> send(channel: Channel<T>, value: T, connection: Connection? = null) {
        select {
            stopped.onAwait { throw stopReason() }
            connection?.lostSignal?.onAwait { throw connection.lost ?: stopReason() }
            channel.onSend(value) { }
        }
    }

    /**
     * Waits at most [timeout] until the TCP tethers closed their listeners and freed their ports, and returns whether
     * that happened. One timeout for all of them, never on a dispatcher thread.
     */
    public suspend fun awaitClosed(timeout: Duration): Boolean {
        val drivers = closingDrivers
        val start = System.nanoTime()
        var allClosed = true
        for (d in drivers) {
            val left = timeout.minusNanos(System.nanoTime() - start)
            if (!d.awaitClosed(if (left.isNegative) Duration.ZERO else left)) allClosed = false
        }
        for (d in closingSerialDrivers) {
            val left = timeout.minusNanos(System.nanoTime() - start)
            if (!d.awaitClosed(if (left.isNegative) Duration.ZERO else left)) allClosed = false
        }
        return allClosed
    }

    private class Connection(
        val info: TetherInfo,
        val fromPort: PortDefinition,
        val toPort: PortDefinition,
        val policy: DeliveryPolicy,
        val tcpPort: Int?,
        val bufferCapacity: Int,
        val requestTimeout: Duration,
        val retry: RetryConfig,
        val serialConfig: SerialTetherConfig?,
        /** Set for a tether that ends on another engine; [remoteSends] says whether the local end is the sending one. */
        @Volatile var remote: RemoteEndpoint? = null,
        val remoteSends: Boolean = false,
        /** Set for the receiving end of a provided service port: the name of the service (#177). */
        val serviceName: String? = null,
    ) {
        /** The open call of a tether whose local end sends to another engine. */
        @Volatile var call: RemoteCall? = null

        /** Why the call to the other engine ended, or `null`. */
        @Volatile var lost: TetherDeliveryException? = null

        /** Completed with [lost]: a sender that waits for room in the queue waits for this as well (closing the queue does not wake it). */
        @Volatile var lostSignal: CompletableDeferred<Unit> = CompletableDeferred()

        /** What the other engine said when it ended the call (it is stopping), or `null`. */
        @Volatile var remoteReason: String? = null

        /** The wire mode of the tether. */
        val wireMode: TetherMode = if (info.type == TetherType.BYTE_STREAM) TetherMode.BYTES else TetherMode.TYPED

        /** Ids of the requests and streams that the sending end starts. */
        val nextId = AtomicLong(1)

        /** The requests and streams of the sending end that wait for the other engine. */
        val remoteRequests = ConcurrentHashMap<Long, CompletableDeferred<Any>>()
        val remoteStreams = ConcurrentHashMap<Long, RemoteStream>()

        /** The accepted calls of the receiving end. */
        val sessions = CopyOnWriteArrayList<ReceiverInbound>()

        @Volatile var queue: Channel<Envelope>? = null

        @Volatile var sender: TcpDriver? = null

        @Volatile var serialConnection: SerialConnection? = null
    }

    /** One stream (or byte stream) of a tether that ends on another engine: [toWire] is what the local side writes, [fromWire] what it reads. */
    private class RemoteStream(val toWire: Channel<Any>, val fromWire: Channel<Any>) {
        /** The other engine closed the stream, so it needs no `STREAM_CLOSE` back. */
        @Volatile var closedByWire = false

        /** Ends the stream for the local side with [reason]: readers and writers fail with it. */
        fun fail(reason: Throwable) {
            toWire.close(reason)
            fromWire.close(reason)
        }

        /** `STREAM_CLOSE` from the other engine: closing is for both directions. */
        fun closedByTheOtherEngine() {
            closedByWire = true
            fromWire.close()
            toWire.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Channel<Any>.cast(): Channel<T> = this as Channel<T>

    private sealed interface Envelope {
        class Message(val value: Any) : Envelope
        class Request(val value: Any, val response: CompletableDeferred<Any>) : Envelope
        class Stream(val toReceiver: Channel<Any>, val fromReceiver: Channel<Any>) : Envelope
        class Bytes(val toReceiver: Channel<ByteArray>, val fromReceiver: Channel<ByteArray>) : Envelope
    }

    private suspend fun pump(c: Connection) {
        val queue = c.queue ?: return
        val to = c.info.to
        val port = PortRef(to.port, to.index)
        try {
            for (env in queue) {
                if (env is Envelope.Request && env.response.isCancelled) continue
                var first = true
                var attempt = 0
                while (true) {
                    try {
                        val target = deliverer ?: return
                        val isFirst = first
                        first = false
                        handle(c, env, target, port, isFirst)
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        attempt++
                        if (c.policy == DeliveryPolicy.BUFFER && (e is FabricException || e is RemoteUnavailableException)) {
                            val maxAttempts = c.retry.maxAttempts
                            if (maxAttempts != null && attempt >= maxAttempts) {
                                fail(c, env, to, e, "after $attempt attempts")
                                break
                            }
                            delay(retryDelay(c.retry, attempt))
                            if (env is Envelope.Request && env.response.isCancelled) break
                            continue
                        }
                        fail(c, env, to, e, null)
                        break
                    }
                }
            }
        } catch (_: CancellationException) {
            // closed
        } catch (e: Throwable) {
            // close() closed the queue with the reason its senders get; nothing is left to pump
            if (e !== stopping && e !== c.lost) throw e
        }
    }

    private fun fail(c: Connection, env: Envelope, to: Endpoint, e: Throwable, suffix: String?) {
        val detail = suffix?.let { " $it" }.orEmpty()
        val failure = TetherDeliveryException("tether ${c.info.id}: delivery to '${to.block}' failed$detail: ${e.message}", e)
        if (env is Envelope.Request) env.response.completeExceptionally(failure)
        onDeliveryFailure(c.info, failure)
    }

    private fun retryDelay(retry: RetryConfig, failedAttempt: Int): Long {
        if (retry.backoff == Backoff.FIXED) return retry.backoffMs
        var d = retry.backoffMs
        repeat(failedAttempt - 1) { d = minOf(d * 2, retry.maxBackoffMs) }
        return minOf(d, retry.maxBackoffMs)
    }

    private suspend fun handle(c: Connection, env: Envelope, target: TetherDeliverer, port: PortRef, first: Boolean) {
        val to = c.info.to
        when (env) {
            is Envelope.Message -> {
                if (first) hook(c, TrafficKind.MESSAGE, env.value)
                if (c.remoteSends) {
                    val call = c.call ?: throw c.lost ?: TetherDeliveryException("tether ${c.info.id}: the fabric is not running")
                    call.send(Message(0u, c.fromPort.schema.namespace, toJson(env.value)))
                } else {
                    target.deliver(to.block, TetherEvent.Message(port, env.value))
                }
            }
            is Envelope.Request -> {
                if (first) hook(c, TrafficKind.REQUEST, env.value)
                target.deliver(
                    to.block,
                    TetherEvent.Request(port, env.value) { response ->
                        val checked = validate(c, response, "response")
                        hook(c, TrafficKind.RESPONSE, checked)
                        check(env.response.complete(checked)) { "request on ${c.info.id} was already answered" }
                    },
                )
            }
            is Envelope.Stream -> {
                if (first) hook(c, TrafficKind.STREAM_OPENED, null)
                target.deliver(to.block, TetherEvent.StreamOpened(port, ValueStream(c, env.fromReceiver, env.toReceiver)))
            }
            is Envelope.Bytes -> {
                if (first) hook(c, TrafficKind.STREAM_OPENED, null)
                target.deliver(to.block, TetherEvent.ByteStreamOpened(port, ByteStream(c, env.fromReceiver, env.toReceiver)))
            }
        }
    }

    private suspend fun hook(c: Connection, kind: TrafficKind, payload: Any?) {
        config.observer?.observe(c.info, kind, payload)
        config.interceptor?.beforeDelivery(c.info, kind, payload)
    }

    private fun validate(c: Connection, value: Any, what: String): Any {
        val v = validator ?: return value
        val json = try {
            toJson(value)
        } catch (e: IllegalArgumentException) {
            throw TetherValidationException("tether ${c.info.id}: $what is not valid data: ${e.message}", listOf(e.message.orEmpty()))
        }
        val errors = v.validate(json, c.fromPort.schema)
        if (errors.isNotEmpty()) {
            val list = errors.map { "${it.path}: ${it.message}" }
            throw TetherValidationException(
                "tether ${c.info.id}: $what violates schema ${c.fromPort.schema}:\n" + list.joinToString("\n") { "  $it" },
                list,
            )
        }
        return value
    }

    override fun tether(blockId: BlockId, port: PortDefinition, index: Int?): Tether {
        val key = key(blockId.value, port.name, index)
        val connection = connections[key]
        return when {
            connection == null -> Unconnected(blockId.value, port, index)
            port.direction == PortDirection.OUT -> Outgoing(connection)
            else -> Incoming(connection)
        }
    }

    private class Unconnected(block: String, port: PortDefinition, index: Int?) : Tether {
        private val what = "port '${port.name}${index?.let { "[$it]" }.orEmpty()}' of block '$block' is not connected in the blueprint"
        override val type: TetherType = port.tetherTypes.first()
        override suspend fun send(message: Any): Unit = throw IllegalStateException(what)
        override suspend fun request(request: Any): Any = throw IllegalStateException(what)
        override suspend fun openStream(): TetherStream = throw IllegalStateException(what)
        override suspend fun openByteStream(): TetherByteStream = throw IllegalStateException(what)
    }

    private class Incoming(private val c: Connection) : Tether {
        override val type: TetherType = c.info.type
        private fun fail(): Nothing = throw IllegalStateException("port '${c.info.to.port}' of block '${c.info.to.block}' is an input port; it receives events and cannot initiate traffic")
        override suspend fun send(message: Any): Unit = fail()
        override suspend fun request(request: Any): Any = fail()
        override suspend fun openStream(): TetherStream = fail()
        override suspend fun openByteStream(): TetherByteStream = fail()
    }

    private inner class Outgoing(private val c: Connection) : Tether {
        override val type: TetherType = c.info.type

        private fun require(t: TetherType, operation: String) {
            check(type == t) { "tether ${c.info.id} has type $type; $operation is only valid for $t" }
        }

        private fun requireByteStream() {
            check(type == TetherType.BYTE_STREAM || type == TetherType.TCP || type == TetherType.SERIAL) {
                "tether ${c.info.id} has type $type; openByteStream is only valid for BYTE_STREAM, TCP and SERIAL"
            }
        }

        private fun queue(): Channel<Envelope> = c.queue ?: throw IllegalStateException("tether ${c.info.id}: the fabric is not running")

        override suspend fun send(message: Any) {
            require(TetherType.MESSAGE, "send")
            // a closed queue throws the reason close() gave it
            send(queue(), Envelope.Message(validate(c, message, "message")), c)
        }

        override suspend fun request(request: Any): Any {
            require(TetherType.REQUEST_RESPONSE, "request")
            if (c.remoteSends) return remoteRequest(c, request)
            val response = CompletableDeferred<Any>()
            pendingRequests += response
            response.invokeOnCompletion { pendingRequests.remove(response) }
            send(queue(), Envelope.Request(validate(c, request, "request"), response))
            try {
                return withTimeout(c.requestTimeout.toMillis()) { response.await() }
            } catch (e: TimeoutCancellationException) {
                response.cancel()
                throw TetherTimeoutException("tether ${c.info.id}: no response within ${c.requestTimeout}")
            }
        }

        override suspend fun openStream(): TetherStream {
            require(TetherType.STREAM, "openStream")
            if (c.remoteSends) return openRemoteStream(c) { toWire, fromWire -> ValueStream(c, toWire, fromWire) }
            val toReceiver = Channel<Any>(c.bufferCapacity)
            val fromReceiver = Channel<Any>(c.bufferCapacity)
            streamChannels += toReceiver
            streamChannels += fromReceiver
            send(queue(), Envelope.Stream(toReceiver, fromReceiver))
            return ValueStream(c, toReceiver, fromReceiver)
        }

        override suspend fun openByteStream(): TetherByteStream {
            requireByteStream()
            if (type == TetherType.SERIAL) return c.serialConnection ?: throw IllegalStateException("tether ${c.info.id}: the fabric is not running")
            if (type == TetherType.TCP) {
                val driver = c.sender ?: throw IllegalStateException("tether ${c.info.id}: the fabric is not running")
                val connection = try {
                    driver.connect("127.0.0.1", checkNotNull(c.tcpPort))
                } catch (e: java.io.IOException) {
                    throw TetherDeliveryException("tether ${c.info.id}: cannot connect to port ${c.tcpPort}: ${e.message}", e)
                }
                return TcpByteStream(c, connection)
            }
            if (c.remoteSends) return openRemoteStream(c) { toWire, fromWire -> ByteStream(c, toWire.cast(), fromWire.cast()) }
            val toReceiver = Channel<ByteArray>(c.bufferCapacity)
            val fromReceiver = Channel<ByteArray>(c.bufferCapacity)
            streamChannels += toReceiver
            streamChannels += fromReceiver
            send(queue(), Envelope.Bytes(toReceiver, fromReceiver))
            return ByteStream(c, toReceiver, fromReceiver)
        }
    }

    private inner class ValueStream(
        private val c: Connection,
        private val outbound: Channel<Any>,
        inbound: Channel<Any>,
    ) : TetherStream {
        override val incoming: Flow<Any> = inbound.receiveAsFlow()

        override suspend fun send(item: Any) {
            val checked = validate(c, item, "stream item")
            hook(c, TrafficKind.STREAM_ITEM, checked)
            send(outbound, checked, c)
        }

        override suspend fun close() {
            streamChannels.remove(outbound)
            outbound.close()
        }
    }

    private inner class ByteStream(
        private val c: Connection,
        private val outbound: Channel<ByteArray>,
        inbound: Channel<ByteArray>,
    ) : TetherByteStream {
        override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()

        override suspend fun write(bytes: ByteArray) {
            hook(c, TrafficKind.BYTES, bytes)
            send(outbound, bytes, c)
        }

        override suspend fun close() {
            streamChannels.remove(outbound)
            outbound.close()
        }
    }

    public companion object {
        /** The largest payload of one `BYTES` frame; a bigger write is split (the wire format allows 4 MiB per frame). */
        private const val MAX_BYTES_FRAME = 256 * 1024

        private fun key(block: String, port: String, index: Int?) = "$block/$port/${index ?: "-"}"

        /**
         * Wires [blueprint]: [definitions] maps block ids to their definitions. Throws [TetherWiringException] listing
         * every problem (unknown block or port, wrong direction, tether type not supported by a port, schema
         * mismatch, VarArg index out of range, endpoint used twice).
         */
        public fun create(
            blueprint: Blueprint,
            definitions: Map<String, BlockDefinition>,
            config: TetherConfig = TetherConfig(),
            onDeliveryFailure: (TetherInfo, Throwable) -> Unit = { _, _ -> },
        ): TetherNetwork {
            val problems = ArrayList<String>()
            val connections = HashMap<String, Connection>()
            val counts = blueprint.blocks.associate { it.id to it.varArgCounts }

            fun endpoint(e: Endpoint, direction: PortDirection, label: String): PortDefinition? {
                val def = definitions[e.block]
                if (def == null) {
                    problems += "$label: unknown block '${e.block}'"
                    return null
                }
                val port = def.ports.firstOrNull { it.name == e.port }
                if (port == null) {
                    problems += "$label: block '${e.block}' has no port '${e.port}'"
                    return null
                }
                if (port.direction != direction) problems += "$label: port '${e.block}.${e.port}' is ${port.direction}, expected $direction"
                if (port.varArg) {
                    val count = counts[e.block]?.get(e.port) ?: 0
                    if (e.index == null || e.index !in 0 until count) problems += "$label: VarArg port '${e.block}.${e.port}' needs an index in 0..${count - 1}, found ${e.index}"
                } else if (e.index != null) {
                    problems += "$label: port '${e.block}.${e.port}' is not a VarArg port but has an index"
                }
                return port
            }

            val receivers = ArrayList<Pair<Connection, RemoteEndpoint>>()
            for (p in blueprint.provides) {
                val label = "service ${p.service}"
                val local = Endpoint(p.block, p.port)
                val port = endpoint(local, PortDirection.IN, label) ?: continue
                val type = p.type ?: port.tetherTypes.filter { it in remoteTypes }.singleOrNull()
                if (type == null || type !in port.tetherTypes || type !in remoteTypes) {
                    problems += "$label: port '${p.block}.${p.port}' needs a tether type from ${remoteTypes.joinToString()} that it supports"
                    continue
                }
                if (config.remote == null) problems += "$label: this engine cannot run tethers to other engines"
                val c = Connection(
                    TetherInfo(label, type, Endpoint("service", p.service), local),
                    port,
                    port,
                    DeliveryPolicy.DROP,
                    null,
                    config.bufferCapacity,
                    config.requestTimeout,
                    RetryConfig(),
                    null,
                    RemoteEndpoint(null, "", "(service)", p.block, p.port),
                    false,
                    p.service,
                )
                if (connections.put(key(local.block, local.port, null), c) != null) problems += "$label: port '${p.block}.${p.port}' is already connected"
            }
            for (t in blueprint.tethers) {
                val remote = t.remote
                val localFrom = t.from
                val localTo = t.to
                if (t.service != null) {
                    problems += "tether ${t.type} to the service '${t.service}': the service is not bound to a fabric (a service dependency is bound when the project is deployed)"
                    continue
                }
                if (remote != null) {
                    val local = if (localFrom != null && localTo == null) localFrom else if (localTo != null && localFrom == null) localTo else null
                    if (local == null) {
                        problems += "tether ${t.type} to ${remote.address ?: "registry"}: a remote tether has exactly one local endpoint"
                        continue
                    }
                    val sends = local === localFrom
                    val far = Endpoint(remote.block, remote.port, remote.index)
                    fun label(e: Endpoint) = "${e.block}.${e.port}${e.index?.let { "[$it]" }.orEmpty()}"
                    val id = if (sends) "${label(local)} -> ${remote.address ?: "registry"}/${remote.fabric}/${label(far)}" else "${remote.address ?: "registry"}/${remote.fabric}/${label(far)} -> ${label(local)}"
                    val port = endpoint(local, if (sends) PortDirection.OUT else PortDirection.IN, "tether $id") ?: continue
                    if (t.type !in port.tetherTypes) problems += "tether $id: port '${local.port}' does not support ${t.type}"
                    if (t.type == TetherType.TCP || t.type == TetherType.SERIAL) problems += "tether $id: a ${t.type} tether is a local resource and cannot end on another engine"
                    if (config.remote == null) problems += "tether $id: this engine cannot run tethers to other engines"
                    val c = Connection(
                        TetherInfo(id, t.type, if (sends) local else far, if (sends) far else local, remote),
                        port,
                        port,
                        t.delivery,
                        null,
                        t.bufferCapacity ?: config.bufferCapacity,
                        t.requestTimeout ?: config.requestTimeout,
                        t.retry ?: RetryConfig(),
                        null,
                        remote,
                        sends,
                    )
                    val k = key(local.block, local.port, local.index)
                    if (connections.put(k, c) != null) problems += "tether $id: endpoint '${local.block}.${local.port}' is already connected"
                    if (!sends) receivers += c to remote
                    continue
                }
                if (localFrom == null || localTo == null) {
                    problems += "tether ${t.type}: a tether needs a from and a to endpoint, or one of them and a remote"
                    continue
                }
                val id = "${localFrom.block}.${localFrom.port}${localFrom.index?.let { "[$it]" }.orEmpty()} -> ${localTo.block}.${localTo.port}${localTo.index?.let { "[$it]" }.orEmpty()}"
                val from = endpoint(localFrom, PortDirection.OUT, "tether $id")
                val to = endpoint(localTo, PortDirection.IN, "tether $id")
                if (from == null || to == null) continue
                if (t.type !in from.tetherTypes) problems += "tether $id: port '${localFrom.port}' does not support ${t.type}"
                if (t.type !in to.tetherTypes) problems += "tether $id: port '${localTo.port}' does not support ${t.type}"
                if (t.type != TetherType.BYTE_STREAM && t.type != TetherType.TCP && t.type != TetherType.SERIAL) {
                    val registry = config.schemas
                    val ok = if (registry != null) cringle.schema.isAssignable(from.schema, to.schema, registry) else from.schema == to.schema
                    if (!ok) problems += "tether $id: schema ${from.schema} is not assignable to ${to.schema}"
                }
                if (t.type == TetherType.TCP) {
                    if (t.port == null) problems += "tether $id: a TCP tether needs a port"
                    if (config.tcp == null) problems += "tether $id: this fabric has no TCP driver"
                }
                if (t.type == TetherType.SERIAL) {
                    if (t.serial == null) problems += "tether $id: a SERIAL tether needs a serial device"
                    if (config.serial == null) problems += "tether $id: this fabric has no serial driver"
                }
                val c = Connection(
                    TetherInfo(id, t.type, localFrom, localTo),
                    from,
                    to,
                    t.delivery,
                    t.port,
                    t.bufferCapacity ?: config.bufferCapacity,
                    t.requestTimeout ?: config.requestTimeout,
                    t.retry ?: RetryConfig(),
                    t.serial,
                )
                for (e in listOf(localFrom, localTo)) {
                    val k = key(e.block, e.port, e.index)
                    if (connections.put(k, c) != null) problems += "tether $id: endpoint '${e.block}.${e.port}' is already connected"
                }
            }
            if (problems.isNotEmpty()) throw TetherWiringException("tethers cannot be wired:\n" + problems.joinToString("\n") { "  $it" })
            val network = TetherNetwork(config, connections, onDeliveryFailure)
            if (receivers.isNotEmpty()) {
                network.registration = config.remote!!.register(
                    receivers.map { (c, remote) ->
                        val local = c.info.to
                        RemoteReceiver(c.info.id, local.block, local.port, local.index, remote.fingerprint, c.wireMode) { session ->
                            network.ReceiverInbound(c, session).also { c.sessions += it }
                        }
                    },
                )
            }
            if (config.serviceCallers.isNotEmpty()) network.setServiceCallers(config.serviceCallers)
            return network
        }

        private val remoteTypes = setOf(TetherType.MESSAGE, TetherType.REQUEST_RESPONSE, TetherType.STREAM, TetherType.BYTE_STREAM)
    }
}
