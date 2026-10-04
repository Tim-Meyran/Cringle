// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.contract.BlockId
import cringle.contract.BlockDefinition
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.PortInUseException
import cringle.contract.PortRef
import cringle.contract.TcpConnection
import cringle.contract.TcpDriver
import cringle.contract.Tether
import cringle.contract.TetherByteStream
import cringle.contract.TetherEvent
import cringle.contract.TetherStream
import cringle.contract.TetherType
import cringle.engine.fabric.FabricException
import cringle.engine.fabric.PortWiring
import cringle.packaging.Backoff
import cringle.packaging.Blueprint
import cringle.packaging.DeliveryPolicy
import cringle.packaging.Endpoint
import cringle.packaging.RetryConfig
import cringle.schema.SchemaValidator
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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

    @Volatile private var scope: CoroutineScope? = null
    private val streamChannels = ConcurrentHashMap.newKeySet<Channel<*>>()
    private val tcpDrivers = CopyOnWriteArrayList<TcpDriver>()

    /** The TCP drivers the last [close] closed; [awaitClosed] waits for them. */
    @Volatile private var closingDrivers: List<TcpDriver> = emptyList()
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
            if (c.info.type == TetherType.TCP) continue
            c.queue = Channel(c.bufferCapacity)
            s.launch { pump(c) }
        }
        try {
            for (c in connections.values.distinctBy { it.info.id }) if (c.info.type == TetherType.TCP) openTcp(c, s, deliverer)
        } catch (e: PortInUseException) {
            close()
            throw TetherWiringException("tether cannot start: ${e.message}")
        } catch (e: java.io.IOException) {
            close()
            throw TetherWiringException("tether cannot start: ${e.message}")
        }
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
        for (c in connections.values.distinctBy { it.info.id }) c.queue?.close(reason)
        for (ch in streamChannels) ch.close(reason)
        streamChannels.clear()
        for (r in pendingRequests) r.completeExceptionally(reason)
        pendingRequests.clear()
        for (d in tcpDrivers) (d as? AutoCloseable)?.close()
        closingDrivers = tcpDrivers.toList()
        tcpDrivers.clear()
    }

    /** The reason a sender gets when the network stops under it. */
    private fun stopReason(): TetherDeliveryException = stopping ?: TetherDeliveryException("the fabric is stopping")

    /**
     * Puts [value] into a bounded [channel] and suspends while it is full, as every other send does. A closed
     * channel alone would leave a waiting sender suspended, so the stop is awaited beside the send.
     */
    private suspend fun <T> send(channel: Channel<T>, value: T) {
        select {
            stopped.onAwait { throw stopReason() }
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
    ) {
        @Volatile var queue: Channel<Envelope>? = null

        @Volatile var sender: TcpDriver? = null
    }

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
                        if (c.policy == DeliveryPolicy.BUFFER && e is FabricException) {
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
            if (e !== stopping) throw e
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
                target.deliver(to.block, TetherEvent.Message(port, env.value))
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
            check(type == TetherType.BYTE_STREAM || type == TetherType.TCP) {
                "tether ${c.info.id} has type $type; openByteStream is only valid for BYTE_STREAM and TCP"
            }
        }

        private fun queue(): Channel<Envelope> = c.queue ?: throw IllegalStateException("tether ${c.info.id}: the fabric is not running")

        override suspend fun send(message: Any) {
            require(TetherType.MESSAGE, "send")
            // a closed queue throws the reason close() gave it
            send(queue(), Envelope.Message(validate(c, message, "message")))
        }

        override suspend fun request(request: Any): Any {
            require(TetherType.REQUEST_RESPONSE, "request")
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
            val toReceiver = Channel<Any>(c.bufferCapacity)
            val fromReceiver = Channel<Any>(c.bufferCapacity)
            streamChannels += toReceiver
            streamChannels += fromReceiver
            send(queue(), Envelope.Stream(toReceiver, fromReceiver))
            return ValueStream(c, toReceiver, fromReceiver)
        }

        override suspend fun openByteStream(): TetherByteStream {
            requireByteStream()
            if (type == TetherType.TCP) {
                val driver = c.sender ?: throw IllegalStateException("tether ${c.info.id}: the fabric is not running")
                val connection = try {
                    driver.connect("127.0.0.1", checkNotNull(c.tcpPort))
                } catch (e: java.io.IOException) {
                    throw TetherDeliveryException("tether ${c.info.id}: cannot connect to port ${c.tcpPort}: ${e.message}", e)
                }
                return TcpByteStream(c, connection)
            }
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
            send(outbound, checked)
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
            send(outbound, bytes)
        }

        override suspend fun close() {
            streamChannels.remove(outbound)
            outbound.close()
        }
    }

    public companion object {
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

            for (t in blueprint.tethers) {
                val id = "${t.from.block}.${t.from.port}${t.from.index?.let { "[$it]" }.orEmpty()} -> ${t.to.block}.${t.to.port}${t.to.index?.let { "[$it]" }.orEmpty()}"
                val from = endpoint(t.from, PortDirection.OUT, "tether $id")
                val to = endpoint(t.to, PortDirection.IN, "tether $id")
                if (from == null || to == null) continue
                if (t.type !in from.tetherTypes) problems += "tether $id: port '${t.from.port}' does not support ${t.type}"
                if (t.type !in to.tetherTypes) problems += "tether $id: port '${t.to.port}' does not support ${t.type}"
                if (t.type != TetherType.BYTE_STREAM && t.type != TetherType.TCP) {
                    val registry = config.schemas
                    val ok = if (registry != null) cringle.schema.isAssignable(from.schema, to.schema, registry) else from.schema == to.schema
                    if (!ok) problems += "tether $id: schema ${from.schema} is not assignable to ${to.schema}"
                }
                if (t.type == TetherType.TCP) {
                    if (t.port == null) problems += "tether $id: a TCP tether needs a port"
                    if (config.tcp == null) problems += "tether $id: this fabric has no TCP driver"
                }
                val c = Connection(
                    TetherInfo(id, t.type, t.from, t.to),
                    from,
                    to,
                    t.delivery,
                    t.port,
                    t.bufferCapacity ?: config.bufferCapacity,
                    t.requestTimeout ?: config.requestTimeout,
                    t.retry ?: RetryConfig(),
                )
                for (e in listOf(t.from, t.to)) {
                    val k = key(e.block, e.port, e.index)
                    if (connections.put(k, c) != null) problems += "tether $id: endpoint '${e.block}.${e.port}' is already connected"
                }
            }
            if (problems.isNotEmpty()) throw TetherWiringException("tethers cannot be wired:\n" + problems.joinToString("\n") { "  $it" })
            return TetherNetwork(config, connections, onDeliveryFailure)
        }
    }
}
