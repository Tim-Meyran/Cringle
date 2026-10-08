// SPDX-License-Identifier: Apache-2.0

package cringle.common

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IdentityRenewalTest {
    @TempDir
    lateinit var dir: Path

    private val name = "daemon:test"
    private val start = Instant.parse("2030-01-01T00:00:00Z")

    private class TestClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = now
    }

    private val clock = TestClock(start)

    @Test
    fun aCertificateThatEndsWithinThirtyDaysIsRenewedWithTheSameKey() {
        val identity = Identity.loadOrCreate(dir, name, Duration.ofDays(10), clock)
        val fingerprint = identity.publicKeyFingerprint
        val key = Files.readString(dir.resolve("certs/identity.key"))
        val before = identity.certificate.notAfter.toInstant()

        assertTrue(identity.renewIfDue(clock = clock))
        assertTrue(identity.certificate.notAfter.toInstant().isAfter(before.plus(Duration.ofDays(3000))), "the new certificate is valid for the default time")
        assertEquals(fingerprint, identity.publicKeyFingerprint)
        assertEquals(key, Files.readString(dir.resolve("certs/identity.key")), "the key file is unchanged")
        assertEquals(identity.certificate, Identity.loadOrCreate(dir, name, clock = clock).certificate, "the renewed certificate is on disk")
    }

    @Test
    fun aCertificateWithTimeLeftIsLeftAlone() {
        val identity = Identity.loadOrCreate(dir, name, Duration.ofDays(100), clock)
        val certificate = identity.certificate
        assertFalse(identity.renewIfDue(clock = clock))
        assertEquals(certificate, identity.certificate)
        assertTrue(identity.remaining(clock) > Duration.ofDays(99))
    }

    @Test
    fun theTimeLeftIsMeasuredAtTheClockAndRenewalIsDueAfterwards() {
        val identity = Identity.loadOrCreate(dir, name, Duration.ofDays(100), clock)
        assertFalse(identity.renewIfDue(clock = clock))
        clock.now = start.plus(Duration.ofDays(71))
        assertTrue(identity.renewIfDue(clock = clock), "29 days are left")
        assertFalse(identity.renewIfDue(clock = clock), "the new certificate is fresh")
    }

    @Test
    fun anEndedCertificateIsRenewedWhenTheIdentityIsLoaded() {
        Identity.loadOrCreate(dir, name, Duration.ofDays(5), clock)
        clock.now = start.plus(Duration.ofDays(400))
        val loaded = Identity.loadOrCreate(dir, name, clock = clock)
        assertTrue(loaded.remaining(clock) > Duration.ofDays(3000))
        // without renewal the ended certificate stays (tests use that)
        val dir2 = dir.resolve("other")
        clock.now = start
        Identity.loadOrCreate(dir2, name, Duration.ofDays(5), clock)
        clock.now = start.plus(Duration.ofDays(400))
        assertTrue(Identity.loadOrCreate(dir2, name, clock = clock, renew = false).remaining(clock).isNegative)
    }

    @Test
    fun aCertificateCreatedNowIsNotRenewedEvenIfItIsExpired() {
        val identity = Identity.loadOrCreate(dir, name, Duration.ofSeconds(-120), clock)
        assertTrue(identity.remaining(clock).isNegative)
    }
}
