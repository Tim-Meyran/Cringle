// SPDX-License-Identifier: Apache-2.0

package cringle.router

import cringle.common.Identity
import cringle.common.PublicKeyFingerprint
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.TrustStore
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.ForwardingServerCall
import io.grpc.Grpc
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.StatusException
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLPeerUnverifiedException

/** What a router needs to run with TLS: its own identity and the peers it trusts (see `docs/trust.md`). */
public class RouterTls(public val identity: Identity, public val trustStore: TrustStore)

/**
 * Enrollment of local engines (Architecture 5.1, owner decision: one-time secret from the daemon).
 *
 * The daemon announces an engine with [prepare] (the SHA-256 of a secret it hands to the engine). The engine then
 * registers with the secret and its certificate; [enroll] binds the fingerprint of its key to the engine id by an entry
 * in the trust store (`ENGINE`, direct, named like the id) and uses up the secret. Every later registration of the same
 * id has to present the same key, without a secret. Announcements are kept in memory: a router that restarts forgets
 * them and the daemon announces again.
 */
public class Enrollment(private val trustStore: TrustStore) {
    private val pending = ConcurrentHashMap<String, ByteArray>()

    /** Announces that the engine [engineId] will register with the secret whose SHA-256 is [secretHash]. */
    public fun prepare(engineId: String, secretHash: ByteArray) {
        if (!idPattern.matches(engineId)) throw StatusException(Status.INVALID_ARGUMENT.withDescription("engine id must match ${idPattern.pattern}"))
        if (secretHash.size != 32) throw StatusException(Status.INVALID_ARGUMENT.withDescription("enrollment_secret_hash must be the 32 bytes of a SHA-256"))
        pending[engineId] = secretHash
    }

