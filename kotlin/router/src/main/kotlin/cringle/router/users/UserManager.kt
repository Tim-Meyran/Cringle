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

    /** Creates a user; names are unique. */
    public fun createUser(name: String, roles: Set<UserRole>, groups: Set<String> = emptySet()): UserView = synchronized(lock) {
        if (name.isBlank() || name.length > 128) throw UserException(UserException.Kind.INVALID, "user name must be 1 to 128 characters")
        if (data.users.any { it.name == name }) throw UserException(UserException.Kind.CONFLICT, "user '$name' already exists")
        for (g in groups) if (data.groups.none { it.name == g }) throw UserException(UserException.Kind.NOT_FOUND, "unknown group '$g'")
        val user = User(UUID.randomUUID().toString(), name, roles, groups)
        update { it.copy(users = it.users + user) }
        UserView(user, effectiveRoles(user))
    }

    /** All users. */
    public fun listUsers(): List<UserView> = synchronized(lock) { data.users.map { UserView(it, effectiveRoles(it)) } }

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
