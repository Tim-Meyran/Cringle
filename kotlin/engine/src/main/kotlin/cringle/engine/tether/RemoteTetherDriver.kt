// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import com.google.protobuf.ByteString
import cringle.common.Identity
import cringle.common.PublicKeyFingerprint
import cringle.common.TlsHelper
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.TrustStore
import cringle.engine.v1.RemoteTetherServiceGrpcKt
import cringle.engine.v1.WireData
import cringle.packaging.RemoteEndpoint
import cringle.wire.TetherMode
import cringle.wire.WireCodec
import cringle.wire.WireFormatException
import cringle.wire.WireFrame
import io.grpc.ConnectivityState
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Grpc
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.MetadataUtils
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The tethers between engines (`spec/tether.md`, "Tethers between engines"): serves [RemoteTetherServiceGrpcKt] for the
 * senders that the fabrics of this engine allow, and opens the calls of the tethers whose local end sends.
 *
 * - **Server.** Mutual TLS with the identity of the engine. [trustStore] holds the public key fingerprints of the engines
 *   that the registered fabrics allow as senders ([RemoteReceiver.senderFingerprint]); a key that is not in it is refused
 *   at the handshake. It is a store of its own, separate from the trust of the daemon and the management server, and it
 *   is emptied when the driver is created: nothing is allowed until a fabric is deployed.
 * - **Client.** One channel per remote address and key, opened on first use and reused; it trusts that key only and
 *   presents the identity of the engine. One call per tether.
 *
 * A call is accepted when the caller's key is the one the receiving tether names. A call that names an unknown fabric,
 * block or port is answered like one from a caller that is not allowed (an ERROR frame, then the end of the call), so
 * that a caller learns nothing about what runs here.
 */