    /**
     * Checks a registration and returns the fingerprint of the engine key. [peerFingerprint] is the key the TLS peer
     * proved it has; it has to be the key of [certificateDer]. Every refusal is PERMISSION_DENIED with a message that
     * does not tell a wrong secret from a used one.
     */
    public fun enroll(engineId: String, certificateDer: ByteArray, secret: ByteArray, peerFingerprint: String?, managementAddress: String): String {
        val certificate = try {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificateDer)) as X509Certificate
        } catch (e: Exception) {
            throw denied("certificate is missing or not an X.509 certificate")
        }
        val fingerprint = PublicKeyFingerprint.of(certificate)
        if (peerFingerprint != fingerprint) throw denied("the key of the certificate is not the key of the connection")
        try {
            certificate.checkValidity()
        } catch (e: java.security.cert.CertificateException) {
            throw denied("certificate is not valid now")
        }
        synchronized(this) {
            val bound = trustStore.list().firstOrNull { it.kind == TrustKind.ENGINE && it.origin == null && it.name == engineId }
            if (bound != null) {
                if (bound.fingerprint != fingerprint) throw denied("engine id '$engineId' is bound to a different key")
                return fingerprint
            }
            val expected = pending[engineId]
            val matches = expected != null && secret.isNotEmpty() && MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(secret))
            if (!matches) throw denied("enrollment secret is missing, wrong or already used")
            pending.remove(engineId)
            trustStore.add(TrustEntry(fingerprint, engineId, TrustKind.ENGINE, managementAddress.ifBlank { null }))
            return fingerprint
        }
    }

    private fun denied(reason: String) = StatusException(Status.PERMISSION_DENIED.withDescription(reason))

    private companion object {
        val idPattern = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

/**
 * Decides for every call of a TLS server whom it serves, by the public key fingerprint of the TLS peer against the
 * [trustStore]. This holds for a resumed TLS session as well (which the TLS layer cannot revoke, see `docs/trust.md`),
 * and a peer that is removed from the trust store later loses its running calls: they are closed with UNAUTHENTICATED,
 * and every new call over the same connection fails the same way. gRPC cannot close the TCP connection of a server.
 *
 * - Methods in [open] are not checked here (the service decides: token, enrollment secret). The fingerprint of the
 *   peer, if it has a certificate, is available as [PEER].
 * - Methods in [componentOnly] need a peer that is trusted as `COMPONENT`; all others need any trusted peer.
 */
public class TrustInterceptor(
    private val trustStore: TrustStore,
    private val open: Set<String>,
    private val componentOnly: Set<String> = emptySet(),
) : ServerInterceptor, AutoCloseable {
    private val running: MutableSet<TrackedCall<*, *>> = ConcurrentHashMap.newKeySet()
    private val subscription = trustStore.onChange { cutUntrusted() }

    override fun <ReqT, RespT> interceptCall(call: ServerCall<ReqT, RespT>, headers: Metadata, next: ServerCallHandler<ReqT, RespT>): ServerCall.Listener<ReqT> {
        val method = call.methodDescriptor.fullMethodName
        val peer = peerFingerprint(call)
        if (method in open) return Contexts.interceptCall(Context.current().withValue(PEER, peer), call, headers, next)
        if (peer == null) return reject(call, Status.UNAUTHENTICATED.withDescription("a client certificate is required"))
        val entry = trustStore.list().firstOrNull { it.fingerprint == peer }
            ?: return reject(call, Status.UNAUTHENTICATED.withDescription("the peer is not trusted"))
        if (method in componentOnly && entry.kind != TrustKind.COMPONENT) {
            return reject(call, Status.PERMISSION_DENIED.withDescription("only a trusted component may call this method"))
        }
        val tracked = TrackedCall(call, peer, running)
        running += tracked
        return Contexts.interceptCall(Context.current().withValue(PEER, peer), tracked, headers, next)
    }

    private fun cutUntrusted() {
        for (call in running.toList()) if (!trustStore.isTrusted(call.peer)) call.cut()
    }

    /** Stops listening to the trust store. */
    override fun close() {
        subscription.close()
    }

    private fun peerFingerprint(call: ServerCall<*, *>): String? {
        val session = call.attributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION) ?: return null
        return try {
            (session.peerCertificates.firstOrNull() as? X509Certificate)?.let { PublicKeyFingerprint.of(it) }
        } catch (e: SSLPeerUnverifiedException) {
            null
        }
    }

    private fun <ReqT, RespT> reject(call: ServerCall<ReqT, RespT>, status: Status): ServerCall.Listener<ReqT> {
        call.close(status, Metadata())
        return object : ServerCall.Listener<ReqT>() {}
    }

    /** A call that can be closed by the interceptor at any time; after that, what the service sends is dropped. */
    private class TrackedCall<ReqT, RespT>(
        delegate: ServerCall<ReqT, RespT>,
        val peer: String,
        private val running: MutableSet<TrackedCall<*, *>>,
    ) : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(delegate) {
        private val closed = AtomicBoolean(false)

        override fun close(status: Status, trailers: Metadata) {
            running -= this
            if (closed.compareAndSet(false, true)) super.close(status, trailers)
        }

        fun cut() {
            running -= this
            if (closed.compareAndSet(false, true)) {
                try {
                    delegate().close(Status.UNAUTHENTICATED.withDescription("trust in the peer was withdrawn"), Metadata())
                } catch (_: RuntimeException) {
                    // the call ended by itself in the meantime
                }
            }
        }

        override fun sendMessage(message: RespT) {
            if (!closed.get()) super.sendMessage(message)
        }

        override fun sendHeaders(headers: Metadata) {
            if (!closed.get()) super.sendHeaders(headers)
        }
    }

    public companion object {
        /** The fingerprint of the TLS peer of the current call, `null` without a client certificate. */
        public val PEER: Context.Key<String?> = Context.key("cringle-peer-fingerprint")

        /** The full names of the methods of [descriptor]'s service, for the sets [open] and [componentOnly]. */
        public fun methodNames(methods: Collection<MethodDescriptor<*, *>>): Set<String> = methods.map { it.fullMethodName }.toSet()
    }
}
