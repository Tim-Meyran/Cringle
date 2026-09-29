// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LockFileTest {
    private val h1 = "a".repeat(64)
    private val h2 = "b".repeat(64)

    private val lock = LockFile(
        roots = mapOf("zeta" to "^1.0.0", "alpha" to "~2.0.0"),
        packages = mapOf(
            "zeta" to LockedPackage("1.4.0", h2, mapOf("alpha" to "2.0.3")),
            "alpha" to LockedPackage("2.0.3", h1, emptyMap()),
        ),
    )

    private val expected = """
        {
            "format": 1,
            "roots": {
                "alpha": "~2.0.0",
                "zeta": "^1.0.0"
            },
            "packages": {
                "alpha": {
                    "version": "2.0.3",
                    "hash": "$h1",
                    "dependencies": {}
                },
                "zeta": {
                    "version": "1.4.0",
                    "hash": "$h2",
                    "dependencies": {
                        "alpha": "2.0.3"
                    }
                }
            }
        }
    """.trimIndent() + "\n"

    @Test
    fun encodingIsExactlyTheDocumentedBytes() {
        assertEquals(expected, lock.encode())
        assertEquals(expected.toByteArray().toList(), lock.encode().toByteArray().toList())
        assertTrue('\r' !in lock.encode())
    }

    @Test
    fun encodingDoesNotDependOnInsertionOrder() {
        val shuffled = LockFile(lock.roots.entries.reversed().associate { it.key to it.value }, lock.packages.entries.reversed().associate { it.key to it.value })
        assertEquals(lock.encode(), shuffled.encode())
    }

    @Test
    fun parseRoundTrips() {
        assertEquals(lock, LockFile.parse(lock.encode()))
        assertEquals(lock.encode(), LockFile.parse(lock.encode()).encode())
    }

    @Test
    fun resolutionProducesAConsistentReproducibleLock() {
        val source = object : PackageSource {
            override fun versions(name: String) = listOf(Version.parse("1.0.0"))
            override fun info(name: String, version: Version) =
                PackageInfo(name, version, if (name == "a") mapOf("b" to "^1.0.0") else emptyMap(), PackageHash.sha256(name.toByteArray()))
        }
        val first = Resolver.resolve(mapOf("a" to "*"), source).toLock()
        val second = Resolver.resolve(mapOf("a" to "*"), source).toLock()
        assertEquals(emptyList<PackageProblem>(), first.problems())
        assertEquals(first.encode(), second.encode())
        assertEquals("1.0.0", first.packages.getValue("a").dependencies.getValue("b"))
    }

    @Test
    fun consistencyProblemsAreReported() {
        val broken = LockFile(
            mapOf("ghost" to "*"),
            mapOf(
                "a" to LockedPackage("1.0", "xyz", mapOf("b" to "1.0.0", "missing" to "1.0.0")),
                "b" to LockedPackage("2.0.0", h1, emptyMap()),
            ),
        )
        val p = broken.problems().map { it.path to it.message }
        assertTrue(("$.packages.a.version" to "invalid version '1.0'") in p, p.toString())
        assertTrue(p.any { it.first == "$.packages.a.hash" }, p.toString())
        assertTrue(("$.packages.a.dependencies.b" to "locked at 2.0.0, but a expects 1.0.0") in p, p.toString())
        assertTrue(("$.packages.a.dependencies.missing" to "'missing' is not locked") in p, p.toString())
        assertTrue(("$.roots.ghost" to "root 'ghost' is not locked") in p, p.toString())
    }

    @Test
    fun parseIsStrict() {
        fun bad(text: String) = assertThrows<PackageFormatException> { LockFile.parse(text) }
        assertTrue(bad("{").message!!.contains("malformed JSON"))
        assertTrue(bad("""{"format":2}""").message!!.contains("unsupported format 2"))
        assertTrue(bad("""{"roots":{}}""").message!!.contains("missing key 'format'"))
        assertTrue(bad("""{"format":1,"extra":1}""").message!!.contains("unknown key 'extra'"))
        assertTrue(bad("""{"format":1,"format":1}""").message!!.contains("duplicate key"))
        assertTrue(bad("""{"format":1,"packages":{"a":{"version":"1.0.0"}}}""").message!!.contains("missing key 'hash'"))
        assertTrue(bad("""{"format":1,"packages":{"a":{"version":"1.0.0","hash":"x","extra":1}}}""").message!!.contains("unknown key 'extra'"))
    }
}
