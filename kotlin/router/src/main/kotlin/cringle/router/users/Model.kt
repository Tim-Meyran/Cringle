// SPDX-License-Identifier: Apache-2.0

package cringle.router.users

import cringle.contract.UserRole
import java.time.Instant

/** What a caller may do. Roles map to permissions; the first version applies them globally. */
public enum class Permission {
    /** Every valid token has it. */
    AUTHENTICATED,

    /** Read state. */
    READ,

    /** Start, stop, deploy. */
    OPERATE,

    /** Create and remove users, groups and tokens. */
    MANAGE_USERS,

    /** Use an application built with Cringle. */
    APPLICATION,

    /** Change security-relevant settings, such as the trust status of plugins. */
    ADMINISTER,
    ;

    public companion object {
        /** The permissions of [role]. */
        public fun of(role: UserRole): Set<Permission> = when (role) {
            UserRole.ADMIN -> entries.toSet()
            UserRole.OPERATOR -> setOf(AUTHENTICATED, READ, OPERATE)
            UserRole.VIEWER -> setOf(AUTHENTICATED, READ)
            UserRole.END_USER -> setOf(AUTHENTICATED, APPLICATION)
        }
    }
}

/** Thrown for invalid requests to the [UserManager]; [kind] tells the gRPC layer which status to use. */
public class UserException(public val kind: Kind, message: String) : RuntimeException(message) {
    /** Category of the problem. */
    public enum class Kind { INVALID, NOT_FOUND, CONFLICT }
}

/** What a role assignment can be limited to (#229, Architecture 6.3). */
public enum class ScopeKind {
    /** Everything; the role applies as before scopes existed. */
    GLOBAL,

    /** One machine and what runs on it. */
    MACHINE,

    /** One project and its fabrics. */
    PROJECT,

    /** One fabric. */
    FABRIC,

    /** One framework function, named by [Scope.FUNCTIONS]. */
    FUNCTION,
}

/**
 * The object a role applies to: everything ([GLOBAL]), or one machine, project, fabric or framework function. Written as text `global`,
 * `machine:m1`, `project:shop`, `fabric:shop-app-1` or `function:trust`.
 */
public data class Scope(val kind: ScopeKind, val name: String = "") {
    init {
        when (kind) {
            ScopeKind.GLOBAL -> require(name.isEmpty()) { "the global scope has no name" }
            ScopeKind.FUNCTION -> require(name in FUNCTIONS) { "unknown function '$name' (one of ${FUNCTIONS.joinToString()})" }
            else -> require(NAME.matches(name)) { "invalid ${kind.name.lowercase()} name '$name'" }
        }
    }

    /** The text form, see [parse]. */
    public fun encode(): String = if (kind == ScopeKind.GLOBAL) "global" else "${kind.name.lowercase()}:$name"

    override fun toString(): String = encode()

    public companion object {
        /** The framework functions a role can be limited to: trust of components, trust of plugins, users and groups. */
        public val FUNCTIONS: List<String> = listOf("trust", "plugin-trust", "users")

        private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        /** Everything. */
        public val GLOBAL: Scope = Scope(ScopeKind.GLOBAL)

        /** Parses the text form; throws [IllegalArgumentException] if it is invalid. */
        public fun parse(text: String): Scope {
            if (text == "global") return GLOBAL
            val kind = text.substringBefore(':', "").uppercase().let { k -> ScopeKind.entries.firstOrNull { it.name == k && it != ScopeKind.GLOBAL } }
            require(kind != null && ':' in text) { "invalid scope '$text' (global, machine:<id>, project:<name>, fabric:<id> or function:<name>)" }
            return Scope(kind, text.substringAfter(':'))
        }
    }
}

/** A role that applies only to [scope] (#229). */
public data class RoleAssignment(val role: UserRole, val scope: Scope)

/** A user. [roles] are the global ones; [scoped] are limited to an object. See [UserView.effectiveRoles] for the result with groups. */
public data class User(val id: String, val name: String, val roles: Set<UserRole>, val groups: Set<String>, val scoped: Set<RoleAssignment> = emptySet())

