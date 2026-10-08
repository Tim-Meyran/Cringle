// SPDX-License-Identifier: Apache-2.0

package cringle.common

import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class CertificateWatcherTest {
    @TempDir
    lateinit var dir: Path

    private val base = Instant.parse("2030-01-01T00:00:00Z")

    @Test
    fun itRenewsOnceWhenDueAndNotAgainAndStops() = runTest {
        // the clock follows the virtual time of the test
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant(): Instant = base.plusMillis(testScheduler.currentTime)
        }
        val identity = Identity.loadOrCreate(dir, "daemon:test", Duration.ofDays(60), clock)
        val first = identity.certificate
        val watcher = CertificateWatcher(identity, clock, Duration.ofHours(24)).start(this)
        runCurrent()
        assertEquals(first, identity.certificate, "60 days are left")

        advanceTimeBy(Duration.ofDays(29).toMillis())
        runCurrent()
        assertEquals(first, identity.certificate, "31 days are left")

        advanceTimeBy(Duration.ofDays(2).toMillis())
        runCurrent()
        val renewed = identity.certificate
        assert(renewed != first) { "29 days are left: renewed" }

        advanceTimeBy(Duration.ofDays(5).toMillis())
        runCurrent()
        assertEquals(renewed, identity.certificate, "not renewed again")

        watcher.close()
        advanceTimeBy(Duration.ofDays(4000).toMillis())
        runCurrent()
        assertEquals(renewed, identity.certificate, "a stopped watcher does nothing")
    }
}
