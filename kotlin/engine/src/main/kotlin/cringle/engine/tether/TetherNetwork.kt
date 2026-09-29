// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.contract.BlockId
import cringle.contract.BlockDefinition
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.PortRef
import cringle.contract.Tether
import cringle.contract.TetherByteStream
import cringle.contract.TetherEvent
import cringle.contract.TetherStream
import cringle.contract.TetherType
import cringle.engine.fabric.PortWiring
import cringle.packaging.Blueprint
import cringle.packaging.Endpoint
import cringle.schema.SchemaValidator
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
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
    private val ports: Map<String, PortDefinition>,
    private val onDeliveryFailure: (TetherInfo, Throwable) -> Unit,
) : PortWiring {
    private val validator = config.schemas?.let { SchemaValidator(it) }

    @Volatile private var deliverer: TetherDeliverer? = null

    @Volatile private var scope: CoroutineScope? = null
    private val streamChannels = CopyOnWriteArrayList<Channel<*>>()

    /** All tethers of the blueprint. */
    public val tethers: List<TetherInfo> = connections.values.map { it.info }.distinctBy { it.id }

    /** Starts the delivery of messages to [deliverer]. */
    public fun open(deliverer: TetherDeliverer) {
        close()
        this.deliverer = deliverer
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        for (c in connections.values.distinctBy { it.info.id }) {
            c.queue = Channel(config.bufferCapacity)
            s.launch { pump(c) }
        }
    }

    /** Stops delivery, fails waiting senders and closes open streams. Safe to call more than once. */
    public fun close() {
        scope?.cancel()
        scope = null
        deliverer = null
        for (c in connections.values.distinctBy { it.info.id }) c.queue?.cancel()
        for (ch in streamChannels) ch.cancel()
        streamChannels.clear()
    }

    private class Connection(val info: TetherInfo, val fromPort: PortDefinition, val toPort: PortDefinition) {
        @Volatile var queue: Channel<Envelope>? = null
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
                try {
                    val target = deliverer ?: return
                    when (env) {
                        is Envelope.Message -> {
                            hook(c, TrafficKind.MESSAGE, env.value)
                            target.deliver(to.block, TetherEvent.Message(port, env.value))
                        }
                        is Envelope.Request -> {
                            hook(c, TrafficKind.REQUEST, env.value)
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
                            hook(c, TrafficKind.STREAM_OPENED, null)
                            target.deliver(to.block, TetherEvent.StreamOpened(port, ValueStream(c, env.fromReceiver, env.toReceiver)))
                        }
                        is Envelope.Bytes -> {
                            hook(c, TrafficKind.STREAM_OPENED, null)
                            target.deliver(to.block, TetherEvent.ByteStreamOpened(port, ByteStream(c, env.fromReceiver, env.toReceiver)))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val failure = TetherDeliveryException("tether ${c.info.id}: delivery to '${to.block}' failed: ${e.message}", e)
                    if (env is Envelope.Request) env.response.completeExceptionally(failure)
                    onDeliveryFailure(c.info, failure)
                }
            }
        } catch (_: CancellationException) {
            // closed
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

        private fun queue(): Channel<Envelope> = c.queue ?: throw IllegalStateException("tether ${c.info.id}: the fabric is not running")

        private suspend fun enqueue(e: Envelope) {
            try {
                queue().send(e)
            } catch (_: ClosedSendChannelException) {
                throw IllegalStateException("tether ${c.info.id}: the fabric is stopping")
            } catch (e: CancellationException) {
                throw e
            }
        }

        override suspend fun send(message: Any) {
            require(TetherType.MESSAGE, "send")
            enqueue(Envelope.Message(validate(c, message, "message")))
        }

        override suspend fun request(request: Any): Any {
            require(TetherType.REQUEST_RESPONSE, "request")
            val response = CompletableDeferred<Any>()
            enqueue(Envelope.Request(validate(c, request, "request"), response))
            try {
                return withTimeout(config.requestTimeout.toMillis()) { response.await() }
            } catch (e: TimeoutCancellationException) {
                throw TetherTimeoutException("tether ${c.info.id}: no response within ${config.requestTimeout}")
            }
        }

        override suspend fun openStream(): TetherStream {
            require(TetherType.STREAM, "openStream")
            val toReceiver = Channel<Any>(config.bufferCapacity)
            val fromReceiver = Channel<Any>(config.bufferCapacity)
            streamChannels += toReceiver
            streamChannels += fromReceiver
            enqueue(Envelope.Stream(toReceiver, fromReceiver))
            return ValueStream(c, toReceiver, fromReceiver)
        }

        override suspend fun openByteStream(): TetherByteStream {
            require(TetherType.BYTE_STREAM, "openByteStream")
            val toReceiver = Channel<ByteArray>(config.bufferCapacity)
            val fromReceiver = Channel<ByteArray>(config.bufferCapacity)
            streamChannels += toReceiver
            streamChannels += fromReceiver
            enqueue(Envelope.Bytes(toReceiver, fromReceiver))
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
            outbound.send(checked)
        }

        override suspend fun close() {
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
            outbound.send(bytes)
        }

        override suspend fun close() {
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
            val ports = HashMap<String, PortDefinition>()
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
                if (t.type != TetherType.BYTE_STREAM) {
                    val registry = config.schemas
                    val ok = if (registry != null) cringle.schema.isAssignable(from.schema, to.schema, registry) else from.schema == to.schema
                    if (!ok) problems += "tether $id: schema ${from.schema} is not assignable to ${to.schema}"
                }
                val c = Connection(TetherInfo(id, t.type, t.from, t.to), from, to)
                for (e in listOf(t.from, t.to)) {
                    val k = key(e.block, e.port, e.index)
                    if (connections.put(k, c) != null) problems += "tether $id: endpoint '${e.block}.${e.port}' is already connected"
                    ports[k] = if (e === t.from) from else to
                }
            }
            if (problems.isNotEmpty()) throw TetherWiringException("tethers cannot be wired:\n" + problems.joinToString("\n") { "  $it" })
            return TetherNetwork(config, connections, ports, onDeliveryFailure)
        }
    }
}
