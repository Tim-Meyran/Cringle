// SPDX-License-Identifier: Apache-2.0

package cringle.common

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir

class OwnerOnlyFilesTest {
    @TempDir
    lateinit var dir: Path

    /** Only the owner may access [file]: `rw-------` on POSIX, one ACL entry for the owner and nothing inherited on Windows. */
    private fun assertOwnerOnly(file: Path) {
        val views = file.fileSystem.supportedFileAttributeViews()
        when {
            "posix" in views -> assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)), file.toString())
            "acl" in views -> {
                val acl = Files.getFileAttributeView(file, AclFileAttributeView::class.java).acl
                assertEquals(1, acl.size, "the access list of $file has other entries: $acl")
                assertEquals(AclEntryType.ALLOW, acl.single().type())
                assertEquals(Files.getOwner(file), acl.single().principal())
            }
            else -> fail("this test needs a file system with POSIX permissions or ACLs, found $views")
        }
    }

    @Test
    fun aNewFileHasOwnerOnlyRightsBeforeAnythingIsWrittenToIt() {
        val file = dir.resolve("secret")
        OwnerOnlyFiles.create(file)
        // right after creation: the file is still empty, so there was no time with content and default rights
        assertEquals(0, Files.size(file))
        assertOwnerOnly(file)
    }

    @Test
    fun anExistingFileIsNeverTakenOver() {
        val file = dir.resolve("secret")
        Files.writeString(file, "somebody else's")
        assertThrows<FileAlreadyExistsException> { OwnerOnlyFiles.create(file) }
        assertEquals("somebody else's", Files.readString(file))
    }

    @Test
    fun writeAtomicallyReplacesTheFileAndKeepsTheRights() {
        val file = dir.resolve("sub").resolve("profile.json")
        OwnerOnlyFiles.writeAtomically(file, "one")
        assertOwnerOnly(file)
        OwnerOnlyFiles.writeAtomically(file, "two")
        assertEquals("two", Files.readString(file))
        assertOwnerOnly(file)
        assertEquals(listOf("profile.json"), Files.list(file.parent).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun aFailedWriteLeavesTheOldFileAndNoTemporaryFile() {
        val target = dir.resolve("target")
        // a directory with content cannot be replaced by a file, so the move fails after the temporary file was written
        Files.createDirectories(target.resolve("inner"))
        assertThrows<java.io.IOException> { OwnerOnlyFiles.writeAtomically(target, "secret") }
        assertTrue(Files.isDirectory(target.resolve("inner")))
        assertEquals(listOf("target"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun aLeftOverTemporaryFileIsReplacedAndDoesNotKeepItsRights() {
        val file = dir.resolve("profile.json")
        // a crash of an earlier run left a temporary file with open rights behind
        Files.writeString(dir.resolve("profile.json.tmp"), "stale")
        OwnerOnlyFiles.writeAtomically(file, "fresh")
        assertEquals("fresh", Files.readString(file))
        assertOwnerOnly(file)
        assertEquals(listOf("profile.json"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
    }
}
