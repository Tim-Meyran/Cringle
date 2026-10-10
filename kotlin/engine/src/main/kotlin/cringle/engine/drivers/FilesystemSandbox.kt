// SPDX-License-Identifier: Apache-2.0

package cringle.engine.drivers

import cringle.contract.BuiltinDriverTypes
import cringle.contract.DriverType
import cringle.contract.FilesystemAccessException
import cringle.contract.FilesystemDriver
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [FilesystemDriver] confined to [root]. A path is accepted only if, after resolving `.`/`..` and symbolic links of
 * everything that already exists, it is still inside the root. Checking happens on every call.
 */
public class FilesystemSandbox(private val root: Path, override val type: DriverType = BuiltinDriverTypes.FILESYSTEM) : FilesystemDriver {

    private fun resolve(path: String): Path {
        if (path.contains('\u0000') || path.contains('\\') || path.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(path)) {
            throw FilesystemAccessException("path '$path' is not a relative path inside the block directory")
        }
        Files.createDirectories(root)
        val realRoot = root.toRealPath()
        val target = try {
            realRoot.resolve(path).normalize()
        } catch (e: InvalidPathException) {
            throw FilesystemAccessException("path '$path' is invalid: ${e.reason}")
        }
        if (!target.startsWith(realRoot)) throw FilesystemAccessException("path '$path' leaves the block directory")
        var existing: Path? = target
        while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) existing = existing.parent
        if (existing != null && !existing.toRealPath().startsWith(realRoot)) {
            throw FilesystemAccessException("path '$path' leaves the block directory through a symbolic link")
        }
        return target
    }

    private suspend fun <T> io(path: String, body: (Path) -> T): T = withContext(Dispatchers.IO) {
        val p = resolve(path)
        try {
            body(p)
        } catch (e: IOException) {
            throw e
        }
    }

    override suspend fun readBytes(path: String): ByteArray = io(path) { Files.readAllBytes(it) }

    override suspend fun readText(path: String): String = io(path) { Files.readString(it) }

    override suspend fun writeBytes(path: String, bytes: ByteArray) {
        io(path) {
            Files.createDirectories(it.parent)
            Files.write(it, bytes)
        }
    }

    override suspend fun writeText(path: String, text: String) {
        io(path) {
            Files.createDirectories(it.parent)
            Files.writeString(it, text)
        }
    }

    override suspend fun append(path: String, bytes: ByteArray) {
        io(path) {
            Files.createDirectories(it.parent)
            Files.write(it, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    override suspend fun list(path: String): List<String> = io(path) { dir ->
        Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
    }

    override suspend fun exists(path: String): Boolean = io(path) { Files.exists(it) }

    override suspend fun delete(path: String): Boolean = io(path) { Files.deleteIfExists(it) }

    override suspend fun deleteRecursively(path: String): Boolean = io(path) { target ->
        if (target == root.toRealPath() || !Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            false
        } else {
            // a link inside the tree is removed as a link, never followed
            Files.walk(target).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
            true
        }
    }

    override suspend fun createDirectories(path: String) {
        io(path) { Files.createDirectories(it) }
    }
}
