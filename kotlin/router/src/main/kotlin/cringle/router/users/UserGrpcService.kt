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
import cringle.user.v1.RoleScopeResponse
import cringle.user.v1.UserServiceGrpcKt
import cringle.user.v1.WhoAmIRequest
import cringle.user.v1.WhoAmIResponse
import io.grpc.Status
import java.time.Duration
import java.time.Instant

/** The gRPC service for user management. Serve it behind an [AuthInterceptor] configured with [REQUIRED_PERMISSIONS]. */
public class UserGrpcService(
    private val users: UserManager,
    /** The key pair with which this site signs federated tokens (the identity key of the management server); without it nothing can be issued (#295). */
    private val signingKey: java.security.KeyPair? = null,
) : UserServiceGrpcKt.UserServiceCoroutineImplBase() {
    private fun <T> call(body: () -> T): T = try {
        body()
    } catch (e: UserException) {
        throw when (e.kind) {
            UserException.Kind.INVALID -> Status.INVALID_ARGUMENT
            UserException.Kind.NOT_FOUND -> Status.NOT_FOUND
            UserException.Kind.CONFLICT -> Status.FAILED_PRECONDITION
        }.withDescription(e.message).asException()
    }

    /** The user functions need `MANAGE_USERS` globally or for `function:users`; the interceptor only knew that the caller has it somewhere. */
    private fun requireUsers() {
        val caller = AuthInterceptor.CURRENT_USER.get() ?: return
        if (!users.allowed(caller, Permission.MANAGE_USERS, USERS_SCOPE)) {
            throw Status.PERMISSION_DENIED.withDescription("insufficient rights for $USERS_SCOPE").asException()
        }
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

    private fun toProto(s: Scope): cringle.user.v1.Scope = cringle.user.v1.Scope.newBuilder().setName(s.name).setKind(
        when (s.kind) {
            ScopeKind.GLOBAL -> cringle.user.v1.ScopeKind.SCOPE_KIND_GLOBAL
            ScopeKind.MACHINE -> cringle.user.v1.ScopeKind.SCOPE_KIND_MACHINE
            ScopeKind.PROJECT -> cringle.user.v1.ScopeKind.SCOPE_KIND_PROJECT
            ScopeKind.FABRIC -> cringle.user.v1.ScopeKind.SCOPE_KIND_FABRIC
            ScopeKind.FUNCTION -> cringle.user.v1.ScopeKind.SCOPE_KIND_FUNCTION
        },
    ).build()

    private fun fromProto(s: cringle.user.v1.Scope): Scope {
        val kind = when (s.kind) {
            cringle.user.v1.ScopeKind.SCOPE_KIND_GLOBAL -> ScopeKind.GLOBAL
            cringle.user.v1.ScopeKind.SCOPE_KIND_MACHINE -> ScopeKind.MACHINE
            cringle.user.v1.ScopeKind.SCOPE_KIND_PROJECT -> ScopeKind.PROJECT
            cringle.user.v1.ScopeKind.SCOPE_KIND_FABRIC -> ScopeKind.FABRIC
            cringle.user.v1.ScopeKind.SCOPE_KIND_FUNCTION -> ScopeKind.FUNCTION
            else -> throw Status.INVALID_ARGUMENT.withDescription("scope kind must be specified").asException()
        }
        return try {
            Scope(kind, s.name)
        } catch (e: IllegalArgumentException) {
            throw Status.INVALID_ARGUMENT.withDescription(e.message).asException()
        }
    }

    private fun toProto(a: RoleAssignment): cringle.user.v1.RoleAssignment =
        cringle.user.v1.RoleAssignment.newBuilder().setRole(toRole(a.role)).setScope(toProto(a.scope)).build()

    private fun ts(i: Instant): Timestamp = Timestamp.newBuilder().setSeconds(i.epochSecond).setNanos(i.nano).build()

    private fun proto(v: UserView): cringle.user.v1.User = cringle.user.v1.User.newBuilder()
        .setId(v.user.id).setName(v.user.name)
        .addAllRoles(v.user.roles.sorted().map(::toRole)).addAllGroups(v.user.groups.sorted())
        .addAllEffectiveRoles(v.effectiveRoles.sorted().map(::toRole))
        .addAllScopedRoles(v.user.scoped.sortedBy { it.scope.encode() }.map(::toProto))
        .addAllEffectiveScopedRoles(v.effectiveScoped.sortedBy { it.scope.encode() }.map(::toProto)).build()

    private fun proto(g: Group): cringle.user.v1.Group = cringle.user.v1.Group.newBuilder().setName(g.name).addAllRoles(g.roles.sorted().map(::toRole))
        .addAllScopedRoles(g.scoped.sortedBy { it.scope.encode() }.map(::toProto)).build()

    private fun proto(t: TokenInfo): cringle.user.v1.TokenInfo = cringle.user.v1.TokenInfo.newBuilder()
        .setId(t.id).setUserId(t.userId).setLabel(t.label).setCreatedAt(ts(t.createdAt)).setRevoked(t.revoked)
        .also { b -> t.expiresAt?.let { b.setExpiresAt(ts(it)) } }.build()

    override suspend fun createUser(request: CreateUserRequest): CreateUserResponse = run {
        requireUsers()
        CreateUserResponse.newBuilder().setUser(proto(call { users.createUser(request.name, fromRoles(request.rolesList), request.groupsList.toSet()) })).build()
    }

    override suspend fun listUsers(request: ListUsersRequest): ListUsersResponse = run {
        requireUsers()
        ListUsersResponse.newBuilder().addAllUsers(users.listUsers().map(::proto)).build()
    }

    override suspend fun deleteUser(request: DeleteUserRequest): DeleteUserResponse {
        requireUsers()
        call { users.deleteUser(request.userId) }
        return DeleteUserResponse.getDefaultInstance()
    }

    override suspend fun createGroup(request: CreateGroupRequest): CreateGroupResponse {
        requireUsers()
        val g = call { users.createGroup(request.name, fromRoles(request.rolesList)) }
        return CreateGroupResponse.newBuilder().setGroup(proto(g)).build()
    }

    override suspend fun listGroups(request: ListGroupsRequest): ListGroupsResponse = run {
        requireUsers()
        ListGroupsResponse.newBuilder()
        .addAllGroups(users.listGroups().map(::proto))
        .build()
    }

    override suspend fun createToken(request: CreateTokenRequest): CreateTokenResponse {
        requireUsers()
        val created = call {
            users.createToken(request.userId, request.label, request.ttlSeconds.takeIf { it > 0 }?.let { Duration.ofSeconds(it) })
        }
        return CreateTokenResponse.newBuilder().setToken(created.secret).setInfo(proto(created.info)).build()
    }

    override suspend fun listTokens(request: ListTokensRequest): ListTokensResponse = run {
        requireUsers()
        ListTokensResponse.newBuilder().addAllTokens(call { users.listTokens(request.userId) }.map(::proto)).build()
    }

    override suspend fun revokeToken(request: RevokeTokenRequest): RevokeTokenResponse {
        requireUsers()
        call { users.revokeToken(request.tokenId) }
        return RevokeTokenResponse.getDefaultInstance()
    }

    private fun change(request: cringle.user.v1.RoleScopeRequest, grant: Boolean): RoleScopeResponse {
        requireUsers()
        val role = fromRoles(listOf(request.role)).single()
        val scope = fromProto(request.scope)
        if (listOf(request.userId, request.group, request.registry).count { it.isNotEmpty() } != 1) {
            throw Status.INVALID_ARGUMENT.withDescription("name exactly one of user_id, group and registry").asException()
        }
        val response = RoleScopeResponse.newBuilder()
        call {
            if (request.registry.isNotEmpty()) {
                response.registry = proto(if (grant) users.grantRegistry(request.registry, role, scope) else users.revokeRegistry(request.registry, role, scope))
            } else if (request.userId.isNotEmpty()) {
                response.user = proto(if (grant) users.grantUser(request.userId, role, scope) else users.revokeUser(request.userId, role, scope))
            } else {
                response.group = proto(if (grant) users.grantGroup(request.group, role, scope) else users.revokeGroup(request.group, role, scope))
            }
        }
        return response.build()
    }

    override suspend fun grantRole(request: cringle.user.v1.RoleScopeRequest): RoleScopeResponse = change(request, true)

    override suspend fun revokeRole(request: cringle.user.v1.RoleScopeRequest): RoleScopeResponse = change(request, false)

    override suspend fun whoAmI(request: WhoAmIRequest): WhoAmIResponse {
        val current = AuthInterceptor.CURRENT_USER.get() ?: throw Status.UNAUTHENTICATED.asException()
        val view = users.listUsers().firstOrNull { it.user.id == current.id }
        if (view == null) {
            // a user of a trusted registry (name@registry) is not stored unless it was given rights of its own
            if (current.id.contains('@')) {
                val federated = cringle.user.v1.User.newBuilder().setId(current.id).setName(current.name).addAllEffectiveRoles(current.roles.sorted().map(::toRole)).build()
                return WhoAmIResponse.newBuilder().setUser(federated).build()
            }
            throw Status.UNAUTHENTICATED.asException()
        }
        return WhoAmIResponse.newBuilder().setUser(proto(view)).build()
    }

    private fun proto(r: TrustedRegistry): cringle.user.v1.Registry = cringle.user.v1.Registry.newBuilder().setName(r.name).setFingerprint(r.fingerprint)
        .addAllRoles(r.roles.sorted().map(::toRole)).addAllScopedRoles(r.scoped.sortedBy { it.scope.encode() }.map(::toProto)).build()

    private fun key(): java.security.KeyPair = signingKey ?: throw Status.FAILED_PRECONDITION.withDescription("this server has no key to sign federated tokens with").asException()

    override suspend fun trustRegistry(request: cringle.user.v1.TrustRegistryRequest): cringle.user.v1.TrustRegistryResponse {
        requireUsers()
        val publicKey = try {
            PublicKeyPem.parse(request.publicKeyPem)
        } catch (e: IllegalArgumentException) {
            throw Status.INVALID_ARGUMENT.withDescription(e.message).asException()
        }
        val registry = call { users.trustRegistry(request.name, publicKey, fromRoles(request.rolesList)) }
        return cringle.user.v1.TrustRegistryResponse.newBuilder().setRegistry(proto(registry)).build()
    }

    override suspend fun untrustRegistry(request: cringle.user.v1.UntrustRegistryRequest): cringle.user.v1.UntrustRegistryResponse {
        requireUsers()
        call { users.untrustRegistry(request.name) }
        return cringle.user.v1.UntrustRegistryResponse.getDefaultInstance()
    }

    override suspend fun listRegistries(request: cringle.user.v1.ListRegistriesRequest): cringle.user.v1.ListRegistriesResponse {
        requireUsers()
        return cringle.user.v1.ListRegistriesResponse.newBuilder().addAllRegistries(users.listRegistries().map(::proto)).build()
    }

    override suspend fun issueFederatedToken(request: cringle.user.v1.IssueFederatedTokenRequest): cringle.user.v1.IssueFederatedTokenResponse {
        requireUsers()
        val pair = key()
        if (request.user.isBlank() || request.user.contains('@')) throw Status.INVALID_ARGUMENT.withDescription("the user is empty or has an @").asException()
        if (request.registryName.isBlank()) throw Status.INVALID_ARGUMENT.withDescription("registry_name is empty").asException()
        val ttl = Duration.ofSeconds(request.ttlSeconds.takeIf { it > 0 } ?: Duration.ofHours(24).seconds)
        val token = try {
            FederatedToken.issue(request.registryName, request.user, pair, ttl)
        } catch (e: IllegalArgumentException) {
            throw Status.INVALID_ARGUMENT.withDescription(e.message).asException()
        }
        return cringle.user.v1.IssueFederatedTokenResponse.newBuilder().setToken(token).setExpiresAt(ts(Instant.now().plus(ttl))).build()
    }

    override suspend fun getRegistryKey(request: cringle.user.v1.GetRegistryKeyRequest): cringle.user.v1.GetRegistryKeyResponse {
        requireUsers()
        val pair = key()
        return cringle.user.v1.GetRegistryKeyResponse.newBuilder().setPublicKeyPem(PublicKeyPem.encode(pair.public))
            .setFingerprint(cringle.common.PublicKeyFingerprint.of(pair.public)).build()
    }

    public companion object {
        private val USERS_SCOPE = Scope(ScopeKind.FUNCTION, "users")

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
            "cringle.user.v1.UserService/GrantRole" to Permission.MANAGE_USERS,
            "cringle.user.v1.UserService/RevokeRole" to Permission.MANAGE_USERS,
            "cringle.user.v1.UserService/TrustRegistry" to Permission.MANAGE_USERS,
            "cringle.user.v1.UserService/UntrustRegistry" to Permission.MANAGE_USERS,
            "cringle.user.v1.UserService/ListRegistries" to Permission.MANAGE_USERS,
            "cringle.user.v1.UserService/IssueFederatedToken" to Permission.MANAGE_USERS,
            "cringle.user.v1.UserService/GetRegistryKey" to Permission.MANAGE_USERS,
            "cringle.user.v1.UserService/WhoAmI" to Permission.AUTHENTICATED,
        )
    }
}
