// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemVerTest {
    @Test
    fun parseAcceptsVersionsAndRejectsGarbage() {
        assertEquals(SemVer(1, 2, 3, null), SemVer.parse("1.2.3"))
        assertEquals(SemVer(1, 2, 3, "rc.1"), SemVer.parse("1.2.3-rc.1"))
        assertNull(SemVer.parse("1.2"))
        assertNull(SemVer.parse("v1.2.3"))
        assertNull(SemVer.parse("1.2.3.4"))
    }

    @Test
    fun toStringRoundTrips() {
        assertEquals("1.2.3-rc.1", SemVer.parse("1.2.3-rc.1")!!.toString())
        assertEquals("1.2.3", SemVer(1, 2, 3).toString())
    }

    @Test
    fun releaseSortsAfterItsPreRelease() {
        assertTrue(SemVer.parse("1.2.3-rc.1")!! < SemVer.parse("1.2.3")!!)
        assertTrue(SemVer.parse("1.2.3")!! > SemVer.parse("1.2.3-rc.1")!!)
    }

    @Test
    fun numbersCompareInOrder() {
        assertTrue(SemVer.parse("1.2.3")!! < SemVer.parse("1.3.0")!!)
        assertTrue(SemVer.parse("2.0.0")!! > SemVer.parse("1.9.9")!!)
    }

    @Test
    fun preReleaseIdentifiersCompare() {
        assertTrue(SemVer.parse("1.0.0-alpha")!! < SemVer.parse("1.0.0-beta")!!)
        assertTrue(SemVer.parse("1.0.0-rc.2")!! < SemVer.parse("1.0.0-rc.10")!!)
        assertTrue(SemVer.parse("1.0.0-alpha")!! < SemVer.parse("1.0.0-alpha.1")!!)
    }

    @Test
    fun parseOrThrowNamesTheText() {
        val thrown = assertThrows(IllegalArgumentException::class.java) { SemVer.parseOrThrow("nope") }
        assertTrue(thrown.message!!.contains("nope"))
    }
}
