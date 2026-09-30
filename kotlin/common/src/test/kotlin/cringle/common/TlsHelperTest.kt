// SPDX-License-Identifier: Apache-2.0

package cringle.common

import io.grpc.CallOptions
import io.grpc.Grpc
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.ServerServiceDefinition
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.grpc.stub.ClientCalls
import io.grpc.stub.ServerCalls
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** Real gRPC servers and channels with generated identities; every certificate is made in a temporary directory. */
class TlsHelperTest {
    @TempDir
    lateinit var dir: Path

    private val closeables = ArrayList<() -> Unit>()
    private val protocol = AtomicReference<String?>()

    private val method: MethodDescriptor<ByteArray, ByteArray> = MethodDescriptor.newBuilder<ByteArray, ByteArray>()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("test.Echo/Say")
        .setRequestMarshaller(Bytes)
        .setResponseMarshaller(Bytes)
        .build()

    private object Bytes : MethodDescriptor.Marshaller<ByteArray> {
        override fun stream(value: ByteArray) = ByteArrayInputStream(value)

        override fun parse(stream: java.io.InputStream): ByteArray = stream.readAllBytes()
    }

    private class Peer(val identity: Identity, val trust: TrustStore) {
        val fingerprint get() = identity.publicKeyFingerprint
    }

    private fun peer(name: String, validity: Duration = Identity.VALIDITY) =
        Peer(Identity.loadOrCreate(dir.resolve(name), name, validity), TrustStore(dir.resolve("$name-trust.json")))

    private fun trust(who: Peer, other: Peer) =
        who.trust.add(TrustEntry(other.fingerprint, "peer", TrustKind.COMPONENT, addedAt = java.time.Instant.parse("2026-01-01T00:00:00Z")))

    @AfterEach
    fun tearDown() {
        closeables.reversed().forEach { runCatching { it() } }
    }

