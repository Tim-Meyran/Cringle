// SPDX-License-Identifier: Apache-2.0

package cringle.common

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * TLS for gRPC without a certificate authority (Architecture chapters 5 and 6): builds the netty `SslContext` for a
 * server or a client from an [Identity] and a [TrustStore].
 *
 * - TLS 1.3 only; older protocols are refused.
 * - The server demands a client certificate.
 * - A peer is accepted if, and only if, the [PublicKeyFingerprint] of its key is in the trust store and its certificate
 *   is valid now. Host names and certificate chains are not checked: trust is in the key, not in a name or an issuer.
 * - A peer that is refused is logged (logger `cringle.common.TlsHelper`, level WARNING) with its fingerprint and the
 *   reason, never with the content of its certificate.
 *
 * Use the result with `NettyServerBuilder.sslContext(...)` and `NettyChannelBuilder.sslContext(...)`.
 */
public object TlsHelper {
    private val log = Logger.getLogger("cringle.common.TlsHelper")
    private const val PROTOCOL = "TLSv1.3"

    /** How long a TLS session can be resumed without the certificate check; see [boundedSessions]. */
    public const val SESSION_TIMEOUT_SECONDS: Long = 60

    /** The context of a server that presents [identity] and demands a certificate that [trustStore] trusts. */
    public fun serverCredentials(identity: Identity, trustStore: TrustStore): SslContext =
        GrpcSslContexts.configure(
            SslContextBuilder.forServer(identity.keyPair.private, identity.certificate)
                .trustManager(TrustStoreTrustManager(trustStore))
                .clientAuth(ClientAuth.REQUIRE)
                .protocols(PROTOCOL)
                .boundedSessions(),
            SslProvider.JDK,
        ).build()

    /**
     * The context of a client that accepts servers [trustStore] trusts. With [identity] it presents its certificate;
     * without one it can only talk to servers that do not demand a client certificate.
     */
    public fun channelCredentials(identity: Identity?, trustStore: TrustStore): SslContext {
        val builder = GrpcSslContexts.forClient().trustManager(TrustStoreTrustManager(trustStore)).protocols(PROTOCOL).boundedSessions()
        if (identity != null) builder.keyManager(identity.keyPair.private, identity.certificate)
        return GrpcSslContexts.configure(builder, SslProvider.JDK).build()
    }

    // A resumed TLS session skips the certificate check, and the JDK issues tickets that the server cannot take back. The
    // timeout therefore is the time in which a peer that was removed from the trust store can still resume an old session.
    private fun SslContextBuilder.boundedSessions(): SslContextBuilder = sessionTimeout(SESSION_TIMEOUT_SECONDS)

/** Accepts a peer by the fingerprint of its key and the validity of its certificate, nothing else. */
    private class TrustStoreTrustManager(private val trustStore: TrustStore) : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = check(chain, "client")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = check(chain, "server")

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = check(chain, "client")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = check(chain, "server")

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = check(chain, "client")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = check(chain, "server")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        private fun check(chain: Array<X509Certificate>, role: String) {
            val leaf = chain.firstOrNull() ?: reject(null, role, "no certificate")
            val fingerprint = PublicKeyFingerprint.of(leaf)
            if (!trustStore.isTrusted(fingerprint)) reject(fingerprint, role, "public key is not in the trust store")
            try {
                leaf.checkValidity()
            } catch (e: java.security.cert.CertificateExpiredException) {
                reject(fingerprint, role, "certificate has expired")
            } catch (e: java.security.cert.CertificateNotYetValidException) {
                reject(fingerprint, role, "certificate is not valid yet")
            }
            // the certificate is self-signed: it must carry a valid signature of its own key
            try {
                leaf.verify(leaf.publicKey)
            } catch (e: Exception) {
                reject(fingerprint, role, "certificate signature is invalid")
            }
        }

        private fun reject(fingerprint: String?, role: String, reason: String): Nothing {
            log.log(Level.WARNING, "TLS peer ({0}) rejected: {1}; fingerprint {2}", arrayOf(role, reason, fingerprint ?: "none"))
            throw CertificateException("peer rejected: $reason")
        }
    }
}
