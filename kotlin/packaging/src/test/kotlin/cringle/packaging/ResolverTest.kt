// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResolverTest {
    private class MapSource : PackageSource {
        val data = LinkedHashMap<String, LinkedHashMap<Version, Map<String, String>>>()
        var lookups = 0

        fun add(name: String, version: String, vararg deps: Pair<String, String>): MapSource {
            data.getOrPut(name) { LinkedHashMap() }[Version.parse(version)] = linkedMapOf(*deps)
            return this
        }

        override fun versions(name: String): List<Version> = data[name]?.keys?.toList().orEmpty()

        override fun info(name: String, version: Version): PackageInfo {
            lookups++
            return PackageInfo(name, version, data.getValue(name).getValue(version), PackageHash.sha256("$name@$version".toByteArray()))
        }
    }

    private fun versionsOf(r: Resolution) = r.packages.mapValues { it.value.version.toString() }

    @Test
    fun picksHighestMatchingVersionsIncludingTransitive() {
        val s = MapSource()
            .add("app-lib", "1.0.0", "util" to "^1.0.0")
            .add("app-lib", "1.1.0", "util" to "^1.1.0")
            .add("util", "1.0.0").add("util", "1.1.0").add("util", "1.2.0").add("util", "2.0.0")
        val r = Resolver.resolve(mapOf("app-lib" to "^1.0.0"), s)
        assertEquals(mapOf("app-lib" to "1.1.0", "util" to "1.2.0"), versionsOf(r))
    }

    @Test
    fun backtracksToOlderVersionsWhenNeeded() {
        val s = MapSource()
            .add("a", "2.0.0", "c" to "^2.0.0")
            .add("a", "1.0.0", "c" to "^1.0.0")
            .add("b", "1.0.0", "c" to "^1.0.0")
            .add("c", "1.5.0").add("c", "2.5.0")
        val r = Resolver.resolve(mapOf("a" to "*", "b" to "*"), s)
        assertEquals(mapOf("a" to "1.0.0", "b" to "1.0.0", "c" to "1.5.0"), versionsOf(r))
    }

    @Test
    fun singleVersionPerPackageAcrossTheGraph() {
        val s = MapSource()
            .add("a", "1.0.0", "shared" to ">=1.0.0")
            .add("b", "1.0.0", "shared" to "<2.0.0")
            .add("shared", "1.0.0").add("shared", "1.9.0").add("shared", "2.1.0")
        assertEquals("1.9.0", Resolver.resolve(mapOf("a" to "*", "b" to "*"), s).packages.getValue("shared").version.toString())
    }

    @Test
    fun resultIsIndependentOfOrder() {
        fun source(reverse: Boolean): MapSource {
            val entries = listOf<() -> Unit>()
            val s = MapSource()
            val adds = listOf(
                { s.add("a", "1.0.0", "c" to "^1.0.0", "b" to "^1.0.0") },
                { s.add("b", "1.0.0", "c" to "~1.1.0") },
                { s.add("b", "1.1.0", "c" to "~1.2.0") },
                { s.add("c", "1.1.5"); s.add("c", "1.2.5"); s.add("c", "1.0.0") },
            )
            (if (reverse) adds.reversed() else adds).forEach { it() }
            check(entries.isEmpty())
            return s
        }
        val forward = Resolver.resolve(linkedMapOf("a" to "*", "b" to "*"), source(false))
        val backward = Resolver.resolve(linkedMapOf("b" to "*", "a" to "*"), source(true))
        assertEquals(versionsOf(forward), versionsOf(backward))
        assertEquals(forward.toLock().encode(), backward.toLock().encode())
    }

    @Test
    fun unknownPackageIsReportedWithRequirer() {
        val s = MapSource().add("a", "1.0.0", "ghost" to "^1.0.0")
        val e = assertThrows<ResolutionException> { Resolver.resolve(mapOf("a" to "*"), s) }
        assertEquals(ResolutionFailure.UNKNOWN_PACKAGE, e.failure)
        assertEquals("package 'ghost' is not available (required by a@1.0.0)", e.message)
    }

    @Test
    fun unsatisfiableConstraintsListEveryRequirerAndTheAvailableVersions() {
        val s = MapSource()
            .add("a", "1.0.0", "c" to "^1.0.0")
            .add("b", "1.0.0", "c" to "^2.0.0")
            .add("c", "1.0.0").add("c", "2.0.0")
        val e = assertThrows<ResolutionException> { Resolver.resolve(mapOf("a" to "*", "b" to "*"), s) }
        assertEquals(ResolutionFailure.NO_SATISFYING_VERSION, e.failure)
        assertTrue(e.message!!.contains("^1.0.0 (required by a@1.0.0)") && e.message!!.contains("^2.0.0 (required by b@1.0.0)"), e.message)
        assertTrue(e.message!!.contains("no version of 'c'"), e.message)
    }

    @Test
    fun rootRangeThatMatchesNothingIsReadable() {
        val s = MapSource().add("a", "1.0.0")
        val e = assertThrows<ResolutionException> { Resolver.resolve(mapOf("a" to "^2.0.0"), s) }
        assertEquals(
            "no version of 'a' satisfies all constraints:\n  ^2.0.0 (required by the root)\navailable versions: 1.0.0",
            e.message,
        )
    }

    @Test
    fun cyclesAreReported() {
        val s = MapSource()
            .add("a", "1.0.0", "b" to "*")
            .add("b", "1.0.0", "c" to "*")
            .add("c", "1.0.0", "a" to "*")
        val e = assertThrows<ResolutionException> { Resolver.resolve(mapOf("a" to "*"), s) }
        assertEquals(ResolutionFailure.CYCLE, e.failure)
        assertEquals("dependency cycle: a -> b -> c -> a", e.message)
    }

    @Test
    fun invalidRangesAreReportedWithOwner() {
        val s = MapSource().add("a", "1.0.0", "b" to "not a range").add("b", "1.0.0")
        val e = assertThrows<ResolutionException> { Resolver.resolve(mapOf("a" to "*"), s) }
        assertEquals(ResolutionFailure.INVALID_RANGE, e.failure)
        assertTrue(e.message!!.contains("required by a@1.0.0"), e.message)
        assertEquals(ResolutionFailure.INVALID_RANGE, assertThrows<ResolutionException> { Resolver.resolve(mapOf("a" to ""), s) }.failure)
    }

    @Test
    fun prereleasesAreOnlyChosenWhenAskedFor() {
        val s = MapSource().add("a", "1.0.0").add("a", "1.1.0-rc.1")
        assertEquals("1.0.0", Resolver.resolve(mapOf("a" to "^1.0.0"), s).packages.getValue("a").version.toString())
        assertEquals("1.1.0-rc.1", Resolver.resolve(mapOf("a" to ">=1.1.0-rc.0 <2.0.0"), s).packages.getValue("a").version.toString())
    }

    @Test
    fun emptyRootsResolveToNothing() {
        assertEquals(emptyMap<String, String>(), versionsOf(Resolver.resolve(emptyMap(), MapSource())))
    }
}
