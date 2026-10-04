// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.Comparator

/**
 * The self-update of an installed distribution (#60). It downloads a release, verifies it, unpacks it next to the
 * running version, switches `current` and restarts the services; if the new version does not come up, it switches
 * back. All side effects go through [source], [services] and [links], so the tests can replace them.
 */
internal class SelfUpdate(
    private val root: Path,
    private val runningVersion: String,
    private val platform: Platform,
    private val source: ReleaseSource,
    private val services: ServiceController,
    private val links: LinkSwitcher,
    private val timeout: Duration = Duration.ofSeconds(30),
    private val allowMajor: Boolean = false,
) {
    /** `self-update --check`: the current and the latest version and whether an update is available. */
    fun check(): List<String> {
        val current = currentVersion()
        val latest = source.latestVersion() ?: throw IllegalStateException("cannot find the latest release")
        val available = SemVer.parseOrThrow(latest) > SemVer.parseOrThrow(current)
        return listOf("current $current", "latest $latest", if (available) "update available" else "up to date")
    }

    /** `self-update [--version <v>]`: installs [requested] or the latest version. Throws on failure. */
    fun update(requested: String?): List<String> {
        val lines = ArrayList<String>()
        val current = currentVersion()
        val target = requested ?: source.latestVersion() ?: throw IllegalStateException("cannot find the latest release")
        SemVer.parseOrThrow(target)
        if (requested == null && target == current) {
            lines += "already up to date ($current)"
            return lines
        }
        if (SemVer.parseOrThrow(target).major != SemVer.parseOrThrow(current).major && !allowMajor) {
            throw UsageException("$target is a different major version than $current; use --allow-major to update anyway")
        }
        val manifest = source.manifest(target)
        val archiveName = platform.archiveName(target)
        val file = manifest.files.firstOrNull { it.name == archiveName }
            ?: throw IllegalStateException("the release $target has no $archiveName")
        val sums = source.checksums(target)
        val expected = sums[file.name] ?: throw IllegalStateException("SHA256SUMS of $target has no entry for ${file.name}")
        if (!expected.equals(file.sha256, ignoreCase = true)) {
            throw IllegalStateException("manifest.json and SHA256SUMS of $target disagree about ${file.name}")
        }

        val staging = Files.createTempDirectory(root, ".update-")
        try {
            val archive = staging.resolve(file.name)
            lines += "downloading $target"
            source.download(target, file.name, archive)
            val size = Files.size(archive)
            if (size != file.size) throw IllegalStateException("${file.name} has $size bytes, expected ${file.size}")
            val actual = sha256(archive)
            if (!actual.equals(file.sha256, ignoreCase = true)) {
                throw IllegalStateException("${file.name} has SHA-256 $actual, expected ${file.sha256}")
            }

            lines += "unpacking $target"
            val active = links.currentTarget(root)
            val targetDir = root.resolve(target)
            val unpackDir = Files.createTempDirectory(root, ".unpack-")
            try {
                unpack(archive, unpackDir)
                val top = unpackDir.resolve("cringle-$target")
                if (!Files.isDirectory(top)) throw IllegalStateException("the archive has no cringle-$target directory")
                if (Files.exists(targetDir) && targetDir.fileName.toString() == active) {
                    // reinstalling the active version: keep the files, only restart below
                } else {
                    deleteTree(targetDir)
                    Files.move(top, targetDir)
                }
            } finally {
                deleteTree(unpackDir)
            }

            val activeServices = services.activeServices()
            if (platform == Platform.WINDOWS) activeServices.forEach { services.stop(it) }
            val previous = links.switchTo(root, target)
            lines += "switched current to $target"
            if (platform == Platform.WINDOWS) activeServices.forEach { services.start(it) } else activeServices.forEach { services.restart(it) }
            lines += "restarted ${activeServices.size} service(s)"

            if (!healthy(activeServices)) {
                lines += "the new version did not come up; rolling back to ${previous ?: current}"
                links.switchTo(root, previous ?: current)
                if (platform == Platform.WINDOWS) activeServices.forEach { services.stop(it) }
                if (platform == Platform.WINDOWS) activeServices.forEach { services.start(it) } else activeServices.forEach { services.restart(it) }
                deleteTree(targetDir)
                throw IllegalStateException("the new version $target did not start; rolled back to ${previous ?: current}")
            }

            cleanup(target, runningVersion)
            lines += "updated to $target"
            return lines
        } finally {
            deleteTree(staging)
        }
    }

    private fun currentVersion(): String = links.currentTarget(root) ?: runningVersion

    private fun unpack(archive: Path, target: Path) {
        Files.newInputStream(archive).use { input ->
            when (platform) {
                Platform.LINUX -> Archive.extractTarGz(input, target)
                Platform.WINDOWS -> Archive.extractZip(input, target)
            }
        }
    }

    private fun healthy(names: List<String>): Boolean {
        if (names.isEmpty()) return true
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            if (names.all { services.isActive(it) }) return true
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(200)
        }
    }

    /** Keeps the two newest versions and never deletes the active or the running one. */
    private fun cleanup(active: String, running: String) {
        val versions = Files.list(root).use { stream ->
            stream.filter { Files.isDirectory(it) && SemVer.parse(it.fileName.toString()) != null }.toList()
        }
        val keep = versions.sortedWith(compareByDescending { SemVer.parse(it.fileName.toString())!! }).take(2)
            .map { it.fileName.toString() }.toMutableSet()
        keep += active
        keep += running
        for (dir in versions) {
            if (dir.fileName.toString() !in keep) deleteTree(dir)
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
