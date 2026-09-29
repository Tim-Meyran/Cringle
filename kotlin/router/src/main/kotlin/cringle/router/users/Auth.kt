// SPDX-License-Identifier: Apache-2.0

package cringle.router.users

import cringle.contract.AuthenticatedUser
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status

/**
 * Server-side authentication and authorization for gRPC services (used by ManagementServer and Repository).
 *
 * A call needs `authorization: Bearer <token>`. Missing, invalid, revoked and expired tokens all fail with the same
 * UNAUTHENTICATED status, so the answer reveals nothing about users. [required] maps the full method name
 * (`package.Service/Method`) to the permission it needs; a method that is not listed and not in [open] is rejected, so
 * new methods are closed until someone decides who may call them. Tokens are never logged.
 */
public class AuthInterceptor(
    private val users: UserManager,
    private val required: Map<String, Permission>,
    private val open: Set<String> = emptySet(),
) : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val method = call.methodDescriptor.fullMethodName
        if (method in open) return next.startCall(call, headers)
        val permission = required[method]
        if (permission == null) return reject(call, Status.PERMISSION_DENIED.withDescription("method is not available"))
        val header = headers.get(AUTHORIZATION)
        val token = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
        val user = token?.takeIf { it.isNotEmpty() }?.let { users.authenticate(it) }
            ?: return reject(call, Status.UNAUTHENTICATED.withDescription("missing or invalid credentials"))
        if (permission !in users.permissions(user)) {
            return reject(call, Status.PERMISSION_DENIED.withDescription("insufficient rights"))
        }
        return Contexts.interceptCall(Context.current().withValue(CURRENT_USER, user), call, headers, next)
    }

    private fun <ReqT, RespT> reject(call: ServerCall<ReqT, RespT>, status: Status): ServerCall.Listener<ReqT> {
        call.close(status, Metadata())
        return object : ServerCall.Listener<ReqT>() {}
    }

    public companion object {
        /** The metadata key of the credentials. */
        public val AUTHORIZATION: Metadata.Key<String> = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

        /** The authenticated user of the current call; set by the interceptor. */
        public val CURRENT_USER: Context.Key<AuthenticatedUser> = Context.key("cringle-user")
    }
}
