// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import cringle.contract.BuiltinDriverTypes
import cringle.contract.DriverType
import cringle.contract.DwhDriver
import cringle.contract.DwhEntry
import cringle.contract.LogLevel
import cringle.contract.LoggingDriver
import cringle.testkit.BlockTestHarness
import cringle.testkit.TestDriverSet
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LogDwhBlocksTest {
    private class RecordingLog : LoggingDriver {
        val lines = ArrayList<Pair<LogLevel, String>>()
        override val type: DriverType get() = BuiltinDriverTypes.LOGGING

        override suspend fun log(level: LogLevel, message: String, error: Throwable?) {
            lines += level to message
        }
    }

    private class MemoryDwh(private val canRead: Boolean = true) : DwhDriver {
        val entries = ArrayList<DwhEntry>()
        override val type: DriverType get() = BuiltinDriverTypes.DWH

        override suspend fun write(entry: DwhEntry) {
            entries += entry
        }

        override suspend fun read(since: Instant?, until: Instant?, limit: Int): List<DwhEntry> {
            if (!canRead) throw UnsupportedOperationException("this data warehouse driver cannot read")
            return entries.filter { (since == null || !it.timestamp.isBefore(since)) && (until == null || it.timestamp.isBefore(until)) }.takeLast(limit)
        }
    }

    private fun logHarness(driver: LoggingDriver, config: Map<String, Any?>) =
        BlockTestHarness.forProvider(StdBlockProvider(), "log.write", TestDriverSet().add(LoggingDriver::class, driver), config)

    private fun dwhHarness(name: String, driver: DwhDriver, config: Map<String, Any?>) =
        BlockTestHarness.forProvider(StdBlockProvider(), name, TestDriverSet().add(DwhDriver::class, driver), config)

    @Test
    fun logWriteUsesTheConfiguredLevelAndPassesTheValueOn() = runTest {
        val log = RecordingLog()
        val h = logHarness(log, mapOf("level" to "ERROR", "prefix" to "[job] "))
        h.runLifecycle {
            sendMessage("in", "plain text")
            sendMessage("in", mapOf("a" to listOf(1L, "x")))
            assertEquals(listOf(LogLevel.ERROR to "[job] plain text", LogLevel.ERROR to """[job] {"a":[1,"x"]}"""), log.lines)
            assertEquals(listOf<Any>("plain text", mapOf("a" to listOf(1L, "x"))), ports.tether("out").sentMessages, "the value goes on unchanged")
        }
        val defaults = RecordingLog()
        logHarness(defaults, emptyMap()).runLifecycle { sendMessage("in", 42L) }
        assertEquals(listOf(LogLevel.INFO to "42"), defaults.lines, "the level defaults to INFO")
    }

    @Test
    fun logWriteTemplateAndConfigurationErrors() = runTest {
        val log = RecordingLog()
        logHarness(log, mapOf("level" to "WARN", "template" to "value was {} today", "prefix" to "! ")).runLifecycle { sendMessage("in", true) }
        assertEquals(listOf(LogLevel.WARN to "! value was true today"), log.lines)
        assertThrows<IllegalArgumentException> { logHarness(log, mapOf("level" to "LOUD")).init() }
        assertThrows<IllegalArgumentException> { logHarness(log, mapOf("template" to "no placeholder")).init() }
    }

    @Test
    fun dwhWriteStoresTheValueWithKeyAndTags() = runTest {
        val dwh = MemoryDwh()
        dwhHarness("dwh.write", dwh, mapOf("key" to "temperature", "tags" to mapOf("room" to "a1"))).runLifecycle {
            sendMessage("in", 21.5)
            sendMessage("in", mapOf("min" to 19L))
            assertEquals(listOf<Any>(21.5, mapOf("min" to 19L)), ports.tether("out").sentMessages)
        }
        assertEquals(listOf<Any>(21.5, mapOf("min" to 19L)), dwh.entries.map { it.value })
        assertEquals(setOf("temperature"), dwh.entries.map { it.key }.toSet())
        assertEquals(mapOf("room" to "a1"), dwh.entries.first().tags)
        assertThrows<IllegalArgumentException> { dwhHarness("dwh.write", dwh, emptyMap()).init() }
        assertThrows<IllegalArgumentException> { dwhHarness("dwh.write", dwh, mapOf("key" to " ")).init() }
        assertThrows<IllegalArgumentException> { dwhHarness("dwh.write", dwh, mapOf("key" to "k", "tags" to mapOf("a" to 1L))).init() }
    }

    @Test
    fun dwhStoreWritesAndReadsBackItsOwnEntriesOldestFirst() = runTest {
        val dwh = MemoryDwh()
        val t0 = Instant.parse("2026-10-13T10:00:00.123Z")
        dwh.entries += DwhEntry("k", 2L, t0.plusSeconds(2), mapOf("t" to "x"))
        dwh.entries += DwhEntry("k", "text", t0)
        dwh.entries += DwhEntry("k", 1L, t0.plusSeconds(1))
        dwhHarness("dwh.store", dwh, mapOf("key" to "k")).runLifecycle {
            sendMessage("read", 1L)
            val list = ports.tether("entries").sentMessages.single() as List<*>
            assertEquals(listOf<Any?>("text", 1L, 2L), list.map { (it as Map<*, *>)["value"] })
            assertEquals(mapOf("key" to "k", "value" to "text", "timestamp" to "2026-10-13T10:00:00.123Z", "tags" to emptyMap<String, String>()), list.first())
            // a value that is written is in the next read
            sendMessage("in", "newest")
            assertEquals(listOf<Any>("newest"), ports.tether("out").sentMessages)
            sendMessage("read", 2L)
            assertEquals("newest", ((ports.tether("entries").sentMessages.last() as List<*>).last() as Map<*, *>)["value"])
        }
        dwhHarness("dwh.store", dwh, mapOf("key" to "k", "limit" to 1)).runLifecycle {
            sendMessage("read", 1L)
            assertEquals(1, (ports.tether("entries").sentMessages.single() as List<*>).size)
        }
    }

    @Test
    fun dwhStoreLastMsKeepsOnlyRecentEntriesAndAnUnreadableDriverAnswersOnError() = runTest {
        val dwh = MemoryDwh()
        dwh.entries += DwhEntry("k", "old", Instant.now().minusSeconds(3600))
        dwh.entries += DwhEntry("k", "new", Instant.now())
        dwhHarness("dwh.store", dwh, mapOf("key" to "k", "lastMs" to 60_000)).runLifecycle {
            sendMessage("read", 1L)
            assertEquals(listOf<Any?>("new"), (ports.tether("entries").sentMessages.single() as List<*>).map { (it as Map<*, *>)["value"] })
        }
        dwhHarness("dwh.store", MemoryDwh(canRead = false), mapOf("key" to "k")).runLifecycle {
            sendMessage("read", 1L)
            assertEquals(emptyList<Any>(), ports.tether("entries").sentMessages)
            assertEquals(listOf<Any>("this data warehouse driver cannot read"), ports.tether("error").sentMessages)
        }
        assertThrows<IllegalArgumentException> { dwhHarness("dwh.store", dwh, mapOf("key" to "k", "limit" to 0)).init() }
        assertThrows<IllegalArgumentException> { dwhHarness("dwh.store", dwh, mapOf("key" to "k", "lastMs" to 0)).init() }
        assertThrows<IllegalArgumentException> { dwhHarness("dwh.store", dwh, emptyMap()).init() }
    }
}
