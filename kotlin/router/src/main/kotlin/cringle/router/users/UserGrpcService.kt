// SPDX-License-Identifier: Apache-2.0

package cringle.router.users

import com.google.protobuf.Timestamp
import cringle.contract.UserRole
import cringle.user.v1.CreateGroupRequest
import cringle.user.v1.CreateGroupResponse
import cringle.user.v1.CreateTokenRequest
import cringle.user.v1.CreateTokenResponse
import cringle.user.v1.CreateUserRequest
import cringle.user.v1.CreateUserResponse
import cringle.user.v1.DeleteUserRequest
import cringle.user.v1.DeleteUserResponse
import cringle.user.v1.ListGroupsRequest
import cringle.user.v1.ListGroupsResponse
import cringle.user.v1.ListTokensRequest
import cringle.user.v1.ListTokensResponse
import cringle.user.v1.ListUsersRequest
import cringle.user.v1.ListUsersResponse
import cringle.user.v1.Role
import cringle.user.v1.RevokeTokenRequest
import cringle.user.v1.RevokeTokenResponse
import cringle.user.v1.UserServiceGrpcKt
import cringle.user.v1.WhoAmIRequest
import cringle.user.v1.WhoAmIResponse
import io.grpc.Status
import java.time.Duration
import java.time.Instant

/** The gRPC service for user management. Serve it behind an [AuthInterceptor] configured with [REQUIRED_PERMISSIONS]. */
public class UserGrpcService(private val users: UserManager) : UserServiceGrpcKt.UserServiceCoroutineImplBase() {
    private fun <T> call(body: () -> T): T = try {
        body()
    } catch (e: UserException) {
        throw when (e.kind) {
            UserException.Kind.INVALID -> Status.INVALID_ARGUMENT
            UserException.Kind.NOT_FOUND -> Status.NOT_FOUND
            UserException.Kind.CONFLICT -> Status.FAILED_PRECONDITION
        }.withDescription(e.message).asException()
    }

    private fun toRole(r: UserRole): Role = when (r) {
        UserRole.ADMIN -> Role.ROLE_ADMIN
        UserRole.OPERATOR -> Role.ROLE_OPERATOR
        UserRole.VIEWER -> Role.ROLE_VIEWER
        UserRole.END_USER -> Role.ROLE_END_USER
    }

    private fun fromRoles(roles: List<Role>): Set<UserRole> = roles.map {
        when (it) {
            Role.ROLE_ADMIN -> UserRole.ADMIN
            Role.ROLE_OPERATOR -> UserRole.OPERATOR
            Role.ROLE_VIEWER -> UserRole.VIEWER
            Role.ROLE_END_USER -> UserRole.END_USER
            else -> throw Status.INVALID_ARGUMENT.withDescription("role must be specified").asException()
        }
    }.toSet()

    private fun ts(i: Instant): Timestamp = Timestamp.newBuilder().setSeconds(i.epochSecond).setNanos(i.nano).build()

    private fun proto(v: UserView): cringle.user.v1.User = cringle.user.v1.User.newBuilder()
        .setId(v.user.id).setName(v.user.name)
        .addAllRoles(v.user.roles.sorted().map(::toRole)).addAllGroups(v.user.groups.sorted())
        .addAllEffectiveRoles(v.effectiveRoles.sorted().map(::toRole)).build()

    private fun proto(t: TokenInfo): cringle.user.v1.TokenInfo = cringle.user.v1.TokenInfo.newBuilder()
        .setId(t.id).setUserId(t.userId).setLabel(t.label).setCreatedAt(ts(t.createdAt)).setRevoked(t.revoked)
        .also { b -> t.expiresAt?.let { b.setExpiresAt(ts(it)) } }.build()

    override suspend fun createUser(request: CreateUserRequest): CreateUserResponse =
        CreateUserResponse.newBuilder().setUser(proto(call { users.createUser(request.name, fromRoles(request.rolesList), request.groupsList.toSet()) })).build()

    override suspend fun listUsers(request: ListUsersRequest): ListUsersResponse =
        ListUsersResponse.newBuilder().addAllUsers(users.listUsers().map(::proto)).build()

    override suspend fun deleteUser(request: DeleteUserRequest): DeleteUserResponse {
        call { users.deleteUser(request.userId) }
        return DeleteUserResponse.getDefaultInstance()
    }

    override suspend fun createGroup(request: CreateGroupRequest): CreateGroupResponse {
        val g = call { users.createGroup(request.name, fromRoles(request.rolesList)) }
        return CreateGroupResponse.newBuilder()
            .setGroup(cringle.user.v1.Group.newBuilder().setName(g.name).addAllRoles(g.roles.sorted().map(::toRole))).build()
    }

    override suspend fun listGroups(request: ListGroupsRequest): ListGroupsResponse = ListGroupsResponse.newBuilder()
        .addAllGroups(users.listGroups().map { cringle.user.v1.Group.newBuilder().setName(it.name).addAllRoles(it.roles.sorted().map(::toRole)).build() })
        .build()

    override suspend fun createToken(request: CreateTokenRequest): CreateTokenResponse {
        val created = call {
            users.createToken(request.userId, request.label, request.ttlSeconds.takeIf { it > 0 }?.let { Duration.ofSeconds(it) })
        }
        return CreateTokenResponse.newBuilder().setToken(created.secret).setInfo(proto(created.info)).build()
    }

    override suspend fun listTokens(request: ListTokensRequest): ListTokensResponse =
        ListTokensResponse.newBuilder().addAllTokens(call { users.listTokens(request.userId) }.map(::proto)).build()

    override suspend fun revokeToken(request: RevokeTokenRequest): RevokeTokenResponse {
        call { users.revokeToken(request.tokenId) }
        return RevokeTokenResponse.getDefaultInstance()
    }

    override suspend fun whoAmI(request: WhoAmIRequest): WhoAmIResponse {
        val current = AuthInterceptor.CURRENT_USER.get() ?: throw Status.UNAUTHENTICATED.asException()
        val view = users.listUsers().firstOrNull { it.user.id == current.id } ?: throw Status.UNAUTHENTICATED.asException()
        return WhoAmIResponse.newBuilder().setUser(proto(view)).build()
    }

    public companion object {
        /** Permission per method for the [AuthInterceptor]. */
        public val REQUIRED_PERMISSIONS: Map<String, Permission> = mapOf(
            "cringle.user.v1.UserService/CreateUser" to Permission.MANAGE_USERS,
            "cringle.user.v1.UserService/ListUsers" to Permission.MANAGE_USERS,
            "cringle.user.v1.UserService/DeleteUser" to Permission.MANAGE_USERS,
            "cringle.user.v1.UserService/CreateGroup" to Permission.MANAGE_USERS,
            "cringle.user.v1.UserService/ListGroups" to Permission.MANAGE_USERS,
            "cringle.user.v1.UserService/CreateToken" to Permission.MANAGE_USERS,
            "cringle.user.v1.UserService/ListTokens" to Permission.MANAGE_USERS,
            "cringle.user.v1.UserService/RevokeToken" to Permission.MANAGE_USERS,
            "cringle.user.v1.UserService/WhoAmI" to Permission.AUTHENTICATED,
        )
    }
}
