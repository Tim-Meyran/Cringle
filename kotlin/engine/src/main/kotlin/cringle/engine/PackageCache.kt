// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import cringle.packaging.PackageFormatException
import cringle.packaging.PackageHash
import cringle.packaging.PackageHashMismatchException
import cringle.packaging.SafeUnzip
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The two kinds of package in the cache; [dir] is the directory below the Cringle home. */
public enum class ArtifactType(public val dir: String) { PROJECT("projects"), PLUGIN("plugins") }

/** One package version the cache holds or should hold. [sha256] is the hash of the package file. */
public data class Artifact(val type: ArtifactType, val name: String, val version: String, val sha256: String) {
    /** `<kind>/<name>@<version>` */
    public val label: String get() = "${type.dir}/$name@$version"
}

/** Why a package could not be installed into the cache. */
public class PackageCacheException(public val hashMismatch: Boolean, message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Downloads a package file. Implemented on top of the Repository; tests use fakes. */
public fun interface PackageFetcher {
    /** Writes the package [artifact] to [target]. */
    public suspend fun fetch(artifact: Artifact, target: Path)
}

/**
 * The package cache of a machine (Architecture 15): unpacked packages in `<home>/projects/<name>/<version>` and
 * `<home>/plugins/<name>/<version>`, shared by all Engines that use the same Cringle home.
 *
 * A version is downloaded, verified and unpacked once. Installation happens in a temporary directory that is moved into
 * place atomically, so a failed or corrupt download never leaves anything behind, and a file lock (plus an in-process
 * lock) makes concurrent Engines wait for each other instead of installing twice. A marker file `.cringle-installed`
 * holds the hash of the installed package; only versions with a marker are managed (and cleaned up) by the cache.
 *
 * Usage is tracked per Engine and fabric in `<home>/cache/usage/<engine>/<fabric>`; a version is unused when no fabric of
 * an existing Engine lists it.
 */
public class PackageCache(private val home: Path, private val clock: java.time.Clock = java.time.Clock.systemUTC()) {
    private val root = home.resolve("cache")
    private fun target(a: Artifact): Path = home.resolve(a.type.dir).resolve(a.name).resolve(a.version)

    private fun marker(dir: Path): Path = dir.resolve(MARKER)

    /**
     * Makes sure [artifact] is installed, downloading it with [fetcher] if needed.
     * Returns `true` if this call installed it, `false` if it was already there.
     */
    public suspend fun ensure(artifact: Artifact, fetcher: PackageFetcher): Boolean {
        val dir = target(artifact)
        if (isInstalled(artifact, dir)) {
            touch(dir)
            return false
        }
        val key = artifact.label
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        // the file lock is taken on a blocking thread; the download runs in the caller's coroutine context
        return withContext(Dispatchers.IO) { lock.withLock { withFileLock(key) { install(artifact, dir, fetcher) } } }
    }

    private fun isInstalled(a: Artifact, dir: Path): Boolean {
        if (!Files.isDirectory(dir)) return false
        val m = marker(dir)
        if (!Files.exists(m)) throw PackageCacheException(false, "${a.label} exists in the Cringle home but was not installed by the cache; remove it or deploy without a package source")
        val installed = Files.readString(m).trim()
        if (!installed.equals(a.sha256, ignoreCase = true)) {
            throw PackageCacheException(true, "${a.label} is installed with hash $installed, but ${a.sha256} was requested; published versions are immutable")
        }
        return true
    }

    private fun <T> withFileLock(key: String, body: () -> T): T {
        val lockFile = root.resolve("locks").resolve(key.replace('/', '-').replace('@', '-') + ".lock")
        Files.createDirectories(lockFile.parent)
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { return body() }
        }
    }

    private fun install(a: Artifact, dir: Path, fetcher: PackageFetcher): Boolean {
        if (isInstalled(a, dir)) return false // another engine was faster
        val downloads = Files.createDirectories(root.resolve("downloads"))
        val file = downloads.resolve("${UUID.randomUUID()}.part")
        val staging = dir.resolveSibling(dir.fileName.toString() + ".installing-" + UUID.randomUUID())
        try {
            kotlinx.coroutines.runBlocking { fetcher.fetch(a, file) }
            try {
                PackageHash.verify(file, a.sha256)
            } catch (e: PackageHashMismatchException) {
                throw PackageCacheException(true, "${a.label}: downloaded file has hash ${e.actual}, expected ${e.expected}", e)
            }
            Files.createDirectories(dir.parent)
            SafeUnzip.extract(file, staging)
            Files.writeString(marker(staging), a.sha256.lowercase() + "\n")
            touch(staging)
            Files.move(staging, dir, StandardCopyOption.ATOMIC_MOVE)
            return true
        } catch (e: PackageHashMismatchException) {
            throw PackageCacheException(true, "${a.label}: downloaded file does not match the expected hash (${e.message})", e)
        } catch (e: PackageFormatException) {
            throw PackageCacheException(false, "${a.label}: invalid package: ${e.message}", e)
        } catch (e: IOException) {
            throw PackageCacheException(false, "${a.label}: cannot install: ${e.message}", e)
        } finally {
            runCatching { Files.deleteIfExists(file) }
            runCatching { deleteTree(staging) }
        }
    }

    private fun touch(dir: Path) {
        runCatching { Files.setLastModifiedTime(marker(dir), FileTime.from(clock.instant())) }
    }

    // --- usage ---

    private fun usageFile(engine: String, fabric: String): Path = root.resolve("usage").resolve(engine).resolve(fabric)

    /** Remembers that [fabric] of [engine] uses [artifacts]. */
    public fun recordUsage(engine: String, fabric: String, artifacts: List<Artifact>) {
        val file = usageFile(engine, fabric)
        Files.createDirectories(file.parent)
        Files.writeString(file, artifacts.joinToString("\n") { "${it.type.name} ${it.name} ${it.version}" } + "\n")
        artifacts.forEach { touch(target(it)) }
    }

    /** Remembers that [fabric] of [engine] is gone. The versions it used count as unused from now on. */
    public fun clearUsage(engine: String, fabric: String) {
        val file = usageFile(engine, fabric)
        if (!Files.exists(file)) return
        val used = readUsage(file)
        Files.deleteIfExists(file)
        used.forEach { touch(home.resolve(it.first.dir).resolve(it.second).resolve(it.third)) }
    }

    /** Forgets everything [engine] used; fabrics do not survive an Engine restart. */
    public fun clearEngine(engine: String) {
        val dir = root.resolve("usage").resolve(engine)
        if (!Files.isDirectory(dir)) return
        Files.list(dir).use { s -> s.toList() }.forEach { clearUsage(engine, it.fileName.toString()) }
        Files.deleteIfExists(dir)
    }

    private fun readUsage(file: Path): List<Triple<ArtifactType, String, String>> =
        Files.readAllLines(file).filter { it.isNotBlank() }.map { line ->
            val (type, name, version) = line.split(' ')
            Triple(ArtifactType.valueOf(type), name, version)
        }

    private fun inUse(): Set<Triple<ArtifactType, String, String>> {
        val usage = root.resolve("usage")
        if (!Files.isDirectory(usage)) return emptySet()
        val result = HashSet<Triple<ArtifactType, String, String>>()
        Files.list(usage).use { engines -> engines.toList() }.forEach { engineDir ->
            // usage of Engines that no longer exist does not count
            if (!Files.isDirectory(CringleHome.engineDir(home, engineDir.fileName.toString()))) return@forEach
            Files.list(engineDir).use { s -> s.toList() }.forEach { result += readUsage(it) }
        }
        return result
    }

    /**
     * Removes cache-installed versions that are not in use and were last used at least [minUnused] ago.
     * Returns what was removed as `<kind>/<name>@<version>`.
     */
    public fun cleanup(minUnused: Duration): List<String> {
        val used = inUse()
        val now = clock.instant()
        val removed = ArrayList<String>()
        for (type in ArtifactType.entries) {
            val base = home.resolve(type.dir)
            if (!Files.isDirectory(base)) continue
            for (nameDir in Files.list(base).use { it.toList() }) {
                for (versionDir in Files.list(nameDir).use { it.toList() }) {
                    val m = marker(versionDir)
                    if (!Files.exists(m)) continue // not managed by the cache
                    val key = Triple(type, nameDir.fileName.toString(), versionDir.fileName.toString())
                    if (key in used) continue
                    val lastUsed: Instant = Files.getLastModifiedTime(m).toInstant()
                    if (Duration.between(lastUsed, now) < minUnused) continue
                    val label = "${type.dir}/${key.second}@${key.third}"
                    val lock = locks.computeIfAbsent(label) { ReentrantLock() }
                    lock.withLock {
                        withFileLock(label) {
                            if (Files.exists(m)) {
                                deleteTree(versionDir)
                                removed += label
                            }
                        }
                    }
                }
                if (Files.isDirectory(nameDir) && Files.list(nameDir).use { it.findAny().isEmpty }) Files.deleteIfExists(nameDir)
            }
        }
        return removed.sorted()
    }

    private fun deleteTree(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    public companion object {
        // JVM wide: file locks cannot be taken twice by one JVM, so caches of several engines in one process share these
        private val locks = ConcurrentHashMap<String, ReentrantLock>()

        /** Name of the marker file inside an installed version. */
        public const val MARKER: String = ".cringle-installed"
    }
}
