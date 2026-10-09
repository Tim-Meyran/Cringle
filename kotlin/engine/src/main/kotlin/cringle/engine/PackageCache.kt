// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import cringle.packaging.PackageFormatException
import cringle.packaging.PackageHash
import cringle.packaging.PackageHashMismatchException
import cringle.packaging.SafeUnzip
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.channels.FileChannel
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
 * The outcome of [PackageCache.cleanupReport]: the versions that were removed as `<kind>/<name>@<version>`, and the
 * versions that could not be removed as `<kind>/<name>@<version>: <reason>`. A version that could not be removed is
 * still there, complete and usable.
 */
public class CleanupReport(public val removed: List<String>, public val failed: List<String>)

/** The two file operations of [PackageCache] that can fail on a locked file; replaced in tests to make them fail. */
internal interface CacheFileOps {
    /** Renames [from] to [to] in one step; fails if a file in [from] is locked (Windows). */
    fun rename(from: Path, to: Path)

    /** Deletes [dir] and everything in it; throws the last error if something could not be deleted. */
    fun deleteTree(dir: Path)
}

/** Renames atomically; deletes entry by entry and tries a locked file a few times before it gives up. */
internal class DefaultCacheFileOps(private val attempts: Int = 3, private val pauseMillis: Long = 100) : CacheFileOps {
    override fun rename(from: Path, to: Path) {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun deleteTree(dir: Path) {
        if (!Files.exists(dir)) return
        var failure: IOException? = null
        Files.walk(dir).use { s ->
            s.sorted(Comparator.reverseOrder()).forEach { entry ->
                var left = attempts
                while (true) {
                    try {
                        Files.deleteIfExists(entry)
                        break
                    } catch (_: NoSuchFileException) {
                        break
                    } catch (e: IOException) {
                        if (--left <= 0) {
                            failure = e
                            break
                        }
                        Thread.sleep(pauseMillis)
                    }
                }
            }
        }
        failure?.let { throw it }
    }
}

/**
 * The package cache of a machine (Architecture 15): unpacked packages in `<home>/projects/<name>/<version>` and
 * `<home>/plugins/<name>/<version>`, shared by all Engines that use the same Cringle home.
 *
 * A version is downloaded, verified and unpacked once. Installation happens in a temporary directory that is moved into
 * place atomically, so a failed or corrupt download never leaves anything behind, and a file lock (plus an in-process
 * lock) per version makes concurrent Engines wait for each other instead of installing twice. [ensure] and [cleanup]
 * take the same lock, so they never meet a version in the middle of the other. A marker file `.cringle-installed`
 * holds the hash of the installed package; only versions with a marker are managed (and cleaned up) by the cache. A
 * version directory without a marker was placed by hand: [ensure] refuses to touch it and [cleanup] ignores it.
 *
 * Removing a version is a rename to `<version>.removing-<uuid>` followed by the deletion of that directory. The rename
 * is atomic, so a version is either complete or gone, never half deleted; if a file in it is locked (Windows) the
 * rename fails and the version stays as it was. What an interrupted deletion, installation or download left behind
 * (`*.removing-*`, `*.installing-*` directories and `*.part` files) is removed by [sweepLeftovers] and [cleanup] once
 * it is older than [LEFTOVER_AGE]; these names never count as versions.
 *
 * Usage is tracked per Engine and fabric in `<home>/cache/usage/<engine>/<fabric>`; a version is unused when no fabric of
 * an existing Engine lists it.
 */
public class PackageCache internal constructor(
    private val home: Path,
    private val clock: java.time.Clock,
    private val ops: CacheFileOps,
    private val onWarning: (String) -> Unit,
) {
    /** A cache of [home]; [onWarning] gets what the cache could not do but survived (by default the error stream). */
    public constructor(
        home: Path,
        clock: java.time.Clock = java.time.Clock.systemUTC(),
        onWarning: (String) -> Unit = { System.err.println("WARNING: $it") },
    ) : this(home, clock, DefaultCacheFileOps(), onWarning)

    private val root = home.resolve("cache")

    private fun target(a: Artifact): Path = home.resolve(a.type.dir).resolve(a.name).resolve(a.version)

    private fun marker(dir: Path): Path = dir.resolve(MARKER)

    private fun warn(message: String) {
        runCatching { onWarning(message) }
    }

    /**
     * Makes sure [artifact] is installed, downloading it with [fetcher] if needed.
     * Returns `true` if this call installed it, `false` if it was already there.
     */
    public suspend fun ensure(artifact: Artifact, fetcher: PackageFetcher): Boolean {
        val dir = target(artifact)
        val key = artifact.label
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        // the locks are taken on a blocking thread; the download runs in the caller's coroutine context
        return withContext(Dispatchers.IO) {
            lock.withLock {
                withFileLock(key) {
                    if (isInstalled(artifact, dir)) {
                        touch(dir)
                        false
                    } else {
                        install(artifact, dir, fetcher)
                    }
                }
            }
        }
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
            // A cleanup of another engine removes name directories that are empty, also the one this install has just created, and Windows
            // refuses to create or move something into a directory that is being deleted: a failed step is tried again from the beginning.
            var attempt = 1
            while (true) {
                try {
                    Files.createDirectories(dir.parent)
                    SafeUnzip.extract(file, staging)
                    Files.writeString(marker(staging), a.sha256.lowercase() + "\n")
                    touch(staging)
                    Files.move(staging, dir, StandardCopyOption.ATOMIC_MOVE)
                    return true
                } catch (e: IOException) {
                    if (attempt >= INSTALL_ATTEMPTS) throw e
                    runCatching { ops.deleteTree(staging) }
                    attempt += 1
                    Thread.sleep(10L * attempt)
                }
            }
        } catch (e: PackageHashMismatchException) {
            throw PackageCacheException(true, "${a.label}: downloaded file does not match the expected hash (${e.message})", e)
        } catch (e: PackageFormatException) {
            throw PackageCacheException(false, "${a.label}: invalid package: ${e.message}", e)
        } catch (e: IOException) {
            throw PackageCacheException(false, "${a.label}: cannot install: ${e.message}", e)
        } finally {
            runCatching { Files.deleteIfExists(file) }
            runCatching { ops.deleteTree(staging) }
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

    /**
     * Records that [fabric] of [engine] uses [artifacts] and makes sure each one is installed. The usage is recorded
     * before the installation, so a concurrent cleanup cannot remove a freshly installed version between the install
     * and the record: without this ordering, a version that was just installed would not yet be "in use" and could be
     * swept away by another Engine's [cleanupReport] before this deploy records its usage.
     *
     * If a record already exists for this fabric, it is NOT overwritten: the fabric is already deployed with those
     * artifacts, and the new artifacts are installed but not recorded. On failure, the record is cleared only if this
     * deploy created it, so a failed redeploy of an already-deployed fabric leaves the original record intact.
     *
     * Returns `true` if the record was created by this call, `false` if it already existed.
     */
    public suspend fun ensureForDeploy(
        engine: String,
        fabric: String,
        artifacts: List<Artifact>,
        fetcher: PackageFetcher,
    ): Boolean {
        val file = usageFile(engine, fabric)
        val recordExisted = Files.exists(file)
        if (!recordExisted) recordUsage(engine, fabric, artifacts)
        try {
            for (artifact in artifacts) {
                ensure(artifact, fetcher)
            }
        } catch (e: Throwable) {
            if (!recordExisted) clearUsage(engine, fabric)
            throw e
        }
        return !recordExisted
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

    /** The versions a usage file names; a file that was removed in the meantime (the fabric ended) names none. */
    private fun readUsage(file: Path): List<Triple<ArtifactType, String, String>> {
        val lines = try {
            Files.readAllLines(file)
        } catch (e: java.nio.file.NoSuchFileException) {
            return emptyList()
        } catch (e: java.nio.file.FileSystemException) {
            // Windows: a file that another thread is deleting right now cannot be opened (access denied or sharing violation), it is gone
            if (Files.exists(file)) throw e
            return emptyList()
        }
        return lines.filter { it.isNotBlank() }.map { line ->
            val (type, name, version) = line.split(' ')
            Triple(ArtifactType.valueOf(type), name, version)
        }
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

    // --- cleanup ---

    /**
     * Removes cache-installed versions that are not in use and were last used at least [minUnused] ago.
     * Returns what was removed as `<kind>/<name>@<version>`; [cleanupReport] also says what could not be removed.
     */
    public fun cleanup(minUnused: Duration): List<String> = cleanupReport(minUnused).removed

    /**
     * Like [cleanup], and reports the versions it could not remove. One version that cannot be removed (for example
     * because a file in it is locked) does not stop the others. Leftovers of interrupted work are removed as well, see
     * [sweepLeftovers].
     */
    public fun cleanupReport(minUnused: Duration): CleanupReport {
        val removed = ArrayList<String>()
        val failed = ArrayList<String>()
        val candidates = inUse().let { used ->
            versionDirs().filter { (type, name, version, _) -> Triple(type, name, version) !in used }
        }
        for ((type, name, version, versionDir) in candidates) {
            val label = "${type.dir}/$name@$version"
            try {
                val lock = locks.computeIfAbsent(label) { ReentrantLock() }
                lock.withLock {
                    withFileLock(label) {
                        // everything that decides is looked at under the lock of the version, which ensure takes as well
                        val m = marker(versionDir)
                        if (!Files.exists(m)) return@withFileLock // gone, or placed by hand
                        if (Triple(type, name, version) in inUse()) return@withFileLock
                        val lastUsed: Instant = Files.getLastModifiedTime(m).toInstant()
                        if (Duration.between(lastUsed, clock.instant()) < minUnused) return@withFileLock
                        remove(versionDir, label, removed, failed)
                    }
                }
            } catch (e: IOException) {
                failed += "$label: ${e.message ?: e.javaClass.simpleName}"
                warn("$label could not be removed from the package cache: ${e.message}")
            }
        }
        removeEmptyNameDirs()
        sweepLeftovers()
        return CleanupReport(removed.sorted(), failed.sorted())
    }

    /** Renames the version away atomically, then deletes it. Called with the lock of the version. */
    private fun remove(versionDir: Path, label: String, removed: MutableList<String>, failed: MutableList<String>) {
        val gone = versionDir.resolveSibling(versionDir.fileName.toString() + ".removing-" + UUID.randomUUID())
        try {
            ops.rename(versionDir, gone)
        } catch (e: IOException) {
            // nothing changed: the version is still there and complete
            failed += "$label: ${e.message ?: e.javaClass.simpleName}"
            warn("$label could not be removed from the package cache, it is in use or locked: ${e.message}")
            return
        }
        removed += label
        // the age of the leftover counts from now, not from when the version was installed
        runCatching { Files.setLastModifiedTime(gone, FileTime.from(clock.instant())) }
        try {
            ops.deleteTree(gone)
        } catch (e: IOException) {
            // the version is gone under its name; the rest is swept later
            warn("$label was removed, but ${gone.fileName} could not be deleted completely and is left for a later cleanup: ${e.message}")
        }
    }

    private data class VersionDir(val type: ArtifactType, val name: String, val version: String, val dir: Path)

    /** The cache-installed versions (with marker); leftovers and hand-placed directories are not among them. */
    private fun versionDirs(): List<VersionDir> {
        val result = ArrayList<VersionDir>()
        for (type in ArtifactType.entries) {
            val base = home.resolve(type.dir)
            if (!Files.isDirectory(base)) continue
            for (nameDir in Files.list(base).use { it.toList() }) {
                if (!Files.isDirectory(nameDir)) continue
                for (versionDir in Files.list(nameDir).use { it.toList() }) {
                    if (LEFTOVER.matches(versionDir.fileName.toString()) || !Files.exists(marker(versionDir))) continue
                    result += VersionDir(type, nameDir.fileName.toString(), versionDir.fileName.toString(), versionDir)
                }
            }
        }
        return result
    }

    private fun removeEmptyNameDirs() {
        for (type in ArtifactType.entries) {
            val base = home.resolve(type.dir)
            if (!Files.isDirectory(base)) continue
            for (nameDir in Files.list(base).use { it.toList() }) {
                runCatching {
                    if (Files.isDirectory(nameDir) && Files.list(nameDir).use { it.findAny().isEmpty }) Files.deleteIfExists(nameDir)
                }
            }
        }
    }

    /**
     * Removes what interrupted work left behind and that is older than [LEFTOVER_AGE]: `*.part` files in
     * `cache/downloads`, and `*.installing-*` and `*.removing-*` directories next to the versions. Younger ones may
     * belong to an Engine that is working on them right now. Called when an Engine starts and by [cleanup]; it never
     * throws. Returns what it removed.
     */
    public fun sweepLeftovers(): List<String> {
        val removed = ArrayList<String>()
        val limit = clock.instant().minus(LEFTOVER_AGE)
        fun old(path: Path): Boolean = try {
            Files.getLastModifiedTime(path).toInstant().isBefore(limit)
        } catch (_: IOException) {
            false
        }
        val downloads = root.resolve("downloads")
        if (Files.isDirectory(downloads)) {
            runCatching {
                for (file in Files.list(downloads).use { it.toList() }) {
                    if (file.fileName.toString().endsWith(".part") && Files.isRegularFile(file) && old(file)) {
                        try {
                            Files.deleteIfExists(file)
                            removed += "cache/downloads/${file.fileName}"
                        } catch (e: IOException) {
                            warn("cannot remove the old download ${file.fileName}: ${e.message}")
                        }
                    }
                }
            }
        }
        for (type in ArtifactType.entries) {
            val base = home.resolve(type.dir)
            if (!Files.isDirectory(base)) continue
            runCatching {
                for (nameDir in Files.list(base).use { it.toList() }) {
                    if (!Files.isDirectory(nameDir)) continue
                    for (dir in Files.list(nameDir).use { it.toList() }) {
                        if (!LEFTOVER.matches(dir.fileName.toString()) || !Files.isDirectory(dir) || !old(dir)) continue
                        try {
                            ops.deleteTree(dir)
                            removed += "${type.dir}/${nameDir.fileName}/${dir.fileName}"
                        } catch (e: IOException) {
                            warn("cannot remove the leftover ${dir.fileName} of ${type.dir}/${nameDir.fileName}: ${e.message}")
                        }
                    }
                }
            }
        }
        removeEmptyNameDirs()
        return removed.sorted()
    }

    public companion object {
        // JVM wide: file locks cannot be taken twice by one JVM, so caches of several engines in one process share these
        private val locks = ConcurrentHashMap<String, ReentrantLock>()

        /** Directories of an unfinished installation or removal: `<version>.installing-<uuid>`, `<version>.removing-<uuid>`. */
        private val LEFTOVER = Regex(".+\\.(installing|removing)-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        private const val INSTALL_ATTEMPTS = 5

        /** Name of the marker file inside an installed version. */
        public const val MARKER: String = ".cringle-installed"

        /** How old leftovers of interrupted work have to be before they are removed. */
        public val LEFTOVER_AGE: Duration = Duration.ofHours(1)
    }
}
