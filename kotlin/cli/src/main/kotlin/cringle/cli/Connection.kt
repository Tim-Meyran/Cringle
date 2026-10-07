// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import cringle.common.PublicKeyFingerprint
import cringle.common.TlsHelper
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.TrustStore
import cringle.management.v1.ManagementServiceGrpcKt.ManagementServiceCoroutineStub
import cringle.user.v1.UserServiceGrpcKt.UserServiceCoroutineStub
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import java.nio.file.Files
import java.util.concurrent.TimeUnit

private val AUTHORIZATION: Metadata.Key<String> = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

/**
 * The connection to the ManagementServer; it serves the management API and the user management API. The channel is TLS 1.3
 * and pinned: the server is accepted only if the fingerprint of its key is [fingerprint] (Architecture 5.1). There is no
 * client certificate; the user is authenticated by the token.
 */
internal class Connection(val server: String, token: String?, fingerprint: String) : AutoCloseable {
    private val workDir = Files.createTempDirectory("cringle-cli-trust")
    private val channel: ManagedChannel = NettyChannelBuilder.forTarget(server)
        .sslContext(TlsHelper.channelCredentials(null, pinned(server, fingerprint)))
        .maxInboundMessageSize(8 * 1024 * 1024)
        .build()
    private val bearer: List<ClientInterceptor> = if (token == null) emptyList() else listOf(Bearer(token))

    val management: ManagementServiceCoroutineStub = ManagementServiceCoroutineStub(channel).withInterceptors(*bearer.toTypedArray())
    val users: UserServiceCoroutineStub = UserServiceCoroutineStub(channel).withInterceptors(*bearer.toTypedArray())

    private fun pinned(server: String, fingerprint: String): TrustStore {
        val text = fingerprint.trim().lowercase()
        if (!PublicKeyFingerprint.pattern.matches(text)) {
            workDir.toFile().deleteRecursively()
            throw UsageException("the fingerprint of the server is not a SHA-256 fingerprint (64 hexadecimal characters): '$fingerprint'")
        }
        // a trust store with the one entry the connection is pinned to; it lives as long as the connection
        return TrustStore(workDir.resolve("trust.json")).also { it.add(TrustEntry(text, server, TrustKind.SERVER, address = server)) }
    }

    override fun close() {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        workDir.toFile().deleteRecursively()
    }

    private class Bearer(private val token: String) : ClientInterceptor {
        override fun <Q, R> interceptCall(method: MethodDescriptor<Q, R>, options: CallOptions, next: Channel): ClientCall<Q, R> =
            object : ForwardingClientCall.SimpleForwardingClientCall<Q, R>(next.newCall(method, options)) {
                override fun start(listener: Listener<R>, headers: Metadata) {
                    headers.put(AUTHORIZATION, "Bearer $token")
                    super.start(listener, headers)
                }
            }
    }
}
