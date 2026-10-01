// SPDX-License-Identifier: Apache-2.0

package cringle.router

import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.common.TlsHelper
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.TrustStore
import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.ServerInterceptors
import io.grpc.ServerServiceDefinition
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The per-call decision of [TrustInterceptor] (docs/trust.md, "Revoke"): who is served, which methods need a component,
 * and that a removal ends running calls and refuses new ones over the same connection and a resumed TLS session.
 * Runs on a server with one open stream method and three unary methods; all certificates are made by the test.
 */
class TrustInterceptorTest {
    @TempDir
    lateinit var dir: Path

    private val closeables = ArrayList<() -> Unit>()
    private var counter = 0

    @AfterEach
    fun tearDown() {
        closeables.reversed().forEach { runCatching { it() } }
    }

    private object Bytes : MethodDescriptor.Marshaller<ByteArray> {
        override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)
        override fun parse(stream: InputStream): ByteArray = stream.readAllBytes()
    }

    private fun method(name: String, type: MethodDescriptor.MethodType): MethodDescriptor<ByteArray, ByteArray> =
        MethodDescriptor.newBuilder(Bytes, Bytes).setType(type).setFullMethodName("test.Probe/$name").build()

    private val hold = method("Hold", MethodDescriptor.MethodType.BIDI_STREAMING)
    private val ping = method("Ping", MethodDescriptor.MethodType.UNARY)
    private val admin = method("Admin", MethodDescriptor.MethodType.UNARY)
    private val enroll = method("Enroll", MethodDescriptor.MethodType.UNARY)

    private class Rig(val port: Int, val trust: TrustStore, val identity: Identity, val peerSeen: AtomicReference<String?>)

    /** `Hold` stays open until the client or the interceptor ends it; `Enroll` is open (no check), `Admin` needs a component. */
    private fun rig(): Rig {
        val identity = Identity.loadOrCreate(dir.resolve("server"), ComponentKind.ROUTER.commonName("r"))
        val trust = TrustStore(dir.resolve("server-trust.json"))
        val seen = AtomicReference<String?>()
        val interceptor = TrustInterceptor(trust, open = setOf(enroll.fullMethodName), componentOnly = setOf(admin.fullMethodName))
        val echo = ServerCalls.UnaryMethod<ByteArray, ByteArray> { req, out -> out.onNext(req); out.onCompleted() }
        val service = ServerServiceDefinition.builder("test.Probe")
            .addMethod(
                hold,
                ServerCalls.asyncBidiStreamingCall<ByteArray, ByteArray> { out ->
                    object : StreamObserver<ByteArray> {
                        override fun onNext(value: ByteArray) = out.onNext(value)
                        override fun onError(t: Throwable) {}
                        override fun onCompleted() = out.onCompleted()
                    }
                },
            )
            .addMethod(ping, ServerCalls.asyncUnaryCall(echo))
            .addMethod(admin, ServerCalls.asyncUnaryCall(echo))
            .addMethod(
                enroll,
                ServerCalls.asyncUnaryCall<ByteArray, ByteArray> { req, out ->
                    seen.set(TrustInterceptor.PEER.get())
                    out.onNext(req)
                    out.onCompleted()
                },
            )
            .build()
        val server = NettyServerBuilder.forPort(0)
            .sslContext(TlsHelper.serverCredentials(identity, trust, requireTrustedClients = false))
            .addService(ServerInterceptors.intercept(service, interceptor))
            .build().start()
        closeables += { server.shutdownNow() }
        closeables += { interceptor.close() }
        return Rig(server.port, trust, identity, seen)
    }

    private fun peer(name: String, kind: ComponentKind = ComponentKind.ENGINE) = Identity.loadOrCreate(dir.resolve(name), kind.commonName(name))

    private fun serverTrust(rig: Rig): TrustStore {
        val store = TrustStore(dir.resolve("client-${counter++}-trust.json"))
        store.add(TrustEntry(rig.identity.publicKeyFingerprint, "r", TrustKind.ROUTER))
        return store
    }

    private fun channel(rig: Rig, me: Identity?): ManagedChannel {
        val channel = NettyChannelBuilder.forAddress("127.0.0.1", rig.port).sslContext(TlsHelper.channelCredentials(me, serverTrust(rig))).build()
        closeables += { channel.shutdownNow() }
        return channel
    }

    private fun unary(channel: ManagedChannel, m: MethodDescriptor<ByteArray, ByteArray>): Status.Code = try {
        ClientCalls.blockingUnaryCall(channel, m, CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS), "x".toByteArray())
        Status.Code.OK
    } catch (e: StatusRuntimeException) {
        e.status.code
    }

    /** An open `Hold` stream; [closedWith] is set when the server ends it. */
    private class Stream(val closedWith: AtomicReference<Status?>, val closed: CountDownLatch)

    private fun openStream(channel: ManagedChannel): Stream {
        val echoed = CountDownLatch(1)
        val closedWith = AtomicReference<Status?>()
        val closed = CountDownLatch(1)
        val send = ClientCalls.asyncBidiStreamingCall(
            channel.newCall(hold, CallOptions.DEFAULT),
            object : StreamObserver<ByteArray> {
                override fun onNext(value: ByteArray) = echoed.countDown()
                override fun onError(t: Throwable) { closedWith.set(Status.fromThrowable(t)); closed.countDown() }
                override fun onCompleted() { closed.countDown() }
            },
        )
        send.onNext("hello".toByteArray())
        assertTrue(echoed.await(30, TimeUnit.SECONDS), "the stream did not start")
        return Stream(closedWith, closed)
    }

    @Test
    fun onlyTrustedPeersAreServedAndAPeerWithoutCertificateIsNot() {
        val rig = rig()
        val engine = peer("e1")
        assertEquals(Status.Code.UNAUTHENTICATED, unary(channel(rig, engine), ping)) // certificate, but not trusted
        assertEquals(Status.Code.UNAUTHENTICATED, unary(channel(rig, null), ping)) // no certificate at all
        rig.trust.add(TrustEntry(engine.publicKeyFingerprint, "e1", TrustKind.ENGINE))
        assertEquals(Status.Code.OK, unary(channel(rig, engine), ping))
    }

    @Test
    fun openMethodsNeedNoTrustAndKnowTheFingerprintOfThePeer() {
        val rig = rig()
        val engine = peer("e1")
        assertEquals(Status.Code.OK, unary(channel(rig, engine), enroll))
        assertEquals(engine.publicKeyFingerprint, rig.peerSeen.get())
        assertEquals(Status.Code.OK, unary(channel(rig, null), enroll))
        assertNull(rig.peerSeen.get())
    }

    @Test
    fun methodsForComponentsRefuseTrustedPeersOfAnotherKind() {
        val rig = rig()
        val engine = peer("e1")
        val daemon = peer("d1", ComponentKind.DAEMON)
        rig.trust.add(TrustEntry(engine.publicKeyFingerprint, "e1", TrustKind.ENGINE))
        rig.trust.add(TrustEntry(daemon.publicKeyFingerprint, "d1", TrustKind.COMPONENT))
        assertEquals(Status.Code.PERMISSION_DENIED, unary(channel(rig, engine), admin))
        assertEquals(Status.Code.OK, unary(channel(rig, daemon), admin))
        assertEquals(Status.Code.OK, unary(channel(rig, engine), ping))
    }

    @Test
    fun removingTheTrustEndsRunningCallsAndRefusesNewOnesOverTheSameConnection() {
        val rig = rig()
        val engine = peer("e1")
        rig.trust.add(TrustEntry(engine.publicKeyFingerprint, "e1", TrustKind.ENGINE))
        val connection = channel(rig, engine)
        val stream = openStream(connection)
        assertEquals(Status.Code.OK, unary(connection, ping))

        rig.trust.remove(engine.publicKeyFingerprint)

        assertTrue(stream.closed.await(30, TimeUnit.SECONDS), "the running call was not ended")
        assertEquals(Status.Code.UNAUTHENTICATED, stream.closedWith.get()!!.code)
        assertEquals(Status.Code.UNAUTHENTICATED, unary(connection, ping))
    }

    @Test
    fun removingARouterEndsTheCallsOfTheEnginesItVouchedForAndNoOthers() {
        val rig = rig()
        val router = peer("r2", ComponentKind.ROUTER)
        val vouched = peer("e-remote")
        val direct = peer("e-local")
        rig.trust.add(TrustEntry(router.publicKeyFingerprint, "r2", TrustKind.ROUTER))
        rig.trust.add(TrustEntry(vouched.publicKeyFingerprint, "e-remote", TrustKind.ENGINE, origin = router.publicKeyFingerprint))
        rig.trust.add(TrustEntry(direct.publicKeyFingerprint, "e-local", TrustKind.ENGINE))
        val remoteStream = openStream(channel(rig, vouched))
        val localStream = openStream(channel(rig, direct))
        val routerStream = openStream(channel(rig, router))

        rig.trust.remove(router.publicKeyFingerprint)

        assertTrue(remoteStream.closed.await(30, TimeUnit.SECONDS))
        assertTrue(routerStream.closed.await(30, TimeUnit.SECONDS))
        assertEquals(Status.Code.UNAUTHENTICATED, remoteStream.closedWith.get()!!.code)
        assertEquals(Status.Code.UNAUTHENTICATED, routerStream.closedWith.get()!!.code)
        assertEquals(1L, localStream.closed.count, "a directly trusted engine must keep its call")
        assertEquals(Status.Code.OK, unary(channel(rig, direct), ping))
    }

    @Test
    fun aNewConnectionAfterTheRemovalIsRefusedEvenWhenTheTlsSessionCouldBeResumed() {
        val rig = rig()
        val engine = peer("e1")
        rig.trust.add(TrustEntry(engine.publicKeyFingerprint, "e1", TrustKind.ENGINE))
        // one SslContext for both channels: the JDK client keeps its session cache in it and offers the ticket again
        val context = TlsHelper.channelCredentials(engine, serverTrust(rig))
        fun fresh(): ManagedChannel =
            NettyChannelBuilder.forAddress("127.0.0.1", rig.port).sslContext(context).build().also { closeables += { it.shutdownNow() } }

        val first = fresh()
        assertEquals(Status.Code.OK, unary(first, ping))
        first.shutdownNow().awaitTermination(10, TimeUnit.SECONDS)

        rig.trust.remove(engine.publicKeyFingerprint)

        // refused by the handshake (UNAVAILABLE) or by the interceptor after a resumed session (UNAUTHENTICATED), never served
        val code = unary(fresh(), ping)
        assertTrue(code == Status.Code.UNAUTHENTICATED || code == Status.Code.UNAVAILABLE, code.toString())
    }
}
