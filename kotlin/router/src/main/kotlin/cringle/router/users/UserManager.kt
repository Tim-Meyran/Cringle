// SPDX-License-Identifier: Apache-2.0

package cringle.router.users

import cringle.contract.AuthenticatedUser
import cringle.contract.BuiltinDriverTypes
import cringle.contract.DriverType
import cringle.contract.UserManagementDriver
import cringle.contract.UserRole
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * Users, groups and access tokens of the registry (Architecture chapters 6 and 18), as a library that the
 * ManagementServer and the Repository use.
 *
 * Tokens are random 256 bit values; only their SHA-256 hash is stored, so they cannot be recovered from storage.
 * [authenticate] answers `null` for every kind of failure (unknown, revoked, expired token, deleted user), so callers
 * cannot learn whether a user exists. Token values are never logged.
 */
public class UserManager(private val store: UserStore, private val clock: Clock = Clock.systemUTC()) {
    private val lock = Any()
    private var data: UserData = store.load()
    private val random = SecureRandom()

    private fun update(change: (UserData) -> UserData) {
        val next = change(data)
        store.save(next)
        data = next
    }

    private fun newSecret(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return "crt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(secret: String): String =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun info(t: TokenRecord) = TokenInfo(t.id, t.userId, t.label, t.createdAt, t.expiresAt, t.revoked)

    private fun effectiveRoles(u: User): Set<UserRole> =
        u.roles + u.groups.flatMap { g -> data.groups.firstOrNull { it.name == g }?.roles.orEmpty() }

    private fun effectiveScoped(u: User): Set<RoleAssignment> =
        u.scoped + u.groups.flatMap { g -> data.groups.firstOrNull { it.name == g }?.scoped.orEmpty() }

    private fun view(u: User) = UserView(u, effectiveRoles(u), effectiveScoped(u))

    /**
     * On the first start, creates the user `admin` with the admin role and returns its token, which is not shown
     * anywhere else. Returns `null` on every later start (the token keeps working until it is revoked).
     *
     * [persistSecret] gets the token before the user and the hash of the token are stored, so that a crash after the
     * storing can never leave a token that exists nowhere: whatever [persistSecret] wrote (for example a file with
     * owner-only rights) is there when the user is. If it throws, nothing is stored and the call can be repeated.
     */
    public fun bootstrap(persistSecret: (String) -> Unit = {}): String? = synchronized(lock) {
        if (data.bootstrapped || data.users.isNotEmpty()) {
            if (!data.bootstrapped) update { it.copy(bootstrapped = true) }
            return null
        }
        val admin = User(UUID.randomUUID().toString(), "admin", setOf(UserRole.ADMIN), emptySet())
        val secret = newSecret()
        persistSecret(secret)
        val record = TokenRecord(UUID.randomUUID().toString(), admin.id, "bootstrap", hash(secret), clock.instant(), null, false)
        update { it.copy(users = it.users + admin, tokens = it.tokens + record, bootstrapped = true) }
        secret
    }

    /** Returns the user of [token], or `null` for an unknown, revoked or expired token. */
    public fun authenticate(token: String): AuthenticatedUser? = synchronized(lock) {
        val h = hash(token)
        val record = data.tokens.firstOrNull { MessageDigest.isEqual(it.hash.toByteArray(), h.toByteArray()) } ?: return null
        if (record.revoked) return null
        if (record.expiresAt != null && !clock.instant().isBefore(record.expiresAt)) return null
        val user = data.users.firstOrNull { it.id == record.userId } ?: return null
        AuthenticatedUser(user.id, user.name, effectiveRoles(user))
    }

    /** The permissions of [user], given by its roles. */
    public fun permissions(user: AuthenticatedUser): Set<Permission> = user.roles.flatMap { Permission.of(it) }.toSet() + Permission.AUTHENTICATED

    /**
     * Whether [user] may do [permission] to an object that lies in all of [scopes] (for a fabric: the fabric, its machine and its project). True if a
     * global role of the user or its groups gives it, or a role assigned for one of [scopes] does (#229). Users that no longer exist have nothing.
     */
    public fun allowed(user: AuthenticatedUser, permission: Permission, scopes: Collection<Scope>): Boolean = synchronized(lock) {
        if (permission == Permission.AUTHENTICATED) return true
        if (user.roles.any { permission in Permission.of(it) }) return true
        val stored = data.users.firstOrNull { it.id == user.id } ?: return false
        effectiveScoped(stored).any { it.scope in scopes && permission in Permission.of(it.role) }
    }

    /** Whether [user] has [permission] for [scope] (see the other [allowed]). */
    public fun allowed(user: AuthenticatedUser, permission: Permission, scope: Scope): Boolean = allowed(user, permission, listOf(scope))

    private fun checkAssignment(a: RoleAssignment) {
        if (a.role == UserRole.END_USER) throw UserException(UserException.Kind.INVALID, "the role end user cannot be limited to a scope")
        if (a.scope.kind == ScopeKind.GLOBAL) throw UserException(UserException.Kind.INVALID, "a global role is given with the roles of the user or group, not as a scoped one")
    }

    /** Gives [userId] the role [role] for [scope]; giving it twice is fine. */
    public fun grantUser(userId: String, role: UserRole, scope: Scope): UserView = synchronized(lock) {
        val a = RoleAssignment(role, scope).also(::checkAssignment)
        val user = data.users.firstOrNull { it.id == userId } ?: throw UserException(UserException.Kind.NOT_FOUND, "unknown user")
        val next = user.copy(scoped = user.scoped + a)
        update { d -> d.copy(users = d.users.map { if (it.id == userId) next else it }) }
        view(next)
    }

    /** Takes the scoped role back; `NOT_FOUND` if the user does not have it. */
    public fun revokeUser(userId: String, role: UserRole, scope: Scope): UserView = synchronized(lock) {
        val a = RoleAssignment(role, scope)
        val user = data.users.firstOrNull { it.id == userId } ?: throw UserException(UserException.Kind.NOT_FOUND, "unknown user")
        if (a !in user.scoped) throw UserException(UserException.Kind.NOT_FOUND, "the user does not have the role ${role.name.lowercase()} for $scope")
        val next = user.copy(scoped = user.scoped - a)
        update { d -> d.copy(users = d.users.map { if (it.id == userId) next else it }) }
        view(next)
    }

    /** Gives the group [group] the role [role] for [scope]; its members have it. */
    public fun grantGroup(group: String, role: UserRole, scope: Scope): Group = synchronized(lock) {
        val a = RoleAssignment(role, scope).also(::checkAssignment)
        val g = data.groups.firstOrNull { it.name == group } ?: throw UserException(UserException.Kind.NOT_FOUND, "unknown group '$group'")
        val next = g.copy(scoped = g.scoped + a)
        update { d -> d.copy(groups = d.groups.map { if (it.name == group) next else it }) }
        next
    }

    /** Takes the scoped role of a group back; `NOT_FOUND` if the group does not have it. */
    public fun revokeGroup(group: String, role: UserRole, scope: Scope): Group = synchronized(lock) {
        val a = RoleAssignment(role, scope)
        val g = data.groups.firstOrNull { it.name == group } ?: throw UserException(UserException.Kind.NOT_FOUND, "unknown group '$group'")
        if (a !in g.scoped) throw UserException(UserException.Kind.NOT_FOUND, "the group does not have the role ${role.name.lowercase()} for $scope")
        val next = g.copy(scoped = g.scoped - a)
        update { d -> d.copy(groups = d.groups.map { if (it.name == group) next else it }) }
        next
    }

    /** Creates a user; names are unique. */
    public fun createUser(name: String, roles: Set<UserRole>, groups: Set<String> = emptySet()): UserView = synchronized(lock) {
        if (name.isBlank() || name.length > 128) throw UserException(UserException.Kind.INVALID, "user name must be 1 to 128 characters")
        if (data.users.any { it.name == name }) throw UserException(UserException.Kind.CONFLICT, "user '$name' already exists")
        for (g in groups) if (data.groups.none { it.name == g }) throw UserException(UserException.Kind.NOT_FOUND, "unknown group '$g'")
        val user = User(UUID.randomUUID().toString(), name, roles, groups)
        update { it.copy(users = it.users + user) }
        view(user)
    }

    /** All users. */
    public fun listUsers(): List<UserView> = synchronized(lock) { data.users.map { view(it) } }

    /** Deletes a user and its tokens. The last admin cannot be deleted. */
    public fun deleteUser(id: String): Unit = synchronized(lock) {
        val user = data.users.firstOrNull { it.id == id } ?: throw UserException(UserException.Kind.NOT_FOUND, "unknown user")
        if (UserRole.ADMIN in effectiveRoles(user) && data.users.count { UserRole.ADMIN in effectiveRoles(it) } == 1) {
            throw UserException(UserException.Kind.CONFLICT, "the last admin cannot be deleted")
        }
        update { it.copy(users = it.users - user, tokens = it.tokens.filter { t -> t.userId != id }) }
    }

    /** Creates a group; names are unique. */
    public fun createGroup(name: String, roles: Set<UserRole>): Group = synchronized(lock) {
        if (name.isBlank() || name.length > 128) throw UserException(UserException.Kind.INVALID, "group name must be 1 to 128 characters")
        if (data.groups.any { it.name == name }) throw UserException(UserException.Kind.CONFLICT, "group '$name' already exists")
        Group(name, roles).also { g -> update { it.copy(groups = it.groups + g) } }
    }

    /** All groups. */
    public fun listGroups(): List<Group> = synchronized(lock) { data.groups }

    /** Creates a token for [userId] that expires after [ttl] (never if `null`). */
    public fun createToken(userId: String, label: String, ttl: Duration?): CreatedToken = synchronized(lock) {
        if (data.users.none { it.id == userId }) throw UserException(UserException.Kind.NOT_FOUND, "unknown user")
        if (ttl != null && (ttl.isNegative || ttl.isZero)) throw UserException(UserException.Kind.INVALID, "token lifetime must be positive")
        val secret = newSecret()
        val now = clock.instant()
        val record = TokenRecord(UUID.randomUUID().toString(), userId, label, hash(secret), now, ttl?.let { now.plus(it) }, false)
        update { it.copy(tokens = it.tokens + record) }
        CreatedToken(secret, info(record))
    }

    /** The tokens of [userId], without their values. */
    public fun listTokens(userId: String): List<TokenInfo> = synchronized(lock) {
        if (data.users.none { it.id == userId }) throw UserException(UserException.Kind.NOT_FOUND, "unknown user")
        data.tokens.filter { it.userId == userId }.map { info(it) }
    }

    /** Revokes a token; revoking twice is fine. */
    public fun revokeToken(tokenId: String): Unit = synchronized(lock) {
        if (data.tokens.none { it.id == tokenId }) throw UserException(UserException.Kind.NOT_FOUND, "unknown token")
        update { d -> d.copy(tokens = d.tokens.map { if (it.id == tokenId) it.copy(revoked = true) else it }) }
    }

    /** This user management as the engine-wide driver for blocks. */
    public fun asDriver(): UserManagementDriver = object : UserManagementDriver {
        override val type: DriverType = BuiltinDriverTypes.USER_MANAGEMENT

        override suspend fun authenticate(token: String): AuthenticatedUser? = this@UserManager.authenticate(token)

        override suspend fun hasRole(user: AuthenticatedUser, role: UserRole): Boolean = role in user.roles
    }
}
