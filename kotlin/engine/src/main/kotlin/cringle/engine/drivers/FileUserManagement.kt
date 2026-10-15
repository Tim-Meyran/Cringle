// SPDX-License-Identifier: Apache-2.0

package cringle.engine.drivers

import cringle.contract.AuthenticatedUser
import cringle.contract.BuiltinDriverTypes
import cringle.contract.DriverType
import cringle.contract.UserManagementDriver
import cringle.contract.UserRole
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

/** What the engine needs to serve its HTTP API: the port (0: any free port), HTTPS or plain HTTP, and the user store of the machine for the tokens of protected routes. */
public data class ApiSettings(val port: Int = DEFAULT_PORT, val tls: Boolean = true, val usersFile: Path) {
    public companion object {
        /** The default API port (`api.port`). */
        public const val DEFAULT_PORT: Int = 8080
    }
}

/**
 * The tokens of the users of this machine, read from the user store of the management server (`<home>/management/users.json`): the engine only reads it, and reads it
 * again when the file changes. Without the file (no user management on this machine) no token is accepted. A file that cannot be read keeps the last good state.
 */
public class FileUserManagement(private val file: Path) : UserManagementDriver {
    private val log = LoggerFactory.getLogger("cringle.engine.http")
    override val type: DriverType = BuiltinDriverTypes.USER_MANAGEMENT
    private var modified: Long = Long.MIN_VALUE
    private var manager: UserManager? = null

    @Synchronized
    private fun current(): UserManager? {
        if (!Files.exists(file)) {
            manager = null
            modified = Long.MIN_VALUE
            return null
        }
        val time = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(modified)
        if (time != modified) {
            try {
                manager = UserManager(FileUserStore(file))
                modified = time
            } catch (e: Exception) {
                log.warn("the user store {} cannot be read: {}", file, e.message)
            }
        }
        return manager
    }

    override suspend fun authenticate(token: String): AuthenticatedUser? = current()?.authenticate(token)

    override suspend fun hasRole(user: AuthenticatedUser, role: UserRole): Boolean = role in user.roles
}
