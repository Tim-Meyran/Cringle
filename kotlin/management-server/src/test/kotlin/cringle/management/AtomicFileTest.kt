// SPDX-License-Identifier: Apache-2.0

package cringle.management

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class AtomicFileTest {
    @TempDir
    lateinit var dir: Path

    private fun names(): List<String> = Files.list(dir).use { st -> st.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun writesTheWholeFile() {
        val target = dir.resolve("locks").resolve("a.lock.json")
        writeAtomically(target, "one")
        writeAtomically(target, "two")
        assertEquals("two", Files.readString(target))
    }

    @Test
    fun aFailureLeavesTheOldFileAndNoTemporaryFile() {
        val target = dir.resolve("a.lock.json")
        writeAtomically(target, "old")
        assertThrows<IllegalStateException> { writeAtomically(target, "new") { throw IllegalStateException("disk full") } }
        assertEquals("old", Files.readString(target))
        assertEquals(listOf("a.lock.json"), names())
    }

    @Test
    fun aFailureWithoutAnOldFileLeavesNothing() {
        val target = dir.resolve("b.lock.json")
        assertThrows<IllegalStateException> { writeAtomically(target, "new") { throw IllegalStateException("disk full") } }
        assertEquals(emptyList<String>(), names())
    }
}