public class RemoteTetherDriver(
    private val identity: Identity,
    private val trustStore: TrustStore,
    /** Folder for the trust stores of the channels (one file per remote key). */
    private val peersDir: Path,
    requestedPort: Int = 0,
    private val connectTimeout: java.time.Duration = java.time.Duration.ofSeconds(10),
    /** Finds the engine of a fabric for the tethers whose `remote` has no address; `null` if this engine cannot. */
    resolver: FabricResolver? = null,
    private val bindings: BindingResolver = BindingResolver.IDENTITY,
    private val options: RemoteTetherOptions = RemoteTetherOptions(),
) : AutoCloseable {
    private val typedCodec = WireCodec(TetherMode.TYPED)
    private val bytesCodec = WireCodec(TetherMode.BYTES)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val receivers = HashMap<String, RemoteReceiver>()
    private val senderRefs = HashMap<String, Int>()
    private val channels = ConcurrentHashMap<String, ManagedChannel>()
    private val addressCache: CachingFabricResolver? = resolver?.let { CachingFabricResolver(it, options.resolutionTtl, options.clock) }

    private val server: Server = NettyServerBuilder
        .forAddress(InetSocketAddress(requestedPort))
        .sslContext(TlsHelper.serverCredentials(identity, trustStore))
        .permitKeepAliveTime(1, TimeUnit.SECONDS)
        .permitKeepAliveWithoutCalls(true)
        .intercept(CallInterceptor)
        .addService(Service())
        .build()

    init {
        // allowed senders do not outlive the process: the fabrics that allowed them are gone
        for (entry in trustStore.list()) trustStore.remove(entry.fingerprint)
    }

    /** The port of the server; valid after [start]. */
    public val port: Int get() = server.port

    /** Starts serving. */
    public fun start(): RemoteTetherDriver {
        server.start()
        return this
    }

    /** The ports of the fabric [fabricId] (see [RemoteTetherPorts]). */
    public fun portsFor(fabricId: String): RemoteTetherPorts = object : RemoteTetherPorts {
        override fun register(receivers: List<RemoteReceiver>): AutoCloseable = this@RemoteTetherDriver.register(fabricId, receivers)

        override suspend fun connect(sender: RemoteSender): RemoteCall = this@RemoteTetherDriver.connect(sender)
    }

    /** Stops the server, closes the channels and ends the calls. */
    override fun close() {
        scope.cancel()
        for (channel in channels.values) channel.shutdownNow()
        channels.clear()
        server.shutdown()
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
    }

    // ---- receiving ----

    private fun register(fabricId: String, list: List<RemoteReceiver>): AutoCloseable {
        val keys = list.map { key(fabricId, it.block, it.port, it.index, it.senderFingerprint) }
        synchronized(lock) {
            for ((i, r) in list.withIndex()) {
                check(keys[i] !in receivers) { "tether ${r.tetherId}: the port '${r.block}.${r.port}' of fabric '$fabricId' already receives from this engine" }
            }
            for ((i, r) in list.withIndex()) {
                receivers[keys[i]] = r
                if (senderRefs.merge(r.senderFingerprint, 1, Int::plus) == 1) {
                    trustStore.add(TrustEntry(r.senderFingerprint, "fabric:$fabricId", TrustKind.ENGINE))
                }
            }
        }
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true)) {
                synchronized(lock) {
                    for ((i, r) in list.withIndex()) {
                        receivers.remove(keys[i])
                        if (senderRefs.merge(r.senderFingerprint, -1, Int::plus) == 0) {
                            senderRefs.remove(r.senderFingerprint)
                            trustStore.remove(r.senderFingerprint)
                        }
                    }
                }
            }
        }
    }

    private fun lookup(fabric: String, blockPort: String, peer: String?): RemoteReceiver? {
        peer ?: return null
        val parts = blockPort.split('/')
        if (parts.size !in 2..3) return null
        val index = if (parts.size == 3) (parts[2].toIntOrNull() ?: return null) else null
        return synchronized(lock) { receivers[key(fabric, parts[0], parts[1], index, peer)] }
    }

    private class CallInfo(val peer: String?, val fabric: String?, val blockPort: String?)

    private object CallInterceptor : ServerInterceptor {
        override fun <ReqT, RespT> interceptCall(call: ServerCall<ReqT, RespT>, headers: Metadata, next: ServerCallHandler<ReqT, RespT>): ServerCall.Listener<ReqT> {
            val session = call.attributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION)
            val peer = try {
                (session?.peerCertificates?.firstOrNull() as? X509Certificate)?.let { PublicKeyFingerprint.of(it) }
            } catch (e: javax.net.ssl.SSLPeerUnverifiedException) {
                null
            }
            val info = CallInfo(peer, headers.get(FABRIC_KEY), headers.get(BLOCK_PORT_KEY))
            return Contexts.interceptCall(Context.current().withValue(CALL, info), call, headers, next)
        }
    }

    private inner class Service : RemoteTetherServiceGrpcKt.RemoteTetherServiceCoroutineImplBase() {
        override fun exchange(requests: Flow<WireData>): Flow<WireData> {
            val info = CALL.get()
            return channelFlow {
                val receiver = info?.fabric?.let { f -> info.blockPort?.let { bp -> lookup(f, bp, info.peer) } }
                if (info == null || receiver == null || receiver.senderFingerprint != info.peer) {
                    val refusal = RemoteErrors.value(RemoteErrors.UNKNOWN_TARGET, "this engine has no tether that receives from this caller at ${info?.fabric}/${info?.blockPort}")
                    send(data(codecFor(TetherMode.TYPED).encode(cringle.wire.Error(0u, refusal))))
                    return@channelFlow
                }
                val codec = codecFor(receiver.mode)
                var reader: Job? = null
                val session = object : RemoteSession {
                    override suspend fun reply(frame: WireFrame) {
                        send(data(codec.encode(frame)))
                    }

                    override fun end() {
                        close()
                        reader?.cancel()
                    }
                }
                val inbound = receiver.accept(session)
                send(WireData.getDefaultInstance())
                val decoder = codec.newDecoder()
                var cause: Throwable? = null
                reader = launch {
                    try {
                        requests.collect { chunk ->
                            for (frame in decoder.feed(chunk.frame.toByteArray())) {
                                try {
                                    inbound.onFrame(frame)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    send(data(codec.encode(cringle.wire.Error(frame.correlationOrStreamId, RemoteErrors.value(e)))))
                                }
                            }
                        }
                    } catch (e: WireFormatException) {
                        cause = e
                        send(data(codec.encode(cringle.wire.Error(0u, RemoteErrors.value(RemoteErrors.FORMAT, "bad frame: ${e.message}")))))
                    } catch (e: Throwable) {
                        cause = e
                        throw e
                    } finally {
                        inbound.onEnded(cause)
                    }
                }
                reader.join()
            }
        }
    }

    private fun data(frame: ByteArray): WireData = WireData.newBuilder().setFrame(ByteString.copyFrom(frame)).build()

    private fun codecFor(mode: TetherMode): WireCodec = if (mode == TetherMode.BYTES) bytesCodec else typedCodec

    // ---- sending ----

    private suspend fun connect(sender: RemoteSender): RemoteCall {
        val address = sender.remote.address
        if (address != null) return connectFixed(sender, address)
        return ResolvingCall(sender).also { it.start() }
    }

    /**
     * A tether whose target is resolved at run time: finds the engine of the fabric in the registry, connects, and when the
     * connection ends or fails its health check, forgets the address and starts again with a growing wait. The first
     * connection is made in the background too: a target that is not there yet is no reason to fail the fabric.
     */
    private inner class ResolvingCall(private val sender: RemoteSender) : RemoteCall {
        @Volatile private var current: RemoteCall? = null
        private val closed = AtomicBoolean(false)
        private var job: Job? = null

        fun start() {
            job = scope.launch { supervise() }
        }

        private suspend fun supervise() {
            val cache = checkNotNull(addressCache) { "tether ${sender.tetherId}: this engine has no way to resolve a fabric" }
            val binding = bindings.bind(sender.tetherId, sender.remote.fabric)
            var failed = 0
            while (true) {
                try {
                    val address = cache.resolve(binding.bound)
                    val ended = CompletableDeferred<Throwable?>()
                    val toTarget = RemoteSender(sender.tetherId, sender.remote.copy(address = address, fabric = binding.bound), sender.schemaNamespace, sender.mode, object : RemoteInbound {
                        override suspend fun onFrame(frame: WireFrame) = sender.inbound.onFrame(frame)

                        override fun onEnded(cause: Throwable?) {
                            ended.complete(cause)
                        }
                    })
                    current = connectFixed(toTarget, address)
                    failed = 0
                    val cause = ended.await()
                    current = null
                    cache.invalidate(binding.bound)
                    sender.inbound.onInterrupted(cause)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // not found in the registry, not reachable, refused: ask again later
                    current = null
                    cache.invalidate(binding.bound)
                    failed++
                }
                options.clock.delay(options.backoff(maxOf(failed - 1, 0)))
            }
        }

        override suspend fun send(frame: WireFrame) {
            val call = current ?: throw RemoteUnavailableException("tether ${sender.tetherId}: the fabric '${sender.remote.fabric}' is not connected (it is being looked up again)")
            try {
                call.send(frame)
            } catch (e: TetherDeliveryException) {
                throw RemoteUnavailableException(e.message.orEmpty(), e)
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                job?.cancel()
                current?.close()
                current = null
            }
        }
    }

    private suspend fun connectFixed(sender: RemoteSender, address: String): RemoteCall {
        val remote = sender.remote.copy(address = address)
        val codec = codecFor(sender.mode)
        val where = "$address (key ${remote.fingerprint})"
        val headers = Metadata().apply {
            put(FABRIC_KEY, remote.fabric)
            put(BLOCK_PORT_KEY, remote.block + "/" + remote.port + (remote.index?.let { "/$it" } ?: ""))
        }
        val outgoing = Channel<WireData>(OUTGOING_BUFFER)
        val ready = CompletableDeferred<Unit>()
        val closedByUs = AtomicBoolean(false)
        val ended = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val healthFailure = AtomicBoolean(false)
        val channel = channelFor(remote)
        val stub = RemoteTetherServiceGrpcKt.RemoteTetherServiceCoroutineStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
        lateinit var job: Job
        job = scope.launch {
            val decoder = codec.newDecoder()
            var failure: Throwable? = null
            try {
                stub.exchange(outgoing.receiveAsFlow()).collect { chunk ->
                    if (chunk.frame.isEmpty) {
                        ready.complete(Unit)
                    } else {
                        for (frame in decoder.feed(chunk.frame.toByteArray())) {
                            if (!ready.isCompleted) {
                                // the first frame of a call is the refusal
                                val value = (frame as? cringle.wire.Error)?.value
                                val text = if (value != null) "refused (${RemoteErrors.code(value)}): ${RemoteErrors.message(value)}" else "refused"
                                ready.completeExceptionally(TetherWiringException("tether ${sender.tetherId}: cannot connect to $where: $text"))
                            } else {
                                try {
                                    sender.inbound.onFrame(frame)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // the network reports what it cannot take; nothing is left to do for the call
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                if (!healthFailure.get()) failure = e
            } catch (e: Throwable) {
                failure = e
            } finally {
                val reason = failure ?: TetherDeliveryException("tether ${sender.tetherId}: the other engine ended the call to $where")
                ready.completeExceptionally(reason)
                val lost = TetherDeliveryException(
                    "tether ${sender.tetherId}: the connection to $where was lost: " +
                        if (healthFailure.get()) "${options.healthFailures} checks of the connection in a row failed" else describe(failure),
                    failure,
                )
                ended.set(lost)
                outgoing.close(lost)
                if (!closedByUs.get()) sender.inbound.onEnded(lost)
            }
        }
        try {
            withTimeout(connectTimeout.toMillis()) { ready.await() }
        } catch (e: TimeoutCancellationException) {
            closedByUs.set(true)
            job.cancel()
            throw TetherWiringException("tether ${sender.tetherId}: cannot connect to $where: no answer within $connectTimeout")
        } catch (e: TetherWiringException) {
            closedByUs.set(true)
            job.cancel()
            throw e
        } catch (e: CancellationException) {
            closedByUs.set(true)
            job.cancel()
            throw e
        } catch (e: Throwable) {
            closedByUs.set(true)
            job.cancel()
            throw TetherWiringException("tether ${sender.tetherId}: cannot connect to $where: ${describe(e)}")
        }
        // the connection is checked from now on; a connection that fails its checks is cancelled and counts as lost
        val health = scope.launch {
            superviseHealth(
                options.clock, options.healthInterval, options.healthFailures,
                // the state of the connection itself, which keep-alive pings of the transport keep up to date: a receiver that
                // is slow (backpressure) is not a connection that is down
                probe = { channel.getState(false) == ConnectivityState.READY },
                onDown = {
                    healthFailure.set(true)
                    job.cancel()
                },
            )
        }
        return object : RemoteCall {
            override suspend fun send(frame: WireFrame) {
                ended.get()?.let { throw it }
                val bytes = try {
                    codec.encode(frame)
                } catch (e: WireFormatException) {
                    throw TetherDeliveryException("tether ${sender.tetherId}: a ${frame.frameType} frame cannot be sent: ${e.message}", e)
                }
                try {
                    outgoing.send(data(bytes))
                } catch (e: ClosedSendChannelException) {
                    throw ended.get() ?: TetherDeliveryException("tether ${sender.tetherId}: the call to $where has ended")
                }
            }

            override fun close() {
                closedByUs.set(true)
                health.cancel()
                outgoing.close()
                job.cancel()
            }
        }
    }

    private fun describe(e: Throwable?): String = when (e) {
        null -> "no reason given"
        is StatusException -> "${e.status.code}${e.status.description?.let { ": $it" }.orEmpty()}"
        is StatusRuntimeException -> "${e.status.code}${e.status.description?.let { ": $it" }.orEmpty()}"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun channelFor(remote: RemoteEndpoint): ManagedChannel = channels.computeIfAbsent("${remote.address}|${remote.fingerprint}") {
        val address = checkNotNull(remote.address)
        Files.createDirectories(peersDir)
        val store = TrustStore(peersDir.resolve("${remote.fingerprint}.json"))
        store.add(TrustEntry(remote.fingerprint, "remote tether", TrustKind.ENGINE, address = address))
        NettyChannelBuilder.forTarget(address)
            .sslContext(TlsHelper.channelCredentials(identity, store))
            // pings of the transport tell a silent peer from a quiet one (gRPC does not ping more often than every 10 seconds)
            .keepAliveTime(options.healthInterval.toMillis(), TimeUnit.MILLISECONDS)
            .keepAliveTimeout(options.healthInterval.toMillis(), TimeUnit.MILLISECONDS)
            .keepAliveWithoutCalls(true)
            .build()
    }

    private companion object {
        const val OUTGOING_BUFFER = 8

        val FABRIC_KEY: Metadata.Key<String> = Metadata.Key.of("cringle-fabric", Metadata.ASCII_STRING_MARSHALLER)
        val BLOCK_PORT_KEY: Metadata.Key<String> = Metadata.Key.of("cringle-block-port", Metadata.ASCII_STRING_MARSHALLER)
        val CALL: Context.Key<CallInfo> = Context.key("cringle-remote-tether-call")

        fun key(fabric: String, block: String, port: String, index: Int?, sender: String) = "$fabric|$block|$port|${index ?: "-"}|$sender"
    }
}
