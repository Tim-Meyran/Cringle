// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.router.users.Permission
import cringle.router.users.UserManager
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The sessions of the web layer, in memory (a restart of the ManagementServer logs everybody out). A session ends after [idleTimeout]
 * without a request. Without [users] (no `--auth`) everybody is administrator, as on the gRPC API, and a session is created on the first request.
 */
public class Sessions(
    private val users: UserManager?,
    private val clock: Clock = Clock.systemUTC(),
    private val idleTimeout: Duration = Duration.ofHours(8),
) {
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Session>()

    private fun newId(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    /** True if logging in is required. */
    public val loginRequired: Boolean get() = users != null

    /** Logs in with [token]; returns the new session or `null` for an invalid token. Never logs the token. */
    public fun login(token: String): Session? {
        val manager = users ?: return open()
        val user = manager.authenticate(token) ?: return null
        val session = Session(
            newId(), user, manager.permissions(user), newId(), clock.instant(),
            scoped = { permission, scopes -> manager.allowed(user, permission, scopes) },
            anywhere = { permission -> manager.allowedAnywhere(user, permission) },
        )
        sessions[session.id] = session
        return session
    }

    /** A session of the open (no `--auth`) mode. */
    public fun open(): Session {
        val session = Session(newId(), null, Permission.entries.toSet(), newId(), clock.instant())
        sessions[session.id] = session
        return session
    }

    /** The valid session with [id], touched; `null` if unknown or idle for too long. */
    public fun find(id: String?): Session? {
        if (id == null) return null
        val session = sessions[id] ?: return null
        val now = clock.instant()
        if (Duration.between(session.lastUsed, now) >= idleTimeout) {
            sessions.remove(id)
            return null
        }
        session.lastUsed = now
        return session
    }

    /** Ends the session [id]. */
    public fun end(id: String) {
        sessions.remove(id)
    }

    /** Removes the sessions that are idle for too long. */
    public fun expire() {
        val now = clock.instant()
        sessions.values.removeIf { Duration.between(it.lastUsed, now) >= idleTimeout }
    }
}
