// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Points `<root>/current` at a version directory. */
internal interface LinkSwitcher {
    /** The version `<root>/current` points to, or null if there is no link. */
    fun currentTarget(root: Path): String?
    /** Points `<root>/current` at `<root>/<version>`; returns the previous target version or null. */
    fun switchTo(root: Path, version: String): String?
}

/** A symbolic link (Linux). */
internal class SymlinkLinkSwitcher : LinkSwitcher {
    override fun currentTarget(root: Path): String? {
        val current = root.resolve("current")
        if (!Files.isSymbolicLink(current)) return null
        return Files.readSymbolicLink(current).fileName?.toString()
    }

    override fun switchTo(root: Path, version: String): String? {
        val current = root.resolve("current")
        val previous = currentTarget(root)
        val temp = root.resolve("current.new")
        Files.deleteIfExists(temp)
        Files.createSymbolicLink(temp, Path.of(version))
        Files.move(temp, current, StandardCopyOption.REPLACE_EXISTING)
        return previous
    }
}

/** A junction (Windows). */
internal class JunctionLinkSwitcher : LinkSwitcher {
    override fun currentTarget(root: Path): String? {
        val current = root.resolve("current")
        if (!Files.exists(current)) return null
        return try {
            Files.readSymbolicLink(current).fileName?.toString()
        } catch (_: Exception) {
            null
        }
    }

    override fun switchTo(root: Path, version: String): String? {
        val current = root.resolve("current")
        val previous = currentTarget(root)
        if (Files.exists(current)) {
            val (code, output) = exec("cmd.exe", "/c", "rmdir", current.toString())
            if (code != 0) throw IllegalStateException("cannot remove the junction $current: ${output.trim()}")
        }
        val (code, output) = exec("cmd.exe", "/c", "mklink", "/J", current.toString(), root.resolve(version).toString())
        if (code != 0) throw IllegalStateException("cannot create the junction $current: ${output.trim()}")
        return previous
    }
}
