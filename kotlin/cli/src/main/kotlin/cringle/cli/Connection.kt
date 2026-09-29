// SPDX-License-Identifier: Apache-2.0

package cringle.cli

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
import java.util.concurrent.TimeUnit

private val AUTHORIZATION: Metadata.Key<String> = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

/** The connection to the ManagementServer; it serves the management API and the user management API. */
internal class Connection(val server: String, token: String?) : AutoCloseable {
    private val channel: ManagedChannel = NettyChannelBuilder.forTarget(server).usePlaintext().maxInboundMessageSize(8 * 1024 * 1024).build()
    private val bearer: List<ClientInterceptor> = if (token == null) emptyList() else listOf(Bearer(token))

    val management: ManagementServiceCoroutineStub = ManagementServiceCoroutineStub(channel).withInterceptors(*bearer.toTypedArray())
    val users: UserServiceCoroutineStub = UserServiceCoroutineStub(channel).withInterceptors(*bearer.toTypedArray())

    override fun close() {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
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
