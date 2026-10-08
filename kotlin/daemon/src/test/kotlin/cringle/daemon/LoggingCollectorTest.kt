// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import cringle.contract.LogEntry
import cringle.contract.LogLevel
import cringle.engine.drivers.LogQuery
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** The LoggingCollector of a machine (#195): a fake engine instead of a process. */
class LoggingCollectorTest {
    @TempDir
    lateinit var dir: Path

    private val t0 = Instant.parse("2026-10-01T10:00:00Z")
    private var state = ProcessState.RUNNING
    private val available = ArrayList<LogEntry>()

    private fun engine(id: String) = EngineSnapshot(id, id, state, 1, if (state == ProcessState.RUNNING) 4711 else 0, null, 0, "")

    private fun entry(at: Long, message: String, block: String = "a") = LogEntry(t0.plusSeconds(at), "shop", block, LogLevel.INFO, message)

    private fun collector(maxBytes: Long = LoggingCollector.MAX_BYTES, keepBytes: Long = LoggingCollector.KEEP_BYTES) = LoggingCollector(
        dir,
        { listOf(engine("e1"), engine("e2")) },
        { _, since, limit -> available.filter { since == null || !it.timestamp.isBefore(since) }.take(limit) },
        maxBytes, keepBytes,
    )

    private fun messages(c: LoggingCollector, id: String = "e1") = c.query(id, LogQuery()).map { it.message }

    @Test
    fun onlyTheEnginesItIsSwitchedOnForAreCollectedAndTheSwitchIsKept() {
        available += entry(1, "one")
        val c = collector()
        assertEquals(0, c.collectOnce())
        c.setEnabled("e1", true)
        assertTrue(c.enabled("e1") && !c.enabled("e2"))
        assertEquals(1, c.collectOnce())
        assertEquals(listOf("one"), messages(c))
        assertEquals(emptyList<String>(), messages(c, "e2"))
        // a new collector (the daemon restarted) knows the switch
        assertTrue(collector().enabled("e1"))
        c.setEnabled("e1", false)
        assertFalse(collector().enabled("e1"))
        // what was kept stays
        assertEquals(listOf("one"), messages(c))
    }

    @Test
    fun everyEntryIsAddedOnceEvenWhenTheNextRoundStartsAtTheLastTimestamp() {
        val c = collector()
        c.setEnabled("e1", true)
        available += entry(1, "one")
        available += entry(2, "two-a")
        assertEquals(2, c.collectOnce())
        assertEquals(0, c.collectOnce(), "nothing new")
        // a second entry at the last timestamp and a later one
        available += entry(2, "two-b", block = "b")
        available += entry(3, "three")
        assertEquals(2, c.collectOnce())
        assertEquals(listOf("one", "two-a", "two-b", "three").sorted(), messages(c).sorted())
        assertEquals(0, c.collectOnce())
    }

    @Test
    fun aStoppedEngineIsNotAskedButItsEarlierEntriesCanStillBeRead() {
        val c = collector()
        c.setEnabled("e1", true)
        available += entry(1, "before")
        c.collectOnce()
        state = ProcessState.STOPPED
        available += entry(2, "while stopped")
        assertEquals(0, c.collectOnce())
        assertEquals(listOf("before"), messages(c))
    }

    @Test
    fun anEngineThatCannotBeAskedDoesNotStopTheRound() {
        val failing = LoggingCollector(dir, { listOf(engine("e1"), engine("e2")) }, { e, _, _ -> if (e.id == "e1") error("down") else listOf(entry(1, "from e2")) })
        failing.setEnabled("e1", true)
        failing.setEnabled("e2", true)
        assertEquals(1, failing.collectOnce())
        assertEquals(listOf("from e2"), messages(failing, "e2"))
    }

    @Test
    fun theFileIsCutToTheNewestEntriesWhenItGrowsTooBig() {
        val c = collector(maxBytes = 2000, keepBytes = 1000)
        c.setEnabled("e1", true)
        repeat(60) { available += entry(it.toLong(), "message number %03d with some padding to make it longer".format(it)) }
        c.collectOnce()
        val kept = messages(c)
        val file = dir.resolve("collected/e1/logs/engine.log")
        assertTrue(Files.size(file) <= 1000, "size ${Files.size(file)}")
        assertTrue(kept.isNotEmpty() && kept.last().startsWith("message number 059"), kept.toString())
        assertFalse(kept.any { it.startsWith("message number 000") })
    }

    @Test
    fun anInvalidEngineIdIsRefused() {
        assertThrows<IllegalArgumentException> { collector().query("../x", LogQuery()) }
    }
}