    private fun serve(context: SslContext): Int {
        val capture = object : ServerInterceptor {
            override fun <A, B> interceptCall(call: ServerCall<A, B>, headers: Metadata, next: ServerCallHandler<A, B>): ServerCall.Listener<A> {
                protocol.set(call.attributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION)?.protocol)
                return next.startCall(call, headers)
            }
        }
        val service = ServerServiceDefinition.builder("test.Echo")
            .addMethod(method, ServerCalls.asyncUnaryCall<ByteArray, ByteArray> { request, observer -> observer.onNext(request); observer.onCompleted() })
            .build()
        val server = NettyServerBuilder.forAddress(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            .sslContext(context)
            .addService(ServerInterceptors.intercept(service, capture))
            .build()
            .start()
        closeables += { server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS) }
        return server.port
    }

    private fun call(port: Int, context: SslContext): String {
        val channel = NettyChannelBuilder.forAddress("127.0.0.1", port).sslContext(context).build()
        try {
            val options = CallOptions.DEFAULT.withDeadlineAfter(20, TimeUnit.SECONDS)
            return String(ClientCalls.blockingUnaryCall(channel, method, options, "hello".toByteArray()))
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun assertRefused(port: Int, context: SslContext) {
        val e = assertThrows<StatusRuntimeException> { call(port, context) }
        assertEquals(Status.Code.UNAVAILABLE, e.status.code, e.toString())
    }

    private fun captureLog(): MutableList<LogRecord> {
        val records = ArrayList<LogRecord>()
        val logger = Logger.getLogger("cringle.common.TlsHelper")
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                synchronized(records) { records += record }
            }

            override fun flush() {}

            override fun close() {}
        }
        logger.addHandler(handler)
        closeables += { logger.removeHandler(handler) }
        return records
    }

    @Test
    fun peersThatTrustEachOtherConnectWithTls13() {
        val server = peer("server")
        val client = peer("client")
        trust(server, client)
        trust(client, server)
        val port = serve(TlsHelper.serverCredentials(server.identity, server.trust))
        assertEquals("hello", call(port, TlsHelper.channelCredentials(client.identity, client.trust)))
        assertEquals("TLSv1.3", protocol.get())
    }

    @Test
    fun aServerThatTheClientDoesNotTrustIsRefused() {
        val server = peer("server")
        val client = peer("client")
        trust(server, client)
        val log = captureLog()
        val port = serve(TlsHelper.serverCredentials(server.identity, server.trust))
        assertRefused(port, TlsHelper.channelCredentials(client.identity, client.trust))
        val text = synchronized(log) { log.joinToString("\n") { java.text.MessageFormat.format(it.message, *it.parameters) } }
        assertTrue(server.fingerprint in text, text)
        assertTrue("not in the trust store" in text, text)
        assertFalse("BEGIN CERTIFICATE" in text || "CN=" in text, "no certificate content in the log: $text")
        assertTrue(log.all { it.level == Level.WARNING })
    }

    @Test
    fun aClientThatTheServerDoesNotTrustIsRefused() {
        val server = peer("server")
        val client = peer("client")
        trust(client, server)
        val log = captureLog()
        val port = serve(TlsHelper.serverCredentials(server.identity, server.trust))
        assertRefused(port, TlsHelper.channelCredentials(client.identity, client.trust))
        assertTrue(synchronized(log) { log.any { client.fingerprint in it.parameters.joinToString() } }, "the refused client is logged with its fingerprint")
    }

    @Test
    fun aClientWithoutACertificateIsRefused() {
        val server = peer("server")
        val client = peer("client")
        trust(client, server)
        val port = serve(TlsHelper.serverCredentials(server.identity, server.trust))
        assertRefused(port, TlsHelper.channelCredentials(null, client.trust))
    }

    @Test
    fun anExpiredClientCertificateIsRefused() {
        val server = peer("server")
        val client = peer("client", Duration.ofSeconds(-120))
        trust(server, client)
        trust(client, server)
        val log = captureLog()
        val port = serve(TlsHelper.serverCredentials(server.identity, server.trust))
        assertRefused(port, TlsHelper.channelCredentials(client.identity, client.trust))
        assertTrue(synchronized(log) { log.any { "expired" in it.parameters.joinToString() } })
    }

    @Test
    fun anExpiredServerCertificateIsRefused() {
        val server = peer("server", Duration.ofSeconds(-120))
        val client = peer("client")
        trust(server, client)
        trust(client, server)
        val port = serve(TlsHelper.serverCredentials(server.identity, server.trust))
        assertRefused(port, TlsHelper.channelCredentials(client.identity, client.trust))
    }

    @Test
    fun aRenewedCertificateOfTheSameKeyIsStillAccepted() {
        val server = peer("server")
        val client = peer("client")
        trust(server, client)
        trust(client, server)
        val before = server.identity.certificateFingerprint
        server.identity.renew()
        assertTrue(before != server.identity.certificateFingerprint)
        val port = serve(TlsHelper.serverCredentials(server.identity, server.trust))
        assertEquals("hello", call(port, TlsHelper.channelCredentials(client.identity, client.trust)))
        client.identity.renew()
        assertEquals("hello", call(port, TlsHelper.channelCredentials(client.identity, client.trust)))
    }

    @Test
    fun tls12AndOlderAreRefused() {
        val server = peer("server")
        val client = peer("client")
        trust(server, client)
        val port = serve(TlsHelper.serverCredentials(server.identity, server.trust))
        // a client that trusts anything and offers TLS 1.2 only
        val old = GrpcSslContexts.configure(
            GrpcSslContexts.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .keyManager(client.identity.keyPair.private, client.identity.certificate)
                .protocols("TLSv1.2"),
        ).build()
        assertRefused(port, old)
    }

    @Test
    fun trustRemovedFromTheStoreTakesEffectForNewSessions() {
        val server = peer("server")
        val client = peer("client")
        trust(server, client)
        trust(client, server)
        val port = serve(TlsHelper.serverCredentials(server.identity, server.trust))
        val context = TlsHelper.channelCredentials(client.identity, client.trust)
        assertEquals("hello", call(port, context))
        server.trust.remove(client.fingerprint)
        // a new context has no session to resume; an old session can be resumed for TlsHelper.SESSION_TIMEOUT_SECONDS
        assertRefused(port, TlsHelper.channelCredentials(client.identity, client.trust))
    }
}
