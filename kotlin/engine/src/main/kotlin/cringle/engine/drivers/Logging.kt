// SPDX-License-Identifier: Apache-2.0

package cringle.engine.drivers

import cringle.contract.Driver
import cringle.contract.DriverType
import cringle.contract.LogEntry
import cringle.contract.LogLevel
import cringle.contract.LoggingDriver
import cringle.contract.BuiltinDriverTypes
import cringle.engine.fabric.FabricPaths
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
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
public class LoggingService(private val engineDir: Path) {
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
        return (all.asSequence().mapNotNull { decode(it) } + foreignEntries(query).asSequence())
            .sortedBy { it.timestamp }
            .filter { query.fabric == null || it.fabric == query.fabric }
            .filter { query.block == null || it.block == query.block }
            .filter { it.level >= query.minLevel }
            .filter { query.since == null || !it.timestamp.isBefore(query.since) }
            .toList()
            .takeLast(query.limit)
    }

    /**
     * The lines of the `*.log` files that foreign processes wrote into the log folders of blocks (#189): every file in
     * `<fabric>/logs/<block>/` except `block.log` (which only mirrors the store). At most [MAX_FOREIGN_FILES] files per block and the
     * last [MAX_FOREIGN_BYTES] of each are read; names outside `[A-Za-z0-9._-]+` and symbolic links are skipped.
     */
    private fun foreignEntries(query: LogQuery): List<LogEntry> {
        val fabricsDir = engineDir.resolve("fabrics")
        if (!Files.isDirectory(fabricsDir)) return emptyList()
        val result = ArrayList<LogEntry>()
        for (fabric in query.fabric?.let { listOf(it) } ?: subdirectories(fabricsDir)) {
            val logs = try {
                FabricPaths(engineDir, fabric).logs
            } catch (_: RuntimeException) {
                continue
            }
            if (!Files.isDirectory(logs, LinkOption.NOFOLLOW_LINKS)) continue
            for (block in query.block?.let { listOf(it) } ?: subdirectories(logs)) {
                val dir = try {
                    FabricPaths(engineDir, fabric).blockLogs(block)
                } catch (_: RuntimeException) {
                    continue
                }
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue
                val files = Files.list(dir).use { s ->
                    s.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && FOREIGN_NAME.matches(it.fileName.toString()) && it.fileName.toString() != "block.log" }
                        .sorted().limit(MAX_FOREIGN_FILES.toLong()).toList()
                }
                for (file in files) result += readForeign(file, fabric, block)
            }
        }
        return result
    }

    private fun subdirectories(dir: Path): List<String> =
        Files.list(dir).use { s -> s.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.map { it.fileName.toString() }.sorted().toList() }

    private fun readForeign(file: Path, fabric: String, block: String): List<LogEntry> {
        val modified = Files.getLastModifiedTime(file).toInstant()
        val size = Files.size(file)
        val text = RandomAccessFile(file.toFile(), "r").use { f ->
            val start = maxOf(0L, size - MAX_FOREIGN_BYTES)
            f.seek(start)
            val bytes = ByteArray((size - start).toInt())
            f.readFully(bytes)
            String(bytes, StandardCharsets.UTF_8).let { if (start > 0) it.substringAfter('\n', "") else it }
        }
        return text.lineSequence().filter { it.isNotBlank() }.map { parseForeign(it, fabric, block, file.fileName.toString(), modified) }.toList()
    }

    private fun parseForeign(line: String, fabric: String, block: String, source: String, modified: Instant): LogEntry {
        val stamped = TIMESTAMP.matchEntire(line)
        val time = stamped?.let { runCatching { Instant.parse(it.groupValues[1]) }.getOrNull() }
        val rest = if (time != null) stamped!!.groupValues[2] else line
        val level = LEVEL.find(rest)?.let { LogLevel.valueOf(it.groupValues[1].uppercase()) } ?: LogLevel.INFO
        return LogEntry(time ?: modified, fabric, block, level, line, source)
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

    private companion object {
        const val MAX_FOREIGN_FILES = 20
        const val MAX_FOREIGN_BYTES = 1024L * 1024L
        val FOREIGN_NAME = Regex("[A-Za-z0-9._-]+\\.log")
        val TIMESTAMP = Regex("^(\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z)\\s+(.*)$")
        val LEVEL = Regex("^\\s*\\[?(DEBUG|INFO|WARN|ERROR)\\b", RegexOption.IGNORE_CASE)
    }
}
