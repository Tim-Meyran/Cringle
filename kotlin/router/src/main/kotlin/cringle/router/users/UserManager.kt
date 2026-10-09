// SPDX-License-Identifier: Apache-2.0

package cringle.router.users

import cringle.contract.AuthenticatedUser
import cringle.contract.BuiltinDriverTypes
import cringle.contract.DriverType
import cringle.contract.UserManagementDriver
import cringle.contract.UserRole
import cringle.common.PublicKeyFingerprint
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
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

    private companion object {
        /** The longest an invite stays valid. */
        val MAX_INVITE: Duration = Duration.ofDays(30)

        val REGISTRY_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

        /** How far in the future the issue time of a federated token may lie (clocks of two sites are not exact). */
        val CLOCK_SKEW: Duration = Duration.ofMinutes(2)
    }

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
        if (token.startsWith(FederatedToken.PREFIX)) return authenticateFederated(token)
        val h = hash(token)
        val record = data.tokens.firstOrNull { MessageDigest.isEqual(it.hash.toByteArray(), h.toByteArray()) } ?: return null
        if (record.revoked) return null
        if (record.expiresAt != null && !clock.instant().isBefore(record.expiresAt)) return null
        val user = data.users.firstOrNull { it.id == record.userId } ?: return null
        AuthenticatedUser(user.id, user.name, effectiveRoles(user))
    }

    /**
     * A token of a user of a trusted registry (#231): the registry named in it has to be trusted, the key in the token has to be the trusted one,
     * the signature has to be valid and the token has to be valid now and not for longer than [FederatedToken.MAX_LIFETIME]. Every failure is `null`.
     */
    private fun authenticateFederated(token: String): AuthenticatedUser? {
        val parsed = FederatedToken.parse(token) ?: return null
        val claims = parsed.claims
        val registry = data.registries.firstOrNull { it.name == claims.issuer } ?: return null
        val key = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(claims.publicKey))
        } catch (_: Exception) {
            return null
        }
        if (!MessageDigest.isEqual(PublicKeyFingerprint.of(key).toByteArray(), registry.fingerprint.toByteArray())) return null
        if (!FederatedToken.verify(parsed, key)) return null
        val now = clock.instant()
        if (claims.issuedAt.isAfter(now.plus(CLOCK_SKEW)) || !now.isBefore(claims.expiresAt)) return null
        if (Duration.between(claims.issuedAt, claims.expiresAt) > FederatedToken.MAX_LIFETIME) return null
        if (!validFederatedName(claims.subject)) return null
        val id = "${claims.subject}@${registry.name}"
        val stored = data.users.firstOrNull { it.id == id }
        return AuthenticatedUser(id, id, registry.roles + stored?.let(::effectiveRoles).orEmpty())
    }

    /** The registry of a federated user id (`name@registry`), if it is still trusted. */
    private fun registryOf(id: String): TrustedRegistry? =
        if ('@' in id) data.registries.firstOrNull { it.name == id.substringAfterLast('@') } else null

    /** The scoped roles of the user with [id]: the stored user's and, for a federated user, those of its registry; `null` for a user that is not known. */
    private fun scopedOf(id: String): Set<RoleAssignment>? {
        val stored = data.users.firstOrNull { it.id == id }
        val registry = registryOf(id)
        if (stored == null && registry == null) return null
        return stored?.let(::effectiveScoped).orEmpty() + registry?.scoped.orEmpty()
    }

    private fun validFederatedName(name: String): Boolean = name.isNotBlank() && name.length <= 128 && '@' !in name && name.none { it.isISOControl() }

    /** The permissions of [user], given by its roles. */
    public fun permissions(user: AuthenticatedUser): Set<Permission> = user.roles.flatMap { Permission.of(it) }.toSet() + Permission.AUTHENTICATED

    /**
     * Whether [user] may do [permission] to an object that lies in all of [scopes] (for a fabric: the fabric, its machine and its project). True if a
     * global role of the user or its groups gives it, or a role assigned for one of [scopes] does (#229). Users that no longer exist have nothing.
     */
    public fun allowed(user: AuthenticatedUser, permission: Permission, scopes: Collection<Scope>): Boolean = synchronized(lock) {
        if (permission == Permission.AUTHENTICATED) return true
        if (user.roles.any { permission in Permission.of(it) }) return true
        val scoped = scopedOf(user.id) ?: return false
        scoped.any { it.scope in scopes && permission in Permission.of(it.role) }
    }

    /**
     * Whether [user] has [permission] globally or for any scope. This is the question of the interceptor in front of a method: the object a call touches
     * is only known inside the method, which then asks [allowed] with the scopes of that object (#269).
     */
    public fun allowedAnywhere(user: AuthenticatedUser, permission: Permission): Boolean = synchronized(lock) {
        if (permission == Permission.AUTHENTICATED) return true
        if (user.roles.any { permission in Permission.of(it) }) return true
        val scoped = scopedOf(user.id) ?: return false
        scoped.any { permission in Permission.of(it.role) }
    }

    /** Whether [user] has [permission] for [scope] (see the other [allowed]). */
    public fun allowed(user: AuthenticatedUser, permission: Permission, scope: Scope): Boolean = allowed(user, permission, listOf(scope))

    private fun checkAssignment(a: RoleAssignment) {
        if (a.role == UserRole.END_USER) throw UserException(UserException.Kind.INVALID, "the role end user cannot be limited to a scope")
        if (a.scope.kind == ScopeKind.GLOBAL) throw UserException(UserException.Kind.INVALID, "a global role is given with the roles of the user or group, not as a scoped one")
    }

    // --- trusted registries (#231) ---

    /**
     * Trusts the registry [name], whose tokens are signed with [publicKey] (an EC key): its users are accepted as `name@registry` with [roles]
     * (none by default: they are authenticated and may do nothing until they are given rights).
     */
    public fun trustRegistry(name: String, publicKey: PublicKey, roles: Set<UserRole> = emptySet()): TrustedRegistry = synchronized(lock) {
        if (!REGISTRY_NAME.matches(name)) throw UserException(UserException.Kind.INVALID, "invalid registry name '$name'")
        if (publicKey.algorithm != "EC") throw UserException(UserException.Kind.INVALID, "the key of a registry has to be an EC key")
        if (UserRole.END_USER in roles) throw UserException(UserException.Kind.INVALID, "the role end user cannot be given to a registry")
        if (data.registries.any { it.name == name }) throw UserException(UserException.Kind.CONFLICT, "registry '$name' is already trusted")
        val registry = TrustedRegistry(name, PublicKeyFingerprint.of(publicKey), Base64.getEncoder().encodeToString(publicKey.encoded), roles)
        update { it.copy(registries = it.registries + registry) }
        registry
    }

    /** Stops trusting [name]: its tokens are refused from now on, and the stored users `x@name` are deleted. */
    public fun untrustRegistry(name: String): Unit = synchronized(lock) {
        if (data.registries.none { it.name == name }) throw UserException(UserException.Kind.NOT_FOUND, "registry '$name' is not trusted")
        update { d -> d.copy(registries = d.registries.filter { it.name != name }, users = d.users.filter { !it.id.endsWith("@$name") || '@' !in it.id }) }
    }

    /** The trusted registries. */
    public fun listRegistries(): List<TrustedRegistry> = synchronized(lock) { data.registries }

    /** Sets the global roles of all users of [name]. */
    public fun setRegistryRoles(name: String, roles: Set<UserRole>): TrustedRegistry = synchronized(lock) {
        if (UserRole.END_USER in roles) throw UserException(UserException.Kind.INVALID, "the role end user cannot be given to a registry")
        changeRegistry(name) { it.copy(roles = roles) }
    }

    /** Gives all users of [name] the role [role] for [scope]. */
    public fun grantRegistry(name: String, role: UserRole, scope: Scope): TrustedRegistry = synchronized(lock) {
        val a = RoleAssignment(role, scope).also(::checkAssignment)
        changeRegistry(name) { it.copy(scoped = it.scoped + a) }
    }

    /** Takes the scoped role of a registry back; `NOT_FOUND` if it does not have it. */
    public fun revokeRegistry(name: String, role: UserRole, scope: Scope): TrustedRegistry = synchronized(lock) {
        val a = RoleAssignment(role, scope)
        changeRegistry(name) {
            if (a !in it.scoped) throw UserException(UserException.Kind.NOT_FOUND, "the registry does not have the role ${role.name.lowercase()} for $scope")
            it.copy(scoped = it.scoped - a)
        }
    }

    private fun changeRegistry(name: String, change: (TrustedRegistry) -> TrustedRegistry): TrustedRegistry {
        val registry = data.registries.firstOrNull { it.name == name } ?: throw UserException(UserException.Kind.NOT_FOUND, "registry '$name' is not trusted")
        val next = change(registry)
        update { d -> d.copy(registries = d.registries.map { if (it.name == name) next else it }) }
        return next
    }

    /**
     * Stores the user `name@registry` so that it can get rights of its own (they add to those of the registry). It is not needed for the user to
     * log in. The id and the name of the user are `name@registry`.
     */
    public fun createFederatedUser(registry: String, name: String, roles: Set<UserRole> = emptySet(), groups: Set<String> = emptySet()): UserView = synchronized(lock) {
        if (data.registries.none { it.name == registry }) throw UserException(UserException.Kind.NOT_FOUND, "registry '$registry' is not trusted")
        if (!validFederatedName(name)) throw UserException(UserException.Kind.INVALID, "invalid user name '$name'")
        if (UserRole.END_USER in roles) throw UserException(UserException.Kind.INVALID, "the role end user cannot be given to a federated user")
        for (g in groups) if (data.groups.none { it.name == g }) throw UserException(UserException.Kind.NOT_FOUND, "unknown group '$g'")
        val id = "$name@$registry"
        if (data.users.any { it.id == id }) throw UserException(UserException.Kind.CONFLICT, "user '$id' already exists")
        val user = User(id, id, roles, groups)
        update { it.copy(users = it.users + user) }
        view(user)
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
        checkLocalName(name)
        if (data.users.any { it.name == name }) throw UserException(UserException.Kind.CONFLICT, "user '$name' already exists")
        for (g in groups) if (data.groups.none { it.name == g }) throw UserException(UserException.Kind.NOT_FOUND, "unknown group '$g'")
        val user = User(UUID.randomUUID().toString(), name, roles, groups)
        update { it.copy(users = it.users + user) }
        view(user)
    }

    /** A local user name: 1 to 128 characters, no `@`: `name@registry` is how the users of a trusted registry are shown, a local user must not look like one. */
    private fun checkLocalName(name: String) {
        if (name.isBlank() || name.length > 128) throw UserException(UserException.Kind.INVALID, "user name must be 1 to 128 characters")
        if ('@' in name) throw UserException(UserException.Kind.INVALID, "user name must not contain @ (name@registry is the form of a user of another registry)")
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

    private fun inviteInfo(i: Invite) = InviteInfo(i.id, i.roles, i.groups, i.scoped, i.createdBy, i.createdAt, i.expiresAt, i.usedAt, i.usedBy, i.revoked)

    /**
     * Creates an invite (#303): a one-time secret that [redeemInvite] turns into a user with [roles], [groups] and [scoped] roles. It is valid for [ttl]
     * (24 hours if `null`, at most 30 days). The inviter decides what it gives, including administrator.
     */
    public fun createInvite(createdBy: String, roles: Set<UserRole>, groups: Set<String> = emptySet(), scoped: Set<RoleAssignment> = emptySet(), ttl: Duration? = null): CreatedInvite = synchronized(lock) {
        val life = ttl ?: Duration.ofHours(24)
        if (life.isNegative || life.isZero || life > MAX_INVITE) throw UserException(UserException.Kind.INVALID, "invite lifetime must be between 1 second and 30 days")
        for (g in groups) if (data.groups.none { it.name == g }) throw UserException(UserException.Kind.NOT_FOUND, "unknown group '$g'")
        val secret = newSecret().replaceFirst("crt_", "inv_")
        val now = clock.instant()
        val invite = Invite(UUID.randomUUID().toString(), hash(secret), roles, groups, scoped, createdBy, now, now.plus(life))
        update { it.copy(invites = it.invites + invite) }
        CreatedInvite(secret, inviteInfo(invite))
    }

    /** All invites, newest first, without their secrets. */
    public fun listInvites(): List<InviteInfo> = synchronized(lock) { data.invites.map { inviteInfo(it) }.sortedByDescending { it.createdAt } }

    /** Revokes an invite that is not used yet; revoking twice is fine. */
    public fun revokeInvite(id: String): Unit = synchronized(lock) {
        val invite = data.invites.firstOrNull { it.id == id } ?: throw UserException(UserException.Kind.NOT_FOUND, "unknown invite")
        if (invite.usedAt != null) throw UserException(UserException.Kind.CONFLICT, "the invite was used already")
        update { d -> d.copy(invites = d.invites.map { if (it.id == id) it.copy(revoked = true) else it }) }
    }

    /** What the invite behind [secret] gives, or `null` if it is unknown, used, revoked or expired (one answer for all, so nothing leaks). */
    public fun peekInvite(secret: String): InviteInfo? = synchronized(lock) { openInvite(secret)?.let { inviteInfo(it) } }

    private fun openInvite(secret: String): Invite? {
        val h = hash(secret)
        val now = clock.instant()
        return data.invites.firstOrNull { MessageDigest.isEqual(it.hash.toByteArray(), h.toByteArray()) && it.usedAt == null && !it.revoked && it.expiresAt.isAfter(now) }
    }

    /**
     * Redeems the invite behind [secret]: creates the user [name] with what the invite gives and one token (label [tokenLabel], lifetime [tokenTtl]),
     * and uses the invite up, in one step under the lock. An unknown, used, revoked or expired secret is `NOT_FOUND` for all of them; a name that
     * exists is `CONFLICT` and the invite stays valid.
     */
    public fun redeemInvite(secret: String, name: String, tokenLabel: String = "invite", tokenTtl: Duration? = null): RedeemedInvite = synchronized(lock) {
        val invite = openInvite(secret) ?: throw UserException(UserException.Kind.NOT_FOUND, "the invite is not valid")
        checkLocalName(name)
        if (data.users.any { it.name == name }) throw UserException(UserException.Kind.CONFLICT, "user '$name' already exists")
        if (tokenTtl != null && (tokenTtl.isNegative || tokenTtl.isZero)) throw UserException(UserException.Kind.INVALID, "token lifetime must be positive")
        val now = clock.instant()
        val user = User(UUID.randomUUID().toString(), name, invite.roles, invite.groups, invite.scoped)
        val tokenSecret = newSecret()
        val record = TokenRecord(UUID.randomUUID().toString(), user.id, tokenLabel, hash(tokenSecret), now, tokenTtl?.let { now.plus(it) }, false)
        update { d ->
            d.copy(
                users = d.users + user,
                tokens = d.tokens + record,
                invites = d.invites.map { if (it.id == invite.id) it.copy(usedAt = now, usedBy = name) else it },
            )
        }
        RedeemedInvite(view(user), CreatedToken(tokenSecret, info(record)))
    }

    /** This user management as the engine-wide driver for blocks. */
    public fun asDriver(): UserManagementDriver = object : UserManagementDriver {
        override val type: DriverType = BuiltinDriverTypes.USER_MANAGEMENT

        override suspend fun authenticate(token: String): AuthenticatedUser? = this@UserManager.authenticate(token)

        override suspend fun hasRole(user: AuthenticatedUser, role: UserRole): Boolean = role in user.roles
    }
}
