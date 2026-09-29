// SPDX-License-Identifier: Apache-2.0

package cringle.router.users

import cringle.contract.UserRole
import cringle.router.MutableClock
import cringle.user.v1.CreateUserRequest
import cringle.user.v1.ListUsersRequest
import cringle.user.v1.Role
import cringle.user.v1.UserServiceGrpcKt
import cringle.user.v1.WhoAmIRequest
import io.grpc.CallCredentials
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AuthInterceptorTest {
    private val clock = MutableClock()
    private val users = UserManager(InMemoryUserStore(), clock)
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel

    @BeforeEach
    fun start() {
        server = NettyServerBuilder.forAddress(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            .addService(io.grpc.ServerInterceptors.intercept(UserGrpcService(users), AuthInterceptor(users, UserGrpcService.REQUIRED_PERMISSIONS)))
            .build().start()
        channel = NettyChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
    }

    @AfterEach
    fun stop() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    private fun stub(token: String?): UserServiceGrpcKt.UserServiceCoroutineStub {
        val stub = UserServiceGrpcKt.UserServiceCoroutineStub(channel)
        if (token == null) return stub
        return stub.withCallCredentials(object : CallCredentials() {
            override fun applyRequestMetadata(info: RequestInfo, executor: Executor, applier: MetadataApplier) {
                applier.apply(Metadata().also { it.put(AuthInterceptor.AUTHORIZATION, "Bearer $token") })
            }
        })
    }

    private fun tokenFor(role: UserRole, ttl: Duration? = null): String {
        val u = users.createUser("user-$role-${System.nanoTime()}", setOf(role))
        return users.createToken(u.user.id, "t", ttl).secret
    }

    private fun code(body: suspend () -> Unit): Status.Code = try {
        runBlocking { body() }
        Status.Code.OK
    } catch (e: StatusException) {
        e.status.code
    }

    @Test
    fun missingInvalidExpiredAndRevokedTokensAreRejectedAlike() {
        val expiring = tokenFor(UserRole.ADMIN, Duration.ofMinutes(1))
        val revoked = users.createUser("r", setOf(UserRole.ADMIN)).let { users.createToken(it.user.id, "t", null) }
        users.revokeToken(revoked.info.id)
        clock.advance(Duration.ofMinutes(2))
        val messages = listOf<String?>(null, "crt_bogus", expiring, revoked.secret).map { t ->
            val e = assertThrows<StatusException> { runBlocking { stub(t).whoAmI(WhoAmIRequest.getDefaultInstance()) } }
            assertEquals(Status.Code.UNAUTHENTICATED, e.status.code)
            e.status.description
        }
        assertEquals(1, messages.toSet().size)
    }

    @Test
    fun rolesAreEnforcedPerMethod() {
        val admin = tokenFor(UserRole.ADMIN)
        val operator = tokenFor(UserRole.OPERATOR)
        val viewer = tokenFor(UserRole.VIEWER)
        val endUser = tokenFor(UserRole.END_USER)
        val create = { t: String -> code { stub(t).createUser(CreateUserRequest.newBuilder().setName("new-${System.nanoTime()}").addRoles(Role.ROLE_VIEWER).build()) } }
        val list = { t: String -> code { stub(t).listUsers(ListUsersRequest.getDefaultInstance()) } }
        val whoami = { t: String -> code { stub(t).whoAmI(WhoAmIRequest.getDefaultInstance()) } }
        assertEquals(Status.Code.OK, create(admin))
        assertEquals(Status.Code.OK, list(admin))
        for (t in listOf(operator, viewer, endUser)) {
            assertEquals(Status.Code.PERMISSION_DENIED, create(t))
            assertEquals(Status.Code.PERMISSION_DENIED, list(t))
        }
        for (t in listOf(admin, operator, viewer, endUser)) assertEquals(Status.Code.OK, whoami(t))
    }

    @Test
    fun whoAmIReturnsTheCurrentUserWithEffectiveRoles() = runBlocking {
        users.createGroup("g", setOf(UserRole.VIEWER))
        val u = users.createUser("ann", setOf(UserRole.END_USER), setOf("g"))
        val token = users.createToken(u.user.id, "t", null).secret
        val me = stub(token).whoAmI(WhoAmIRequest.getDefaultInstance()).user
        assertEquals("ann", me.name)
        assertEquals(listOf(Role.ROLE_VIEWER, Role.ROLE_END_USER).toSet(), me.effectiveRolesList.toSet())
    }

    @Test
    fun adminCanManageTokensThroughTheService() = runBlocking {
        val admin = stub(tokenFor(UserRole.ADMIN))
        val user = admin.createUser(CreateUserRequest.newBuilder().setName("bob").addRoles(Role.ROLE_VIEWER).build()).user
        val created = admin.createToken(cringle.user.v1.CreateTokenRequest.newBuilder().setUserId(user.id).setLabel("cli").setTtlSeconds(3600).build())
        assertEquals("bob", stub(created.token).whoAmI(WhoAmIRequest.getDefaultInstance()).user.name)
        assertEquals(1, admin.listTokens(cringle.user.v1.ListTokensRequest.newBuilder().setUserId(user.id).build()).tokensCount)
        admin.revokeToken(cringle.user.v1.RevokeTokenRequest.newBuilder().setTokenId(created.info.id).build())
        assertEquals(Status.Code.UNAUTHENTICATED, code { stub(created.token).whoAmI(WhoAmIRequest.getDefaultInstance()) })
    }

    @Test
    fun methodsWithoutAPermissionAreClosed() {
        val closed = NettyServerBuilder.forAddress(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            .addService(io.grpc.ServerInterceptors.intercept(UserGrpcService(users), AuthInterceptor(users, emptyMap())))
            .build().start()
        val ch = NettyChannelBuilder.forAddress("127.0.0.1", closed.port).usePlaintext().build()
        try {
            val t = tokenFor(UserRole.ADMIN)
            val c = code { UserServiceGrpcKt.UserServiceCoroutineStub(ch).withCallCredentials(object : CallCredentials() {
                override fun applyRequestMetadata(info: RequestInfo, executor: Executor, applier: MetadataApplier) {
                    applier.apply(Metadata().also { it.put(AuthInterceptor.AUTHORIZATION, "Bearer $t") })
                }
            }).whoAmI(WhoAmIRequest.getDefaultInstance()) }
            assertEquals(Status.Code.PERMISSION_DENIED, c)
        } finally {
            ch.shutdownNow()
            closed.shutdownNow()
        }
    }
}
