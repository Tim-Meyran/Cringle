// SPDX-License-Identifier: Apache-2.0

package cringle.contract

import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** The driver types the engine ships. Blocks name these ids in [BlockDefinition.requiredDrivers]. */
public object BuiltinDriverTypes {
    /** [LoggingDriver]. */
    public val LOGGING: DriverType = DriverType("logging", IsolationLevel.SHARED)

    /** [FilesystemDriver]. */
    public val FILESYSTEM: DriverType = DriverType("filesystem", IsolationLevel.SHARED)

    /** [TcpDriver]. */
    public val TCP: DriverType = DriverType("tcp", IsolationLevel.SHARED)

    /** [UserManagementDriver]; provided by the daemon, not by every engine. */
    public val USER_MANAGEMENT: DriverType = DriverType("user-management", IsolationLevel.SHARED)

    /** The types every engine ships, by id. */
    public val ALL: Map<String, DriverType> = listOf(LOGGING, FILESYSTEM, TCP).associateBy { it.id }
}

/** Severity of a log entry. */
public enum class LogLevel {
    /** Detail for debugging. */
    DEBUG,

    /** Normal events. */
    INFO,

    /** Something needs attention. */
    WARN,

    /** A failure. */
    ERROR,
}

/**
 * Unified logging of the engine. Entries are tagged with the fabric and the block of the caller and kept in the
 * engine's local log store, from where they can be queried by fabric and block.
 */
public interface LoggingDriver : Driver {
    /** Logs [message] at [level]; [error] adds the stack trace of a throwable. */
    public suspend fun log(level: LogLevel, message: String, error: Throwable? = null)
}

/** Thrown by [FilesystemDriver] when a path leaves the block's directory or is otherwise not allowed. */
public class FilesystemAccessException(message: String) : RuntimeException(message)

/**
 * File access restricted to the working directory of the calling block. All paths are relative to that directory and
 * use `/` as separator. Absolute paths, `..` segments that leave the directory and symbolic links that point out of it
 * are rejected with [FilesystemAccessException].
 */
public interface FilesystemDriver : Driver {
    /** Reads the whole file. */
    public suspend fun readBytes(path: String): ByteArray

    /** Reads the whole file as UTF-8 text. */
    public suspend fun readText(path: String): String

    /** Writes [bytes], creating parent directories and replacing an existing file. */
    public suspend fun writeBytes(path: String, bytes: ByteArray)

    /** Writes [text] as UTF-8, creating parent directories and replacing an existing file. */
    public suspend fun writeText(path: String, text: String)

    /** Appends [bytes] to the file, creating it and its parent directories if needed. */
    public suspend fun append(path: String, bytes: ByteArray)

    /** Lists the names in a directory, sorted. */
    public suspend fun list(path: String = ""): List<String>

    /** Whether [path] exists. */
    public suspend fun exists(path: String): Boolean

    /** Deletes a file or an empty directory; returns whether something was deleted. */
    public suspend fun delete(path: String): Boolean

    /** Creates a directory and its parents. */
    public suspend fun createDirectories(path: String)
}

/** Thrown by [TcpDriver.listen] when the port is already used; the message says by whom. */
public class PortInUseException(public val port: Int, message: String) : RuntimeException(message)

/** One TCP connection, seen as a raw byte stream. */
public interface TcpConnection : TetherByteStream {
    /** Remote address as `host:port`. */
    public val remoteAddress: String
}

/** A listening TCP port. */
public interface TcpListener {
    /** The port that is actually bound (differs from the requested one when 0 was requested). */
    public val port: Int

    /** Accepted connections; completes when the listener is closed. */
    public val connections: Flow<TcpConnection>

    /** Stops listening and frees the port. */
    public suspend fun close()
}

/**
 * TCP access. The engine keeps one registry of all ports used through this driver, so a second block that asks for a
 * port that is already in use fails at once with [PortInUseException], before the operating system is asked.
 */
public interface TcpDriver : Driver {
    /** Listens on [port] (0 picks a free port) on the loopback interface, or on all interfaces if [anyInterface]. */
    public suspend fun listen(port: Int, anyInterface: Boolean = false): TcpListener

    /** Connects to [host]:[port]. */
    public suspend fun connect(host: String, port: Int): TcpConnection
}

/** One entry of the engine's log store. */
public data class LogEntry(
    public val timestamp: Instant,
    public val fabric: String,
    public val block: String,
    public val level: LogLevel,
    public val message: String,
)

/** The roles of the first version of user management. Roles apply globally; scopes can be added later. */
public enum class UserRole {
    /** Everything, including user management. */
    ADMIN,

    /** Deploy, start and stop, read. */
    OPERATOR,

    /** Read only. */
    VIEWER,

    /** End user of an application built with Cringle; no management rights. */
    END_USER,
}

/** A user whose token was accepted. [roles] include the roles of the user's groups. */
public data class AuthenticatedUser(public val id: String, public val name: String, public val roles: Set<UserRole>)

/**
 * Minimal engine-wide access to user management for blocks: authenticate a token and check a role. The engine wires an
 * implementation once user management is available in the daemon.
 */
public interface UserManagementDriver : Driver {
    /** Returns the user the [token] belongs to, or `null` if the token is unknown, revoked or expired. */
    public suspend fun authenticate(token: String): AuthenticatedUser?

    /** Whether [user] has [role]. */
    public suspend fun hasRole(user: AuthenticatedUser, role: UserRole): Boolean
}
