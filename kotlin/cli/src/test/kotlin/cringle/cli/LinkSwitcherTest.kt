// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class LinkSwitcherTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun platformArchiveNames() {
        assertEquals("cringle-1.2.3-linux.tar.gz", Platform.LINUX.archiveName("1.2.3"))
        assertEquals("cringle-1.2.3-windows.zip", Platform.WINDOWS.archiveName("1.2.3"))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun symlinkSwitchPointsCurrentAtTheVersion() {
        val root = temp.resolve("root")
        Files.createDirectories(root.resolve("1.0.0"))
        Files.createDirectories(root.resolve("1.1.0"))

        val switcher = SymlinkLinkSwitcher()
        assertNull(switcher.switchTo(root, "1.0.0"))
        assertEquals("1.0.0", switcher.currentTarget(root))

        assertEquals("1.0.0", switcher.switchTo(root, "1.1.0"))
        assertEquals("1.1.0", switcher.currentTarget(root))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun junctionSwitchPointsCurrentAtTheVersion() {
        val root = temp.resolve("root")
        Files.createDirectories(root.resolve("1.0.0"))
        Files.createDirectories(root.resolve("1.1.0"))

        val switcher = JunctionLinkSwitcher()
        try {
            assertNull(switcher.switchTo(root, "1.0.0"))
            assertEquals("1.0.0", switcher.currentTarget(root))

            assertEquals("1.0.0", switcher.switchTo(root, "1.1.0"))
            assertEquals("1.1.0", switcher.currentTarget(root))
        } finally {
            // the junction is removed itself, so that the cleanup of the temporary folder never follows it
            Files.deleteIfExists(root.resolve("current"))
        }
    }
}
