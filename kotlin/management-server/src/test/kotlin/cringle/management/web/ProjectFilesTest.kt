// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ProjectFilesTest {
    @TempDir
    lateinit var dir: Path

    private val files get() = ProjectFiles(dir.resolve("files"))

    @Test
    fun filesAreStoredListedReadAndDeleted() {
        val f = files
        f.put("shop", "readme.txt", "hello".toByteArray())
        f.put("shop", "web/css/site.css", "body {}".toByteArray())
        f.put("shop", "web/index.html", byteArrayOf(1, 2, 3))
        assertEquals(listOf("readme.txt" to 5L, "web/css/site.css" to 7L, "web/index.html" to 3L), f.list("shop").map { it.path to it.size })
        assertArrayEquals(byteArrayOf(1, 2, 3), f.read("shop", "web/index.html"))
        assertNull(f.read("shop", "web/missing.txt"))
        assertEquals(emptyList<ProjectFile>(), f.list("other"), "another project has its own files")
        f.put("shop", "readme.txt", "replaced".toByteArray())
        assertEquals("replaced", f.read("shop", "readme.txt")!!.decodeToString())
        assertEquals(setOf("binaries/readme.txt", "binaries/web/css/site.css", "binaries/web/index.html"), f.all("shop").keys)

        assertTrue(f.delete("shop", "web"), "a folder goes with its files")
        assertEquals(listOf("readme.txt"), f.list("shop").map { it.path })
        assertFalse(f.delete("shop", "web"))
        f.deleteProject("shop")
        assertEquals(emptyList<ProjectFile>(), f.list("shop"))
        assertFalse(Files.exists(dir.resolve("files/shop")))
    }

    @Test
    fun badPathsAreRefused() {
        val f = files
        for (path in listOf("", "/abs.txt", "..", "../x", "a/../b", "a//b", "a\\b", "C:/x", "a b.txt", "a/./b", "x".repeat(201), "a/b/c/d/e/f/g/h/i.txt", ".hidden/", "dir/")) {
            assertThrows<DraftException>(path) { f.put("shop", path, byteArrayOf(1)) }
        }
        assertThrows<DraftException> { f.put("Not A Name", "x.txt", byteArrayOf(1)) }
        assertThrows<DraftException> { f.put("..", "x.txt", byteArrayOf(1)) }
        assertThrows<DraftException> { f.read("shop", "../shop2/x") }
        assertFalse(Files.exists(dir.resolve("x.txt")))
    }

    @Test
    fun aFileAndAFolderDoNotShareAPath() {
        val f = files
        f.put("shop", "docs/readme.txt", byteArrayOf(1))
        assertThrows<DraftException> { f.put("shop", "docs", byteArrayOf(1)) }
        assertThrows<DraftException> { f.put("shop", "docs/readme.txt/sub", byteArrayOf(1)) }
    }

    @Test
    fun theSizeLimitsHold() {
        val f = files
        assertThrows<DraftException> { f.put("shop", "big.bin", ByteArray(ProjectFiles.MAX_FILE + 1)) }
        // the project limit: 5 files of the largest size fill it, the sixth is refused, replacing one is fine
        for (i in 0 until 5) f.put("shop", "part$i.bin", ByteArray(ProjectFiles.MAX_FILE))
        assertThrows<DraftException> { f.put("shop", "part5.bin", ByteArray(1)) }
        f.put("shop", "part0.bin", ByteArray(10))
        f.put("shop", "part5.bin", ByteArray(1))
        assertEquals(6, f.list("shop").size)
    }
}
