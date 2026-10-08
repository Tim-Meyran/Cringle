// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import cringle.engine.EngineArgs
import cringle.contract.LogEntry
import cringle.contract.LogLevel
import cringle.engine.drivers.LogQuery
import cringle.engine.drivers.LoggingService
import cringle.engine.v1.EngineManagementServiceGrpc
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The LoggingCollector of a machine (#195, Architecture 16.1). For the engines it is switched on for ([setEnabled]; kept in
 * `<daemon dir>/collector.json`) it pulls the entries of the logging driver of a running engine every [INTERVAL_SECONDS] seconds
 * and keeps them in `<daemon dir>/collected/<engine>/logs/engine.log` (the format of the engine's own store), so that they can be read
 * when the engine is stopped or gone. The file is cut to the newest [KEEP_BYTES] when it grows past [MAX_BYTES]. Lines of foreign log
 * files are not collected (they stay in the log folders on the machine and are part of the log query while the engine runs).
 */
internal class LoggingCollector(
    private val daemonDir: Path,
    private val engines: () -> List<EngineSnapshot>,
    /** The entries of the logging driver of [engine] since [since] (inclusive), at most [limit]; replaceable for tests. */
    private val fetch: (engine: EngineSnapshot, since: Instant?, limit: Int) -> List<LogEntry>,
    private val maxBytes: Long = MAX_BYTES,
    private val keepBytes: Long = KEEP_BYTES,
) : AutoCloseable {
    private val lock = Any()
    private val stateFile = daemonDir.resolve("collector.json")
    private val stores = HashMap<String, LoggingService>()
    private var scheduler: ScheduledExecutorService? = null

    /** Starts the periodic collection. */
    fun start() {
        synchronized(lock) {
            if (scheduler != null) return
            scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "logging-collector").also { it.isDaemon = true } }.also {
                it.scheduleWithFixedDelay({ runCatching { collectOnce() } }, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS)
            }
        }
    }

    /** Whether the collector is switched on for [engineId]. */
    fun enabled(engineId: String): Boolean = engineId in load()

    /** Switches the collector on or off for [engineId]; what was collected stays. */
    fun setEnabled(engineId: String, enabled: Boolean) {
        synchronized(lock) {
            val ids = load().toMutableSet()
            if (enabled) ids += engineId else ids -= engineId
            Files.createDirectories(daemonDir)
            val text = buildJsonObject { put("engines", JsonArray(ids.sorted().map { JsonPrimitive(it) })) }.toString()
            val tmp = stateFile.resolveSibling("collector.json.tmp")
            Files.writeString(tmp, text, StandardCharsets.UTF_8)
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** What the collector has kept of [engineId]. */
    fun query(engineId: String, query: LogQuery): List<LogEntry> = store(engineId).query(query)

    /** One round: pulls the new entries of every collected, running engine. Returns the number of entries added. */
    fun collectOnce(): Int {
        var added = 0
        val running = engines().filter { it.state == ProcessState.RUNNING && it.managementPort != 0 }.associateBy { it.id }
        for (id in load()) {
            val engine = running[id] ?: continue
            val store = store(id)
            val kept = store.query(LogQuery(limit = RECENT)).filter { it.source.isEmpty() }
            val last = kept.maxOfOrNull { it.timestamp }
            val known = kept.filter { it.timestamp == last }.map { Triple(it.fabric, it.block, it.message) }.toSet()
            val entries = try {
                fetch(engine, last, FETCH_LIMIT)
            } catch (_: Exception) {
                continue
            }
            for (e in entries.sortedBy { it.timestamp }) {
                if (e.timestamp == last && Triple(e.fabric, e.block, e.message) in known) continue
                store.append(e)
                added++
            }
            if (entries.isNotEmpty()) trim(daemonDir.resolve("collected").resolve(id).resolve("logs").resolve("engine.log"))
        }
        return added
    }

    private fun store(engineId: String): LoggingService = synchronized(lock) {
        stores.getOrPut(engineId) { LoggingService(daemonDir.resolve("collected").resolve(engineId.also { require(EngineArgs.idPattern.matches(it)) { "invalid engine id" } })) }
    }

    private fun load(): Set<String> = synchronized(lock) {
        if (!Files.exists(stateFile)) return emptySet()
        try {
            Json.parseToJsonElement(Files.readString(stateFile, StandardCharsets.UTF_8)).jsonObject["engines"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
        } catch (_: RuntimeException) {
            emptySet()
        }
    }

    /** Cuts [file] to its newest [KEEP_BYTES] when it is bigger than [MAX_BYTES]. */
    private fun trim(file: Path) {
        synchronized(lock) {
            if (!Files.exists(file) || Files.size(file) <= maxBytes) return
            val lines = Files.readAllLines(file, StandardCharsets.UTF_8)
            var size = 0L
            val keep = ArrayList<String>()
            for (line in lines.asReversed()) {
                size += line.toByteArray(StandardCharsets.UTF_8).size + 1
                if (size > keepBytes) break
                keep += line
            }
            val tmp = file.resolveSibling("engine.log.tmp")
            Files.write(tmp, keep.asReversed(), StandardCharsets.UTF_8)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    override fun close() {
        synchronized(lock) {
            scheduler?.shutdownNow()
            scheduler = null
        }
    }

    companion object {
        const val INTERVAL_SECONDS: Long = 10
        const val MAX_BYTES: Long = 60L * 1024 * 1024
        const val KEEP_BYTES: Long = 50L * 1024 * 1024
        private const val FETCH_LIMIT = 5000
        private const val RECENT = 200

        /** The default [fetch]: asks the management API of the engine over mutual TLS. */
        fun overGrpc(credentials: () -> SslContext): (EngineSnapshot, Instant?, Int) -> List<LogEntry> = { engine, since, limit ->
            val channel = NettyChannelBuilder.forAddress("127.0.0.1", engine.managementPort).sslContext(credentials()).build()
            try {
                val request = cringle.engine.v1.QueryLogsRequest.newBuilder().setDriverOnly(true).setLimit(limit)
                if (since != null) request.since = com.google.protobuf.Timestamp.newBuilder().setSeconds(since.epochSecond).setNanos(since.nano).build()
                EngineManagementServiceGrpc.newBlockingStub(channel).withDeadlineAfter(30, TimeUnit.SECONDS).queryLogs(request.build()).entriesList.map {
                    LogEntry(
                        Instant.ofEpochSecond(it.timestamp.seconds, it.timestamp.nanos.toLong()), it.fabric, it.block,
                        when (it.level) {
                            cringle.engine.v1.LogLevel.LOG_LEVEL_DEBUG -> LogLevel.DEBUG
                            cringle.engine.v1.LogLevel.LOG_LEVEL_WARN -> LogLevel.WARN
                            cringle.engine.v1.LogLevel.LOG_LEVEL_ERROR -> LogLevel.ERROR
                            else -> LogLevel.INFO
                        },
                        it.message,
                    )
                }
            } finally {
                channel.shutdownNow()
            }
        }
    }
}
