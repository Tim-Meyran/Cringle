// SPDX-License-Identifier: Apache-2.0

package cringle.engine.dwh

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The data warehouse store (#188): partitions, queries, retention. */
class DwhTest {
    @TempDir
    lateinit var dir: Path

    private val t0 = Instant.parse("2026-10-01T10:00:00Z")
    private var now = t0
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = now
    }
    private val block = DwhPartition("f1", DwhKind.BLOCK, "b1")
    private val tether = DwhPartition("f1", DwhKind.TETHER, "s.out -> d.in")

    private fun dwh() = Dwh(dir.resolve("dwh"), clock)

    private fun rec(at: Instant, v: String) = DwhRecord(at, JsonPrimitive(v), mapOf("kind" to "message"))

    @Test
    fun recordsComeBackInOrderWithTagsAndTheNewestLimitWins() {
        val d = dwh()
        for (i in 0 until 5) d.append(block, rec(t0.plusSeconds(i.toLong()), "r$i"))
        assertEquals((0 until 5).map { "r$it" }, d.query(block).map { (it.payload as JsonPrimitive).content })
        assertEquals(listOf("r3", "r4"), d.query(block, limit = 2).map { (it.payload as JsonPrimitive).content })
        assertEquals(listOf("r1", "r2"), d.query(block, since = t0.plusSeconds(1), until = t0.plusSeconds(2)).map { (it.payload as JsonPrimitive).content })
        assertEquals(mapOf("kind" to "message"), d.query(block, limit = 1).single().tags)
    }

    @Test
    fun partitionsAreSeparateAndNamesCannotLeaveTheStore() {
        val d = dwh()
        d.append(block, rec(t0, "block"))
        d.append(tether, rec(t0, "tether"))
        val evil = DwhPartition("../../x", DwhKind.BLOCK, "..")
        d.append(evil, rec(t0, "evil"))
        assertEquals(listOf("block"), d.query(block).map { (it.payload as JsonPrimitive).content })
        assertEquals(listOf("tether"), d.query(tether).map { (it.payload as JsonPrimitive).content })
        assertEquals(listOf("evil"), d.query(evil).map { (it.payload as JsonPrimitive).content })
        // everything is below the store folder, and the real names are listed
        Files.walk(dir).use { s -> assertTrue(s.allMatch { it.startsWith(dir.resolve("dwh")) || it == dir }) }
        assertFalse(Files.exists(dir.resolve("x")))
        assertEquals(setOf(block, tether, evil), d.partitions().map { it.partition }.toSet())
    }

    @Test
    fun dataSurvivesOpeningTheStoreAgain() {
        dwh().apply { append(block, rec(t0, "kept")); setRetention(block, Retention(Duration.ofDays(2), 1000)) }
        val again = dwh()
        assertEquals(listOf("kept"), again.query(block).map { (it.payload as JsonPrimitive).content })
        assertEquals(Retention(Duration.ofDays(2), 1000), again.retentionOf(block))
        assertEquals(Retention(), again.retentionOf(tether))
    }

    @Test
    fun deleteRemovesAPartition() {
        val d = dwh()
        d.append(block, rec(t0, "x"))
        d.append(tether, rec(t0, "y"))
        assertTrue(d.delete(block))
        assertFalse(d.delete(block))
        assertEquals(emptyList<DwhRecord>(), d.query(block))
        assertEquals(1, d.query(tether).size)
        assertEquals(listOf(tether), d.partitions().map { it.partition })
    }

    @Test
    fun oldDayFilesGoWithTheirTimeLimit() {
        val d = dwh()
        d.setRetention(block, Retention(maxAge = Duration.ofDays(2)))
        for (day in 0 until 5) d.append(block, rec(t0.plus(Duration.ofDays(day.toLong())), "day$day"))
        now = t0.plus(Duration.ofDays(4)).plusSeconds(60)
        assertEquals(2, d.applyRetention())
        // the day files of day 0 and day 1 are entirely older than two days; day 2 ends after the limit and stays
        assertEquals(listOf("day2", "day3", "day4"), d.query(block).map { (it.payload as JsonPrimitive).content })
        // another partition without limits is not touched
        d.append(tether, rec(t0, "old"))
        assertEquals(0, d.applyRetention())
        assertEquals(1, d.query(tether).size)
    }

    @Test
    fun theOldestDayFilesGoWithTheirSizeLimitAndTheNewestStays() {
        val d = dwh()
        val big = "x".repeat(500)
        for (day in 0 until 4) repeat(2) { d.append(block, rec(t0.plus(Duration.ofDays(day.toLong())).plusSeconds(it.toLong()), big)) }
        val size = d.sizeOf(block)
        d.setRetention(block, Retention(maxBytes = size / 2 + 1))
        d.applyRetention()
        assertTrue(d.sizeOf(block) <= size / 2 + 600, "within the limit plus one day file")
        val days = d.query(block).map { it.timestamp.toString().take(10) }.distinct()
        assertTrue("2026-10-04" in days, "the newest day stays: $days")
        assertFalse("2026-10-01" in days)
        // a limit below one file keeps the newest file
        d.setRetention(block, Retention(maxBytes = 1))
        d.applyRetention()
        assertEquals(listOf("2026-10-04"), d.query(block).map { it.timestamp.toString().take(10) }.distinct())
    }

    @Test
    fun invalidRetentionAndLimitsAreRejected() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { Retention(maxAge = Duration.ZERO) }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { Retention(maxBytes = 0) }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { dwh().query(block, limit = 0) }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { DwhPartition(" ", DwhKind.BLOCK, "b") }
    }
}
