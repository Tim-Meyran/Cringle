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

/** A user. [roles] are the directly assigned ones, see [UserView.effectiveRoles] for the result with groups. */
public data class User(val id: String, val name: String, val roles: Set<UserRole>, val groups: Set<String>)

/** A group that gives its members roles. */
public data class Group(val name: String, val roles: Set<UserRole>)

/** A user with the roles it has including those of its groups. */
public data class UserView(val user: User, val effectiveRoles: Set<UserRole>)

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

/** Everything the user store persists. */
public data class UserData(
    val users: List<User> = emptyList(),
    val groups: List<Group> = emptyList(),
    val tokens: List<TokenRecord> = emptyList(),
    /** Whether the bootstrap admin was created (and its token shown). */
    val bootstrapped: Boolean = false,
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
