// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.packaging.PackageNames
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** A file of a project: [path] is relative and `/`-separated, [size] in bytes. */
internal data class ProjectFile(val path: String, val size: Long)

/**
 * The binary files of the projects (`binaries/` of the package): `<root>/<project>/<path>`. A path is relative and `/`-separated, every segment follows the
 * identifier grammar (letters, digits, `.`, `-`, `_`; no `..`), at most [MAX_DEPTH] segments and [MAX_PATH] characters. A file is at most [MAX_FILE] bytes, a
 * project at most [MAX_PROJECT] bytes. Files are written atomically.
 */
internal class ProjectFiles(private val root: Path) {
    private val lock = Any()

    private fun projectDirectory(project: String): Path {
        PackageNames.nameProblem(project)?.let { throw DraftException("project name: $it") }
        return root.resolve(project).normalize().also { if (!it.startsWith(root.normalize())) throw DraftException("invalid project name '$project'") }
    }

    /** The checked path segments of [path]; throws [DraftException] with the reason. */
    fun segments(path: String): List<String> {
        if (path.isEmpty() || path.length > MAX_PATH) throw DraftException("a file path has 1 to $MAX_PATH characters")
        if (path.startsWith("/") || path.contains('\\') || path.contains(':')) throw DraftException("a file path is relative and written with '/': '$path'")
        val parts = path.split('/')
        if (parts.size > MAX_DEPTH) throw DraftException("a file path has at most $MAX_DEPTH levels")
        for (part in parts) PackageNames.identifierProblem(part)?.let { throw DraftException("file path '$path': $it") }
        return parts
    }

    private fun resolve(project: String, path: String): Path {
        val base = projectDirectory(project)
        return segments(path).fold(base) { dir, part -> dir.resolve(part) }.normalize().also { if (!it.startsWith(base)) throw DraftException("invalid file path '$path'") }
    }

    /** All files of [project], by path. */
    fun list(project: String): List<ProjectFile> = synchronized(lock) {
        val base = projectDirectory(project)
        if (!Files.isDirectory(base)) return emptyList()
        Files.walk(base).use { stream ->
            stream.filter { Files.isRegularFile(it) }.map { ProjectFile(base.relativize(it).toString().replace('\\', '/'), Files.size(it)) }.toList()
        }.sortedBy { it.path }
    }

    /** Stores [data] at [path] (replacing a file that is there). */
    fun put(project: String, path: String, data: ByteArray) = synchronized(lock) {
        if (data.size > MAX_FILE) throw DraftException("a file is at most ${MAX_FILE / (1024 * 1024)} MiB, this one has ${data.size / (1024 * 1024)} MiB")
        val target = resolve(project, path)
        val others = list(project).filter { it.path != path }.sumOf { it.size }
        if (others + data.size > MAX_PROJECT) throw DraftException("the files of a project are at most ${MAX_PROJECT / (1024 * 1024)} MiB together")
        // a file cannot stand where a folder is, nor below a file
        if (Files.isDirectory(target)) throw DraftException("'$path' is a folder")
        var parent = target.parent
        val base = projectDirectory(project)
        while (parent != null && parent != base) {
            if (Files.isRegularFile(parent)) throw DraftException("'${base.relativize(parent).toString().replace('\\', '/')}' is a file, not a folder")
            parent = parent.parent
        }
        val directory = target.toAbsolutePath().parent
        Files.createDirectories(directory)
        val tmp = Files.createTempFile(directory, "upload", ".tmp")
        try {
            Files.write(tmp, data)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    /** The content of the file, or `null` if there is none. */
    fun read(project: String, path: String): ByteArray? = synchronized(lock) { resolve(project, path).takeIf { Files.isRegularFile(it) }?.let { Files.readAllBytes(it) } }

    /** Deletes the file or the folder with everything in it; returns whether something was deleted. */
    fun delete(project: String, path: String): Boolean = synchronized(lock) {
        val target = resolve(project, path)
        if (!Files.exists(target)) return false
        Files.walk(target).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        true
    }

    /** Removes all files of [project]. */
    fun deleteProject(project: String) = synchronized(lock) {
        val base = projectDirectory(project)
        if (Files.isDirectory(base)) Files.walk(base).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }

    /** The entries of the package: `binaries/<path>` to the content. */
    fun all(project: String): Map<String, ByteArray> = synchronized(lock) { list(project).associate { "binaries/${it.path}" to read(project, it.path)!! } }

    companion object {
        const val MAX_DEPTH = 8
        const val MAX_PATH = 200
        const val MAX_FILE = 20 * 1024 * 1024
        const val MAX_PROJECT = 100 * 1024 * 1024
    }
}