/** A group that gives its members roles. */
public data class Group(val name: String, val roles: Set<UserRole>, val scoped: Set<RoleAssignment> = emptySet())

/** A user with the global roles it has including those of its groups, and the same for the scoped ones. */
public data class UserView(val user: User, val effectiveRoles: Set<UserRole>, val effectiveScoped: Set<RoleAssignment> = emptySet())

/**
 * A registry of another site that this one trusts (Architecture 6.2): its users come as `name@registry` with a token that the registry signed
 * with the key [publicKey] (Base64 of the X.509 encoding; [fingerprint] is the SHA-256 of that encoding, the thing the administrator confirmed).
 * [roles] and [scoped] are what every user of the registry may do; a stored user with the id `name@registry` adds to it.
 */
public data class TrustedRegistry(
    val name: String,
    val fingerprint: String,
    val publicKey: String,
    val roles: Set<UserRole> = emptySet(),
    val scoped: Set<RoleAssignment> = emptySet(),
)

/** A stored token. Only the SHA-256 hash of the token value is kept, never the value. */
public data class TokenRecord(
    val id: String,
    val userId: String,
    val label: String,
    val hash: String,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val revoked: Boolean,
)

/** What is known about a token, without its value. */
public data class TokenInfo(
    val id: String,
    val userId: String,
    val label: String,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val revoked: Boolean,
)

/** A freshly created token: the only place where the value [secret] ever appears. */
public data class CreatedToken(val secret: String, val info: TokenInfo)

/** A stored invite (#303). Only the SHA-256 hash of the secret in the link is kept. [usedAt] and [usedBy] (a user name) are set when it was redeemed. */
public data class Invite(
    val id: String,
    val hash: String,
    val roles: Set<UserRole>,
    val groups: Set<String>,
    val scoped: Set<RoleAssignment>,
    val createdBy: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant? = null,
    val usedBy: String? = null,
    val revoked: Boolean = false,
)

/** What is known about an invite, without its hash. */
public data class InviteInfo(
    val id: String,
    val roles: Set<UserRole>,
    val groups: Set<String>,
    val scoped: Set<RoleAssignment>,
    val createdBy: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant?,
    val usedBy: String?,
    val revoked: Boolean,
) {
    /** Where the invite stands at [now]. */
    public fun state(now: Instant): InviteState = when {
        usedAt != null -> InviteState.USED
        revoked -> InviteState.REVOKED
        !expiresAt.isAfter(now) -> InviteState.EXPIRED
        else -> InviteState.OPEN
    }
}

/** The state of an invite. */
public enum class InviteState { OPEN, USED, EXPIRED, REVOKED }

/** A freshly created invite: the only place where the secret of the link appears. */
public data class CreatedInvite(val secret: String, val info: InviteInfo)

/** The result of redeeming an invite: the new user and its first token. */
public data class RedeemedInvite(val user: UserView, val token: CreatedToken)

/** Everything the user store persists. */
public data class UserData(
    val users: List<User> = emptyList(),
    val groups: List<Group> = emptyList(),
    val tokens: List<TokenRecord> = emptyList(),
    /** Whether the bootstrap admin was created (and its token shown). */
    val bootstrapped: Boolean = false,
    /** The registries of other sites that are trusted (#231). */
    val registries: List<TrustedRegistry> = emptyList(),
    /** The invites (#303). */
    val invites: List<Invite> = emptyList(),
)

/** Persistence of [UserData]. */
public interface UserStore {
    /** Loads the data; empty if nothing was stored yet. */
    public fun load(): UserData

    /** Stores [data]. */
    public fun save(data: UserData)
}

/** A store that keeps the data in memory. */
public class InMemoryUserStore(private var data: UserData = UserData()) : UserStore {
    override fun load(): UserData = data

    override fun save(data: UserData) {
        this.data = data
    }
}
