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
        // Java cannot read a junction with readSymbolicLink (it is no symbolic link); the real path follows it to the version
        return try {
            current.toRealPath().fileName?.toString()
        } catch (_: java.io.IOException) {
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

/**
 * The switcher of [platform]: a junction on Windows, a symbolic link elsewhere. A junction needs no privilege, a symbolic
 * link on Windows needs the right to create one (administrator or developer mode), which an installation cannot count on.
 */
internal fun linkSwitcherFor(platform: Platform): LinkSwitcher =
    if (platform == Platform.WINDOWS) JunctionLinkSwitcher() else SymlinkLinkSwitcher()
