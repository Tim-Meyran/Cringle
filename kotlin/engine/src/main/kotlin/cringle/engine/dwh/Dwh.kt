// SPDX-License-Identifier: Apache-2.0

package cringle.engine.dwh

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What a partition of the DWH belongs to: a block or a tether of a fabric. */
public enum class DwhKind { BLOCK, TETHER }

/** One partition of the DWH: the records of a block or of a tether of one fabric instance (Architecture 16.5). */
public data class DwhPartition(val fabric: String, val kind: DwhKind, val name: String) {
    init {
        require(fabric.isNotBlank() && name.isNotBlank()) { "fabric and name of a partition must not be blank" }
    }
}

/** One record: when it was written, its JSON payload and free tags. */
public data class DwhRecord(val timestamp: Instant, val payload: JsonElement, val tags: Map<String, String> = emptyMap())

/** How long and how much of a partition is kept; `null` means no limit. */
public data class Retention(val maxAge: Duration? = null, val maxBytes: Long? = null) {
    init {
        require(maxAge == null || (!maxAge.isNegative && !maxAge.isZero)) { "maxAge must be positive" }
        require(maxBytes == null || maxBytes > 0) { "maxBytes must be positive" }
    }
}

/** A partition with its size and retention, as [Dwh.partitions] lists it. */
public data class DwhPartitionInfo(val partition: DwhPartition, val bytes: Long, val retention: Retention)

/**
 * The data warehouse of one engine (#188, Architecture 16.5): one store with logical partitions per fabric instance and
 * block or tether, so that rights, retention and deletion work per partition. A partition is a folder
 * `<dir>/<fabric>/<block|tether>/<name>/` with one file per day (`yyyy-MM-dd.jsonl`, UTC, one record per line) and a
 * `meta.json` with the real name and the retention. A day file is the unit of retention, so a limit is kept to the
 * granularity of a day (a size limit is `maxBytes` plus up to one day file).
 */
public class Dwh(private val dir: Path, private val clock: Clock = Clock.systemUTC()) {
    private val lock = Any()
    private val json = Json

