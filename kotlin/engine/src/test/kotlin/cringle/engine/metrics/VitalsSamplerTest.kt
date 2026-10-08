// SPDX-License-Identifier: Apache-2.0

package cringle.engine.metrics

import cringle.contract.TetherType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The numbers of the heartbeat (#190). */
class VitalsSamplerTest {
    private var now = Instant.parse("2026-10-01T10:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = now
    }
    private var messages = 0L
    private var errors = 0L

    private fun fabric(id: String) = FabricStats(id, 1, errors, emptyList(), listOf(TetherStats("t", TetherType.MESSAGE, messages, 0, 0)))

    @Test
    fun countsAndMemoryComeFromTheFabricsAndTheJvm() {
        errors = 4
        val v = VitalsSampler({ listOf(fabric("a"), fabric("b")) }, { 1 }, clock).sample()
        assertEquals(2, v.fabricCount)
        assertEquals(1, v.runningFabricCount)
        assertEquals(8, v.errorCount)
        assertTrue(v.memoryUsedBytes > 0 && v.memoryUsedBytes <= v.memoryMaxBytes)
        assertTrue(v.cpuUsagePercent == -1.0 || v.cpuUsagePercent in 0.0..100.0)
    }

    @Test
    fun theThroughputIsTheMessagesSinceThePreviousSamplePerSecond() {
        val sampler = VitalsSampler({ listOf(fabric("a")) }, { 1 }, clock)
        messages = 100
        assertEquals(0, sampler.sample().tetherMessagesPerSec, "the first sample has no earlier one")
        now = now.plusSeconds(5)
        messages = 600
        assertEquals(100, sampler.sample().tetherMessagesPerSec)
        now = now.plusSeconds(10)
        assertEquals(0, sampler.sample().tetherMessagesPerSec)
    }
}
