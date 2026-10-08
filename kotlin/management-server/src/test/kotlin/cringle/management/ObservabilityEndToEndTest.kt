// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.contract.LogEntry
import cringle.contract.LogLevel
import cringle.engine.CringleHome
import cringle.engine.drivers.LoggingService
import cringle.engine.v1.DwhKind
import cringle.engine.v1.DwhRetention
import cringle.management.v1.GetMetricsRequest
import cringle.management.v1.ListDwhPartitionsRequest
import cringle.management.v1.QueryDwhRequest
import cringle.management.v1.QueryLogsRequest
import cringle.management.v1.SetDwhRetentionRequest
import cringle.management.v1.SetLogCollectionRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The M7 criterion (#196, `docs/observability.md`): the ManagementServer shows the logs and the metrics of all engines, and
 * tether messages are in the data warehouse with a retention. A service on one engine, the recorded project `recorded-app` (a block
 * sends to another through a tether with a `record`) on another; everything is read through the API of the ManagementServer.
 */
@Tag("integration")
class ObservabilityEndToEndTest : ServiceTestBase() {
    private val fabric = "recorded-app-app-1"
    private val tether = "c.out -> s.in"
    private val home get() = dir.resolve("home")

    private fun await(what: String, seconds: Long = 90, condition: () -> Boolean) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out: $what" }
            Thread.sleep(200)
        }
    }

    private fun records(until: Instant? = null) = runBlocking {
        val b = QueryDwhRequest.newBuilder().setFabric(fabric).setKind(DwhKind.DWH_KIND_TETHER).setName(tether).setLimit(100_000)
        if (until != null) b.until = com.google.protobuf.Timestamp.newBuilder().setSeconds(until.epochSecond).build()
        s.queryDwh(b.build()).recordsList
    }

    @Test
    fun logsMetricsAndRecordedTetherMessagesOfAllEnginesAreShownByTheManagementServer() {
        deploy("orders-service")
        deploy("recorded-app")
        await("recorded messages") { records().size >= 5 }

        // --- logs: the entries of two engines, a line of a foreign process, and the collector for a stopped engine ---
        val t0 = Instant.parse("2026-01-01T10:00:00Z")
        LoggingService(CringleHome.engineDir(home, "e-svc")).append(LogEntry(t0, "orders-service-service-1", "s1", LogLevel.WARN, "the service says hello"))
        LoggingService(CringleHome.engineDir(home, "e-a")).append(LogEntry(t0.plusSeconds(1), fabric, "c", LogLevel.INFO, "the caller says hello"))
        val foreign = Files.createDirectories(CringleHome.engineDir(home, "e-a").resolve("fabrics/$fabric/logs/c"))
        Files.writeString(foreign.resolve("proc.log"), "2026-01-01T10:00:02Z ERROR from a foreign process\n")
        val logs = runBlocking { s.queryLogs(QueryLogsRequest.newBuilder().setLimit(10_000).build()) }
        val byMessage = logs.entriesList.associateBy { it.entry.message }
        assertEquals("e-svc", byMessage.getValue("the service says hello").engineId.value)
        assertEquals("e-a", byMessage.getValue("the caller says hello").engineId.value)
        val proc = byMessage.getValue("2026-01-01T10:00:02Z ERROR from a foreign process")
        assertEquals("proc.log", proc.entry.source)
        assertEquals(cringle.engine.v1.LogLevel.LOG_LEVEL_ERROR, proc.entry.level)

        // --- metrics of all engines: the counters of the tether match what was recorded ---
        val recordedBefore = records().size
        val metrics = runBlocking { s.getMetrics(GetMetricsRequest.getDefaultInstance()) }
        assertTrue(metrics.metricsList.map { it.engineId.value }.containsAll(listOf("e-svc", "e-a")), metrics.metricsList.map { it.engineId.value }.toString())
        assertEquals(emptyList<String>(), metrics.problemsList)
        val engineA = metrics.metricsList.single { it.engineId.value == "e-a" }.metrics
        val counted = engineA.fabricsList.single { it.fabricId.value == fabric }.tethersList.single { it.tetherId == tether }
        assertTrue(counted.messages >= recordedBefore, "the tether counted ${counted.messages}, the DWH had $recordedBefore")
        assertTrue(engineA.heapUsedBytes > 0)

        // --- the heartbeat carries a few numbers to the router ---
        await("vitals at the router") { daemon.router!!.registry.engines().single { it.record.id == "e-a" }.vitals?.fabricCount == 1 }
        val vitals = daemon.router!!.registry.engines().single { it.record.id == "e-a" }.vitals!!
        assertEquals(1, vitals.runningFabricCount)
        assertTrue(vitals.memoryUsedBytes > 0)

        // --- the DWH: the recorded tether with the retention of the blueprint, listed through the ManagementServer ---
        val partition = runBlocking { s.listDwhPartitions(ListDwhPartitionsRequest.newBuilder().setFabric(fabric).build()).partitionsList.single().partition }
        assertEquals(tether, partition.name)
        assertEquals(1_000_000, partition.retention.maxBytes)

        // --- retention removes old data: two records of an old day, a maxAge of one day, the engine applies it every minute ---
        val folder = Files.list(CringleHome.engineDir(home, "e-a").resolve("dwh/$fabric/tether")).use { it.toList().single() }
        Files.writeString(
            folder.resolve("2020-01-01.jsonl"),
            "{\"t\":\"2020-01-01T10:00:00Z\",\"v\":\"old one\"}\n{\"t\":\"2020-01-01T10:00:01Z\",\"v\":\"old two\"}\n",
        )
        val until = Instant.parse("2021-01-01T00:00:00Z")
        assertEquals(listOf("\"old one\"", "\"old two\""), records(until).map { it.payloadJson })
        runBlocking {
            s.setDwhRetention(
                SetDwhRetentionRequest.newBuilder().setFabric(fabric).setKind(DwhKind.DWH_KIND_TETHER).setName(tether)
                    .setRetention(DwhRetention.newBuilder().setMaxAgeMs(86_400_000)).build(),
            )
        }
        await("the old records to be removed by the retention", seconds = 150) { records(until).isEmpty() }
        assertTrue(records().isNotEmpty(), "today's records stay")

        // --- the collector keeps the logs of a stopped engine ---
        runBlocking { s.setLogCollection(SetLogCollectionRequest.newBuilder().setEngine(ref("e-svc")).setEnabled(true).build()) }
        assertTrue(daemon.collectLogsNow() >= 1)
        runBlocking { s.stopEngine(ref("e-svc")) }
        val kept = runBlocking { s.queryLogs(QueryLogsRequest.newBuilder().setEngine(ref("e-svc")).build()) }
        assertTrue(kept.entriesList.any { it.entry.message == "the service says hello" && it.collected }, kept.entriesList.map { it.entry.message }.toString())
    }
}