    /** Appends [record] to [partition]; the day file is chosen by the timestamp of the record. */
    public fun append(partition: DwhPartition, record: DwhRecord) {
        val line = buildJsonObject {
            put("t", record.timestamp.toString())
            put("v", record.payload)
            if (record.tags.isNotEmpty()) put("tags", JsonObject(record.tags.mapValues { JsonPrimitive(it.value) }))
        }.toString() + "\n"
        synchronized(lock) {
            val folder = folder(partition)
            Files.createDirectories(folder)
            ensureMeta(folder, partition)
            val day = LocalDate.ofInstant(record.timestamp, ZoneOffset.UTC)
            Files.writeString(folder.resolve("$day.jsonl"), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    /** The records of [partition] in the range [since] to [until] (both inclusive, both optional): the newest [limit], oldest first. */
    public fun query(partition: DwhPartition, since: Instant? = null, until: Instant? = null, limit: Int = 1000): List<DwhRecord> {
        require(limit >= 1) { "limit must be at least 1" }
        val files = synchronized(lock) { dayFiles(folder(partition)) }
        val result = ArrayList<DwhRecord>()
        for ((day, file) in files) {
            if (since != null && day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() <= since) continue
            if (until != null && day.atStartOfDay(ZoneOffset.UTC).toInstant() > until) continue
            val lines = synchronized(lock) { if (Files.exists(file)) Files.readAllLines(file, StandardCharsets.UTF_8) else emptyList() }
            for (line in lines) {
                val record = decode(line) ?: continue
                if (since != null && record.timestamp < since) continue
                if (until != null && record.timestamp > until) continue
                result += record
            }
        }
        result.sortBy { it.timestamp }
        return result.takeLast(limit)
    }

    /** Sets the retention of [partition]; it is kept in the partition and applied by [applyRetention]. */
    public fun setRetention(partition: DwhPartition, retention: Retention) {
        synchronized(lock) {
            val folder = folder(partition)
            Files.createDirectories(folder)
            writeMeta(folder, partition, retention)
        }
    }

    /** The retention of [partition] ([Retention] without limits if none was set or the partition is unknown). */
    public fun retentionOf(partition: DwhPartition): Retention = synchronized(lock) { readMeta(folder(partition))?.second ?: Retention() }

    /** The size of the day files of [partition] in bytes. */
    public fun sizeOf(partition: DwhPartition): Long = synchronized(lock) { dayFiles(folder(partition)).sumOf { Files.size(it.second) } }

    /** Every partition of the store with size and retention, sorted by fabric, kind and name. */
    public fun partitions(): List<DwhPartitionInfo> = synchronized(lock) {
        if (!Files.isDirectory(dir)) return@synchronized emptyList()
        val result = ArrayList<DwhPartitionInfo>()
        Files.walk(dir, 4).use { stream ->
            stream.filter { it.fileName.toString() == META && it.nameCount == dir.nameCount + 4 }.forEach { meta ->
                readMeta(meta.parent)?.let { (partition, retention) -> result += DwhPartitionInfo(partition, dayFiles(meta.parent).sumOf { Files.size(it.second) }, retention) }
            }
        }
        result.sortedWith(compareBy({ it.partition.fabric }, { it.partition.kind }, { it.partition.name }))
    }

    /** Removes [partition] with all its records; returns whether it existed. */
    public fun delete(partition: DwhPartition): Boolean = synchronized(lock) {
        val folder = folder(partition)
        if (!Files.isDirectory(folder)) return@synchronized false
        Files.walk(folder).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        true
    }

    /**
     * Applies the retention of every partition: day files that are entirely older than `maxAge` go, then the oldest day
     * files go until the partition is within `maxBytes` (the newest day file always stays). Returns the number of files removed.
     */
    public fun applyRetention(): Int {
        var removed = 0
        for (info in partitions()) {
            val r = info.retention
            synchronized(lock) {
                val folder = folder(info.partition)
                var files = dayFiles(folder)
                if (r.maxAge != null) {
                    val cutoff = clock.instant().minus(r.maxAge)
                    for ((day, file) in files) {
                        if (day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() <= cutoff && Files.deleteIfExists(file)) removed++
                    }
                    files = dayFiles(folder)
                }
                if (r.maxBytes != null) {
                    var total = files.sumOf { Files.size(it.second) }
                    for ((_, file) in files.dropLast(1)) {
                        if (total <= r.maxBytes) break
                        total -= Files.size(file)
                        if (Files.deleteIfExists(file)) removed++
                    }
                }
            }
        }
        return removed
    }

    // ---- files ----

    private fun folder(p: DwhPartition): Path = dir.resolve(segment(p.fabric)).resolve(p.kind.name.lowercase()).resolve(segment(p.name))

    /** A folder name for [name]: the name itself if it is harmless, else a cleaned name with a hash, so that no name can leave the store. */
    private fun segment(name: String): String {
        if (SAFE.matches(name) && !name.startsWith(".")) return name
        val hash = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(StandardCharsets.UTF_8)).take(4).joinToString("") { "%02x".format(it) }
        val cleaned = name.map { if (it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_') it else '_' }.joinToString("").take(48)
        return "$cleaned-$hash"
    }

    private fun dayFiles(folder: Path): List<Pair<LocalDate, Path>> {
        if (!Files.isDirectory(folder)) return emptyList()
        return Files.list(folder).use { s ->
            s.filter { DAY_FILE.matches(it.fileName.toString()) }.map { LocalDate.parse(it.fileName.toString().removeSuffix(".jsonl")) to it }.toList()
        }.sortedBy { it.first }
    }

    private fun ensureMeta(folder: Path, partition: DwhPartition) {
        if (!Files.exists(folder.resolve(META))) writeMeta(folder, partition, Retention())
    }

    private fun writeMeta(folder: Path, partition: DwhPartition, retention: Retention) {
        val text = buildJsonObject {
            put("fabric", partition.fabric)
            put("kind", partition.kind.name)
            put("name", partition.name)
            put("maxAgeMillis", retention.maxAge?.toMillis()?.let { JsonPrimitive(it) } ?: JsonNull)
            put("maxBytes", retention.maxBytes?.let { JsonPrimitive(it) } ?: JsonNull)
        }.toString()
        val tmp = folder.resolve("$META.tmp")
        Files.writeString(tmp, text, StandardCharsets.UTF_8)
        Files.move(tmp, folder.resolve(META), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun readMeta(folder: Path): Pair<DwhPartition, Retention>? {
        val file = folder.resolve(META)
        if (!Files.exists(file)) return null
        return try {
            val o = json.parseToJsonElement(Files.readString(file, StandardCharsets.UTF_8)).jsonObject
            val partition = DwhPartition(o.getValue("fabric").jsonPrimitive.content, DwhKind.valueOf(o.getValue("kind").jsonPrimitive.content), o.getValue("name").jsonPrimitive.content)
            val age = o["maxAgeMillis"]?.jsonPrimitive?.longOrNull?.let { Duration.ofMillis(it) }
            val bytes = o["maxBytes"]?.jsonPrimitive?.longOrNull
            partition to Retention(age, bytes)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun decode(line: String): DwhRecord? = try {
        val o = json.parseToJsonElement(line).jsonObject
        val tags = (o["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() } ?: emptyMap()
        DwhRecord(Instant.parse(o.getValue("t").jsonPrimitive.content), o.getValue("v"), tags)
    } catch (_: RuntimeException) {
        null
    }

    private companion object {
        const val META = "meta.json"
        val SAFE = Regex("[A-Za-z0-9._-]{1,64}")
        val DAY_FILE = Regex("""\d{4}-\d{2}-\d{2}\.jsonl""")
    }
}
