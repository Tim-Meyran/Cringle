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
    // --- hardening (#69) ---

    /** File operations that fail where a test says so, like a locked file on Windows does. */
    private class FailingOps(
        private val failRename: (Path) -> Boolean = { false },
        private val deleteFailsAfterTheMarker: Boolean = false,
    ) : CacheFileOps {
        private val real = DefaultCacheFileOps(attempts = 1, pauseMillis = 0)

        override fun rename(from: Path, to: Path) {
            if (failRename(from)) throw java.nio.file.FileSystemException(from.toString(), null, "the process cannot access the file because it is being used by another process")
            real.rename(from, to)
        }

        override fun deleteTree(dir: Path) {
            if (deleteFailsAfterTheMarker && dir.fileName.toString().contains(".removing-")) {
                // a deletion that dies in the middle: the marker is gone, the rest is still there
                Files.deleteIfExists(dir.resolve(PackageCache.MARKER))
                throw java.io.IOException("the process cannot access the file because it is being used by another process")
            }
            real.deleteTree(dir)
        }
    }

    private fun versionDir(name: String, version: String = "1.0.0") = home.resolve("plugins/$name/$version")

    private fun names(dir: Path): List<String> = if (!Files.isDirectory(dir)) emptyList() else Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun aVersionThatCannotBeRemovedDoesNotStopTheOthersAndIsReported(): Unit = runBlocking {
        val warnings = java.util.concurrent.CopyOnWriteArrayList<String>()
        val clock = Clocks()
        val cache = PackageCache(home, clock, FailingOps(failRename = { it.parent.fileName.toString() == "locked" }), { warnings += it })
        val f = fetcher()
        for (n in listOf("aaa", "locked", "zzz")) cache.ensure(artifact(n), f)

        val report = cache.cleanupReport(Duration.ZERO)
        assertEquals(listOf("plugins/aaa@1.0.0", "plugins/zzz@1.0.0"), report.removed)
        assertEquals(1, report.failed.size, report.failed.toString())
        assertTrue(report.failed.single().startsWith("plugins/locked@1.0.0: "), report.failed.single())
        assertTrue(warnings.any { it.contains("plugins/locked@1.0.0") }, warnings.toString())
        // the version that could not be removed is still complete and usable
        assertTrue(Files.exists(versionDir("locked").resolve(PackageCache.MARKER)))
        assertFalse(cache.ensure(artifact("locked"), f))
        assertEquals(emptyList<String>(), leftovers())
        // a later cleanup, when the file is free, removes it
        assertEquals(listOf("plugins/locked@1.0.0"), PackageCache(home, clock).cleanup(Duration.ZERO))
    }

    @Test
    fun aDeletionThatFailsInTheMiddleNeverLeavesAHalfDeletedVersion(): Unit = runBlocking {
        val clock = Clocks()
        val cache = PackageCache(home, clock, FailingOps(deleteFailsAfterTheMarker = true), {})
        val f = fetcher()
        cache.ensure(artifact("gone"), f)

        val report = cache.cleanupReport(Duration.ZERO)
        // the version is gone under its name, complete or not at all: there is no marker-less directory left under it
        assertEquals(listOf("plugins/gone@1.0.0"), report.removed)
        assertEquals(emptyList<String>(), report.failed)
        assertFalse(Files.exists(versionDir("gone")))
        val left = names(home.resolve("plugins/gone"))
        assertEquals(1, left.size, left.toString())
        assertTrue(left.single().startsWith("1.0.0.removing-"), left.toString())

        // the leftover is not a version: ensure installs a fresh one without an error, cleanup does not see the leftover
        assertTrue(cache.ensure(artifact("gone"), f))
        assertTrue(Files.exists(versionDir("gone").resolve(PackageCache.MARKER)))
        assertEquals(listOf("plugins/gone@1.0.0"), PackageCache(home, clock).cleanup(Duration.ZERO))

        // it is removed once it is old enough: not after ten minutes, after two hours, by cleanup and at the start
        val leftover = home.resolve("plugins/gone").resolve(left.single())
        assertTrue(Files.exists(leftover))
        // the fake deletion touched the directory with the real time; the age is measured on the test clock
        Files.setLastModifiedTime(leftover, java.nio.file.attribute.FileTime.from(clock.instant()))
        clock.now = clock.now.plus(Duration.ofMinutes(10))
        assertEquals(emptyList<String>(), PackageCache(home, clock).sweepLeftovers())
        assertTrue(Files.exists(leftover))
        clock.now = clock.now.plus(Duration.ofHours(2))
        assertEquals(listOf("plugins/gone/${left.single()}"), PackageCache(home, clock).sweepLeftovers())
        assertFalse(Files.exists(leftover))
    }

    @Test
    fun handPlacedPackagesAreKeptWhileTheRestIsCleanedUp(): Unit = runBlocking {
        val cache = PackageCache(home)
        Files.createDirectories(versionDir("manual"))
        Files.writeString(versionDir("manual").resolve("file.txt"), "mine")
        cache.ensure(artifact("cached"), fetcher())
        assertEquals(listOf("plugins/cached@1.0.0"), cache.cleanup(Duration.ZERO))
        assertEquals("mine", Files.readString(versionDir("manual").resolve("file.txt")))
        assertThrows<PackageCacheException> { runBlocking { cache.ensure(artifact("manual"), fetcher()) } }
        assertEquals("mine", Files.readString(versionDir("manual").resolve("file.txt")))
    }

    @Test
    fun ensureAndCleanupRunTogetherWithoutAMissingVersionOrAnError(): Unit = runBlocking {
        Files.createDirectories(CringleHome.engineDir(home, "e1"))
        val a = artifact("busy")
        val f = fetcher()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val cleanerFailures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val cleanups = AtomicInteger()
        // a second engine of the same machine cleans up as fast as it can, removing everything that is unused
        val cleaner = Thread {
            val cache = PackageCache(home, onWarning = {})
            try {
                while (!done.get()) {
                    cache.cleanupReport(Duration.ZERO)
                    cleanups.incrementAndGet()
                }
            } catch (e: Throwable) {
                cleanerFailures += e
            }
        }
        cleaner.start()
        try {
            val cache = PackageCache(home, onWarning = {})
            // ensure throws if it meets a directory without marker (a half-deleted version): it must never happen
            repeat(150) { i ->
                cache.ensure(a, f)
                cache.recordUsage("e1", "fab$i", listOf(a))
                cache.clearUsage("e1", "fab$i")
            }
            cache.ensure(a, f)
            cache.recordUsage("e1", "last", listOf(a))
        } finally {
            done.set(true)
            cleaner.join()
        }
        assertEquals(emptyList<Throwable>(), cleanerFailures)
        assertTrue(cleanups.get() > 0, "the cleanup ran")
        // what the cleanup removed while it ran left nothing that is older work in progress
        assertEquals(emptyList<String>(), leftovers().filter { it.contains(".part") })
    }

    @Test
    fun aVersionIsNotRemovedBetweenEnsureAndRecordUsage(): Unit = runBlocking {
        Files.createDirectories(CringleHome.engineDir(home, "e1"))
        val a = artifact("busy")
        val f = fetcher()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val cleanerFailures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val cleanups = AtomicInteger()
        // a second engine of the same machine cleans up as fast as it can, removing everything that is unused
        val cleaner = Thread {
            val cache = PackageCache(home, onWarning = {})
            try {
                while (!done.get()) {
                    cache.cleanupReport(Duration.ZERO)
                    cleanups.incrementAndGet()
                }
            } catch (e: Throwable) {
                cleanerFailures += e
            }
        }
        cleaner.start()
        try {
            val cache = PackageCache(home, onWarning = {})
            // ensure throws if it meets a directory without marker (a half-deleted version): it must never happen
            repeat(150) { i ->
                cache.ensureForDeploy("e1", "fab$i", listOf(a), f)
                cache.clearUsage("e1", "fab$i")
            }
            cache.ensureForDeploy("e1", "last", listOf(a), f)
        } finally {
            done.set(true)
            cleaner.join()
        }
        assertEquals(emptyList<Throwable>(), cleanerFailures)
        // the version that is in use is there, complete, and is not removed
        assertTrue(Files.exists(versionDir("busy").resolve(PackageCache.MARKER)))
        assertEquals(emptyList<String>(), PackageCache(home).cleanup(Duration.ZERO))
        assertTrue(Files.exists(versionDir("busy").resolve(PackageCache.MARKER)))
        // what the cleanup removed while it ran left nothing that is older work in progress
        assertEquals(emptyList<String>(), leftovers().filter { it.contains(".part") })
    }

    @Test
    fun aFailedDeployLeavesNoUsageRecordBehind(): Unit = runBlocking {
        Files.createDirectories(CringleHome.engineDir(home, "e1"))
        val corrupt = CountingFetcher({ zip("acme-core", "1.0.0") + byteArrayOf(0) })
        val e = assertThrows<PackageCacheException> {
            PackageCache(home).ensureForDeploy("e1", "shop", listOf(artifact("acme-core")), corrupt)
        }
        assertTrue(e.hashMismatch)
        // the failed deploy cleared the usage record it had just written
        assertFalse(Files.exists(home.resolve("cache/usage/e1/shop")))
        // the install failed, so nothing was installed
        assertFalse(Files.exists(home.resolve("plugins/acme-core/1.0.0")))
    }

    @Test
    fun aFailedRedeployLeavesTheOriginalRecordIntact(): Unit = runBlocking {
        Files.createDirectories(CringleHome.engineDir(home, "e1"))
        val cache = PackageCache(home)
        cache.ensure(artifact("acme-core"), fetcher())
        cache.recordUsage("e1", "shop", listOf(artifact("acme-core")))
        val recordFile = home.resolve("cache/usage/e1/shop")
        val original = Files.readString(recordFile)
        // a second deploy of the same fabric id with a different, corrupt artifact fails
        val corrupt = CountingFetcher({ zip("acme-other", "1.0.0") + byteArrayOf(0) })
        val e = assertThrows<PackageCacheException> {
            cache.ensureForDeploy("e1", "shop", listOf(artifact("acme-other")), corrupt)
        }
        assertTrue(e.hashMismatch)
        // the failed redeploy did not touch the original record of the live fabric
        assertTrue(Files.exists(recordFile))
        assertEquals(original, Files.readString(recordFile))
    }

    @Test
    fun aSuccessfulRedeployWithStaleRecordRecordsNewArtifacts(): Unit = runBlocking {
        Files.createDirectories(CringleHome.engineDir(home, "e1"))
        val cache = PackageCache(home)
        // stale record for fabric "shop" with old artifact
        cache.recordUsage("e1", "shop", listOf(artifact("old")))
        // simulate ensureForDeploy on existing record
        assertFalse(cache.ensureForDeploy("e1", "shop", listOf(artifact("new")), fetcher()))
        // simulate Engine.deployFabric success recording new artifacts
        cache.recordUsage("e1", "shop", listOf(artifact("new")))
        val lines = Files.readAllLines(home.resolve("cache/usage/e1/shop")).filter { it.isNotBlank() }
        assertEquals(listOf("PLUGIN new 1.0.0"), lines)
    }

    @Test
    fun oldDownloadsAndUnfinishedInstallsAreRemovedAndNewOnesStay(): Unit = runBlocking {
        val clock = Clocks()
        val cache = PackageCache(home, clock)
        cache.ensure(artifact("keep"), fetcher())
        Files.createDirectories(versionDir("manual", "2.0.0"))
        val downloads = Files.createDirectories(home.resolve("cache/downloads"))
        val oldPart = Files.writeString(downloads.resolve("${java.util.UUID.randomUUID()}.part"), "old")
        val newPart = Files.writeString(downloads.resolve("${java.util.UUID.randomUUID()}.part"), "new")
        val other = Files.writeString(downloads.resolve("readme.txt"), "not a download")
        fun staging(version: String, kind: String): Path {
            val d = Files.createDirectories(home.resolve("plugins/keep/$version.$kind-${java.util.UUID.randomUUID()}"))
            Files.writeString(d.resolve("x"), "x")
            return d
        }
        val oldInstall = staging("3.0.0", "installing")
        val newInstall = staging("4.0.0", "installing")
        val oldRemoval = staging("5.0.0", "removing")
        val now = clock.instant()
        for (p in listOf(oldPart, oldInstall, oldRemoval, other)) Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.from(now.minus(Duration.ofHours(2))))
        for (p in listOf(newPart, newInstall)) Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.from(now.minus(Duration.ofMinutes(10))))

        assertEquals(emptyList<String>(), cache.cleanup(Duration.ofDays(1000))) // removes no version, but sweeps
        assertFalse(Files.exists(oldPart))
        assertFalse(Files.exists(oldInstall))
        assertFalse(Files.exists(oldRemoval))
        assertTrue(Files.exists(newPart), "a download of the last minutes may still be running")
        assertTrue(Files.exists(newInstall), "an installation of the last minutes may still be running")
        assertTrue(Files.exists(other), "only .part files are downloads")
        assertTrue(Files.exists(versionDir("keep").resolve(PackageCache.MARKER)))
        assertTrue(Files.isDirectory(versionDir("manual", "2.0.0")))

        // at the start of an engine the same happens, without a cleanup
        val later = Clocks(now.plus(Duration.ofHours(3)))
        val swept = PackageCache(home, later).sweepLeftovers()
        assertEquals(listOf("cache/downloads/${newPart.fileName}", "plugins/keep/${newInstall.fileName}"), swept)
        assertTrue(Files.exists(other))
    }
}
