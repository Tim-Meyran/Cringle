// SPDX-License-Identifier: Apache-2.0

package cringle.engine.drivers

import cringle.contract.Driver
import cringle.contract.DriverType
import cringle.contract.LogEntry
import cringle.contract.LogLevel
import cringle.contract.LoggingDriver
import cringle.contract.BuiltinDriverTypes
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Filter for [LoggingService.query]; every field that is set narrows the result. */
public data class LogQuery(
    val fabric: String? = null,
    val block: String? = null,
    val minLevel: LogLevel = LogLevel.DEBUG,
    val since: Instant? = null,
    /** At most this many entries are returned: the newest ones, oldest first. */
    val limit: Int = 1000,
) {
    init {
        require(limit >= 1) { "limit must be at least 1" }
    }
}

/**
 * The engine-wide logging service and its local, persistent log store: `<engine dir>/logs/engine.log`, one entry per
 * line (tab separated, control characters escaped). Every block gets a [LoggingDriver] bound to its fabric and block
 * through [driverFor]; entries can optionally be mirrored into a per-block log directory.
 */
public class LoggingService(engineDir: Path) {
    private val file: Path = engineDir.resolve("logs").resolve("engine.log")
    private val lock = Any()

    /** Appends [entry] to the store and, if given, to `<blockLogDir>/block.log`. */
    public fun append(entry: LogEntry, blockLogDir: Path? = null) {
        val line = encode(entry) + "\n"
        synchronized(lock) {
            Files.createDirectories(file.parent)
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            if (blockLogDir != null) {
                Files.createDirectories(blockLogDir)
                val text = "${entry.timestamp} ${entry.level} ${entry.message.replace("\n", "\n    ")}\n"
                Files.writeString(blockLogDir.resolve("block.log"), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }
    }

    /** Returns the entries that match [query], oldest first. */
    public fun query(query: LogQuery = LogQuery()): List<LogEntry> {
        val all = synchronized(lock) { if (Files.exists(file)) Files.readAllLines(file, StandardCharsets.UTF_8) else emptyList() }
        return all.asSequence()
            .mapNotNull { decode(it) }
            .filter { query.fabric == null || it.fabric == query.fabric }
            .filter { query.block == null || it.block == query.block }
            .filter { it.level >= query.minLevel }
            .filter { query.since == null || !it.timestamp.isBefore(query.since) }
            .toList()
            .takeLast(query.limit)
    }

    /** A driver whose entries are tagged with [fabric] and [block]. */
    public fun driverFor(fabric: String, block: String, blockLogDir: Path? = null): LoggingDriver = Bound(fabric, block, blockLogDir)

    private inner class Bound(private val fabric: String, private val block: String, private val dir: Path?) : LoggingDriver {
        override val type: DriverType = BuiltinDriverTypes.LOGGING

        override suspend fun log(level: LogLevel, message: String, error: Throwable?) {
            val text = if (error == null) message else message + "\n" + stackTrace(error)
            withContext(Dispatchers.IO) { append(LogEntry(Instant.now(), fabric, block, level, text), dir) }
        }
    }

    private fun stackTrace(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString().trimEnd()

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(
                    when (s[i + 1]) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> s[i + 1]
                    },
                )
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun encode(e: LogEntry) = listOf(e.timestamp.toString(), e.level.name, e.fabric, e.block, e.message).joinToString("\t") { escape(it) }

    private fun decode(line: String): LogEntry? {
        val parts = line.split("\t")
        if (parts.size != 5) return null
        return try {
            LogEntry(Instant.parse(parts[0]), unescape(parts[2]), unescape(parts[3]), LogLevel.valueOf(parts[1]), unescape(parts[4]))
        } catch (_: RuntimeException) {
            null
        }
    }
}

