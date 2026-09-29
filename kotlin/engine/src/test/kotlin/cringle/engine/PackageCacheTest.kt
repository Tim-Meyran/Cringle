// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import cringle.packaging.PackageHash
import cringle.packaging.PackageWriter
import cringle.packaging.PluginManifest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PackageCacheTest {
    @TempDir
    lateinit var home: Path

    private class Clocks(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }

    private fun zip(name: String, version: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        PackageWriter.writePlugin(PluginManifest(name, version), emptyMap(), emptyMap(), emptyMap(), out)
        return out.toByteArray()
    }

    private fun artifact(name: String, version: String = "1.0.0") = Artifact(ArtifactType.PLUGIN, name, version, PackageHash.sha256(zip(name, version)))

    private class CountingFetcher(val content: (Artifact) -> ByteArray, val delayMillis: Long = 0) : PackageFetcher {
        val count = AtomicInteger()

        override suspend fun fetch(artifact: Artifact, target: Path) {
            count.incrementAndGet()
            Thread.sleep(delayMillis)
            Files.write(target, content(artifact))
        }
    }

    private fun fetcher(delay: Long = 0) = CountingFetcher({ zip(it.name, it.version) }, delay)

    private fun leftovers(): List<String> = listOf("plugins", "cache/downloads").flatMap { d ->
        val dir = home.resolve(d)
        if (!Files.isDirectory(dir)) emptyList() else Files.walk(dir).use { s -> s.filter { it.fileName.toString().let { n -> n.endsWith(".part") || n.contains(".installing-") } }.map { it.toString() }.toList() }
    }

    @Test
    fun installsOnceEvenWhenSeveralEnginesAskAtTheSameTime(): Unit = runBlocking {
        val a = artifact("acme-core")
        val f = fetcher(delay = 200)
        // several caches on one home model several engines of one machine
        val results = (1..6).map { PackageCache(home) }.map { c -> async(Dispatchers.Default) { c.ensure(a, f) } }.awaitAll()
        assertEquals(1, f.count.get())
        assertEquals(1, results.count { it })
        val dir = home.resolve("plugins/acme-core/1.0.0")
        assertTrue(Files.exists(dir.resolve("cringle-plugin.json")))
        assertEquals(a.sha256, Files.readString(dir.resolve(PackageCache.MARKER)).trim())
        assertFalse(PackageCache(home).ensure(a, f))
        assertEquals(1, f.count.get())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun aDownloadWithTheWrongHashIsRejectedAndLeavesNothingBehind(): Unit = runBlocking {
        val a = artifact("acme-core")
        val corrupt = CountingFetcher({ zip("acme-core", "1.0.0") + byteArrayOf(0) })
        val e = assertThrows<PackageCacheException> { runBlocking { PackageCache(home).ensure(a, corrupt) } }
        assertTrue(e.hashMismatch)
        assertFalse(Files.exists(home.resolve("plugins/acme-core/1.0.0")))
        assertEquals(emptyList<String>(), leftovers())
        // a later, correct download works
        assertTrue(PackageCache(home).ensure(a, fetcher()))
    }

    @Test
    fun anUnpackedPackageThatIsNotFromTheCacheIsNeverTakenOverSilently(): Unit = runBlocking {
        Files.createDirectories(home.resolve("plugins/acme-core/1.0.0"))
        val e = assertThrows<PackageCacheException> { runBlocking { PackageCache(home).ensure(artifact("acme-core"), fetcher()) } }
        assertFalse(e.hashMismatch)
    }

    @Test
    fun aDifferentHashForAnInstalledVersionIsAnError(): Unit = runBlocking {
        val cache = PackageCache(home)
        cache.ensure(artifact("acme-core"), fetcher())
        val other = artifact("acme-core").copy(sha256 = "0".repeat(64))
        assertTrue(assertThrows<PackageCacheException> { runBlocking { cache.ensure(other, fetcher()) } }.hashMismatch)
    }

    @Test
    fun cleanupRemovesUnusedVersionsAndKeepsUsedOnes(): Unit = runBlocking {
        val clock = Clocks()
        val cache = PackageCache(home, clock)
        val used = artifact("used")
        val idle = artifact("idle")
        val orphan = artifact("orphan")
        val f = fetcher()
        for (a in listOf(used, idle, orphan)) cache.ensure(a, f)
        Files.createDirectories(CringleHome.engineDir(home, "e1"))
        cache.recordUsage("e1", "shop", listOf(used))
        // usage of an engine that does not exist any more does not count
        cache.recordUsage("gone", "old", listOf(orphan))

        clock.now = clock.now.plus(Duration.ofDays(2))
        assertEquals(emptyList<String>(), cache.cleanup(Duration.ofDays(3))) // not old enough
        assertEquals(listOf("plugins/idle@1.0.0", "plugins/orphan@1.0.0"), cache.cleanup(Duration.ofDays(1)))
        assertTrue(Files.exists(home.resolve("plugins/used/1.0.0")))
        assertFalse(Files.exists(home.resolve("plugins/idle")))

        // removing the fabric makes the version unused, counted from that moment
        cache.clearUsage("e1", "shop")
        assertEquals(emptyList<String>(), cache.cleanup(Duration.ofDays(1)))
        clock.now = clock.now.plus(Duration.ofDays(1))
        assertEquals(listOf("plugins/used@1.0.0"), cache.cleanup(Duration.ofDays(1)))
        // and it can be installed again
        assertTrue(cache.ensure(used, f))
    }

    @Test
    fun manuallyPlacedPackagesAreNotManagedByCleanup(): Unit = runBlocking {
        Files.createDirectories(home.resolve("plugins/manual/1.0.0"))
        assertEquals(emptyList<String>(), PackageCache(home).cleanup(Duration.ZERO))
        assertTrue(Files.exists(home.resolve("plugins/manual/1.0.0")))
    }

    @Test
    fun restartingAnEngineForgetsItsUsage(): Unit = runBlocking {
        val cache = PackageCache(home)
        val a = artifact("acme-core")
        cache.ensure(a, fetcher())
        Files.createDirectories(CringleHome.engineDir(home, "e1"))
        cache.recordUsage("e1", "shop", listOf(a))
        assertEquals(emptyList<String>(), cache.cleanup(Duration.ZERO))
        cache.clearEngine("e1")
        assertEquals(listOf("plugins/acme-core@1.0.0"), cache.cleanup(Duration.ZERO))
    }
}
