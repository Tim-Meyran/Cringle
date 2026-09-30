// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VersionTest {
    @Test
    fun orderingFollowsSemverSpecification() {
        val ordered = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11",
            "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0", "2.1.0", "2.1.1", "10.0.0",
        ).map(Version::parse)
        for (i in ordered.indices) for (j in ordered.indices) {
            assertEquals(i.compareTo(j).coerceIn(-1, 1), ordered[i].compareTo(ordered[j]).coerceIn(-1, 1), "${ordered[i]} vs ${ordered[j]}")
        }
        assertEquals(ordered.shuffled(java.util.Random(1)).sorted(), ordered)
    }

    @Test
    fun parseAndFormatRoundTrip() {
        for (text in listOf("0.0.0", "1.2.3", "1.2.3-rc.1", "10.20.30-alpha-1.x")) assertEquals(text, Version.parse(text).toString())
        assertEquals(Version.parse("1.2.3"), Version.parse("1.2.3"))
        assertEquals(Version.parse("1.2.3").hashCode(), Version.parse("1.2.3").hashCode())
    }

    @Test
    fun rejectsInvalidVersions() {
        for (bad in listOf("1", "1.2", "01.2.3", "1.2.3.4", "v1.2.3", "1.2.3-", "1.2.3+build", "99999999999.0.0")) {
            assertThrows<IllegalArgumentException>(bad) { Version.parse(bad) }
        }
    }

    /** The examples of `spec/versioning.md`, section 1, in both directions. */
    @Test
    fun grammarAgreesWithTheVersioningSpecification() {
        val chain = "1.0.0-alpha < 1.0.0-alpha.1 < 1.0.0-alpha.beta < 1.0.0-beta < 1.0.0-beta.2 < " +
            "1.0.0-beta.11 < 1.0.0-rc.1 < 1.0.0"
        assertEquals(chain.split(" < ").map(Version::parse), chain.split(" < ").map(Version::parse).sorted())
        for (good in listOf("0.0.0", "1.0.0", "10.20.30", "1.0.0-0", "1.0.0-0a", "1.0.0-a.b", "1.0.0-x.7.z.92", "1.0.0-a-b")) {
            assertEquals(null, PackageNames.versionProblem(good), good)
            assertEquals(good, Version.parse(good).toString())
        }
        // No leading zeros, no build metadata, and numbers that fit in the Int of Version.
        for (bad in listOf("01.2.3", "1.02.3", "1.2.03", "1.0.0-01", "1.0.0-1.02", "1.0.0-00", "1.0.0+build", "1.0.0-",
            "1.0.0-1..2", "1.0.0-.1", "2147483648.0.0", "1.0.0.2147483648", "99999999999.0.0")) {
            assertNotEquals(null, PackageNames.versionProblem(bad), bad)
            assertThrows<IllegalArgumentException>(bad) { Version.parse(bad) }
        }
        assertEquals(Version.parse("2147483647.0.0"), Version.parse("2147483647.0.0"))
    }

    @Test
    fun equalityAndHashCodeUseTheSamePartsAsTheOrder() {
        val examples = listOf(
            "0.0.0", "1.0.0", "1.0.0-0", "1.0.0-1", "1.0.0-alpha", "1.0.0-alpha.0", "1.0.0-alpha.1", "1.0.0-beta.11",
            "1.0.0-rc.1", "1.0.1", "1.1.0", "2.0.0", "10.0.0",
        )
        for (a in examples) for (b in examples) {
            val x = Version.parse(a)
            val y = Version.parse(b)
            assertEquals(x.compareTo(y) == 0, x == y, "$a vs $b")
            if (x == y) assertEquals(x.hashCode(), y.hashCode(), "$a vs $b")
        }
        val set = examples.map { Version.parse(it) }.toHashSet()
        for (text in examples) assertTrue(set.contains(Version.parse(text)), text)
        // The spellings that made equals and hashCode disagree do not exist any more.
        assertThrows<IllegalArgumentException> { Version(1, 0, 0, listOf("01")) }
        assertThrows<IllegalArgumentException> { Version.parse("1.0.0-01") }
    }

    // range, then versions that must match, then versions that must not
    private val table = listOf(
        Triple("1.2.3", "1.2.3", "1.2.4 1.2.2 1.2.3-rc.1"),
        Triple("=1.2.3", "1.2.3", "1.2.4"),
        Triple(">1.2.3", "1.2.4 2.0.0", "1.2.3 1.0.0"),
        Triple(">=1.2.3", "1.2.3 1.2.4 3.0.0", "1.2.2"),
        Triple("<1.2.3", "1.2.2 0.0.1", "1.2.3 1.3.0"),
        Triple("<=1.2.3", "1.2.3 1.0.0", "1.2.4"),
        Triple(">= 1.2.3", "1.2.3", "1.2.2"),
        Triple("^1.2.3", "1.2.3 1.9.9", "1.2.2 2.0.0 2.0.0-alpha"),
        Triple("^0.2.3", "0.2.3 0.2.9", "0.3.0 0.2.2 1.0.0"),
        Triple("^0.0.3", "0.0.3", "0.0.4 0.0.2"),
        Triple("^1.2", "1.2.0 1.9.0", "1.1.9 2.0.0"),
        Triple("^1", "1.0.0 1.9.9", "0.9.9 2.0.0"),
        Triple("^0", "0.0.0 0.9.9", "1.0.0"),
        Triple("^0.0", "0.0.0 0.0.9", "0.1.0"),
        Triple("~1.2.3", "1.2.3 1.2.9", "1.3.0 1.2.2"),
        Triple("~1.2", "1.2.0 1.2.9", "1.3.0 1.1.9"),
        Triple("~1", "1.0.0 1.9.9", "2.0.0 0.9.0"),
        Triple("1.2", "1.2.0 1.2.9", "1.3.0"),
        Triple("1.x", "1.0.0 1.9.9", "2.0.0"),
        Triple("1.2.x", "1.2.5", "1.3.0"),
        Triple("*", "0.0.1 5.0.0", "1.0.0-rc.1"),
        Triple("x", "1.2.3", ""),
        Triple(">=1.2.3 <2.0.0", "1.2.3 1.9.9", "1.2.2 2.0.0"),
        Triple("^1.0.0 <1.5.0", "1.0.0 1.4.9", "1.5.0"),
        Triple("^1.0.0 || ^3.0.0", "1.5.0 3.1.0", "2.0.0 0.9.0"),
        Triple("<1.0.0 || >=2.0.0", "0.5.0 2.0.0", "1.5.0"),
        // prereleases match only when a comparator of the same set names a prerelease of the same version
        Triple(">=1.2.3-alpha.1 <2.0.0", "1.2.3-alpha.2 1.2.3-beta 1.2.3 1.5.0", "1.2.4-alpha.1 1.2.3-alpha.0 2.0.0-alpha"),
        Triple("1.2.3-rc.1", "1.2.3-rc.1", "1.2.3-rc.2 1.2.3"),
        Triple("^1.2.3-rc.1", "1.2.3-rc.1 1.2.3 1.4.0", "1.2.3-rc.0 1.3.0-rc.1"),
        Triple("~2.0.0", "2.0.5", "2.1.0-alpha"),
    )

    @Test
    fun rangeSemanticsTable() {
        for ((range, matching, rejected) in table) {
            val r = VersionRange.parse(range)
            for (v in matching.split(' ').filter { it.isNotEmpty() }) assertTrue(r.matches(Version.parse(v)), "'$range' should match $v")
            for (v in rejected.split(' ').filter { it.isNotEmpty() }) assertFalse(r.matches(Version.parse(v)), "'$range' should not match $v")
        }
    }

    @Test
    fun invalidRangesGiveReadableErrors() {
        val cases = mapOf(
            "" to "must not be blank",
            "  " to "must not be blank",
            "1.2.3 - 2.0.0" to "hyphen ranges are not supported",
            ">1.2" to "needs a full version",
            "^1.2.3.4" to "invalid version",
            "^a.b.c" to "invalid version",
            "1.2.3 ||" to "empty alternative",
            "01.2.3" to "invalid version",
            "^1.2-rc.1" to "prerelease needs a full version",
        )
        for ((text, message) in cases) {
            val e = assertThrows<IllegalArgumentException>("'$text'") { VersionRange.parse(text) }
            assertTrue(e.message!!.contains(message), "'$text': ${e.message}")
        }
    }

    @Test
    fun toStringKeepsTheTrimmedText() {
        assertEquals("^1.2.3 || ~2.0.0", VersionRange.parse("  ^1.2.3 || ~2.0.0 ").toString())
        assertEquals(VersionRange.parse("^1.0.0"), VersionRange.parse(" ^1.0.0"))
    }
}
