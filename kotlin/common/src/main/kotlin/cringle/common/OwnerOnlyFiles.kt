// SPDX-License-Identifier: Apache-2.0

package cringle.common

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.util.EnumSet

/**
 * Files with secrets (tokens, private keys) that only their owner may access, from the first moment of their life: the
 * access rights are given when the file is created, never afterwards, so there is no time in which the secret could be
 * read by others.
 *
 * On POSIX file systems the file is created with `rw-------`. On Windows (NTFS) it is created with an access control
 * list that has one entry, full access for the owner, and no inherited entries. Other file systems have no access
 * control that could be used; the file is created normally there.
 */
public object OwnerOnlyFiles {
    private val POSIX_OWNER_ONLY = PosixFilePermissions.fromString("rw-------")

    /** Creates the new, empty file [file] that only its owner can access. Fails if [file] exists. */
    public fun create(file: Path) {
        val views = file.fileSystem.supportedFileAttributeViews()
        when {
            "posix" in views -> Files.createFile(file, PosixFilePermissions.asFileAttribute(POSIX_OWNER_ONLY))
            "acl" in views -> createWithAcl(file)
            else -> Files.createFile(file)
        }
    }

    /**
     * Writes [content] to [target] so that [target] is either the old file or the complete new one, and so that the
     * content is never in a file that others can read: the temporary file is created with [create] before anything is
     * written to it, and [target] gets its rights by the move.
     */
    public fun writeAtomically(target: Path, content: String) {
        val directory = target.toAbsolutePath().parent
        Files.createDirectories(directory)
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.deleteIfExists(tmp)
        try {
            create(tmp)
            Files.writeString(tmp, content)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    private fun createWithAcl(file: Path) {
        val owner = currentUser(file)
        if (owner != null) {
            try {
                // the access list is part of the creation: there is no moment with the default list
                Files.createFile(file, ownerOnlyAcl(owner))
                return
            } catch (e: UnsupportedOperationException) {
                // the file system does not take the list at creation; it is set before any content is written
            }
        }
        Files.createFile(file)
        try {
            restrict(file, Files.getOwner(file))
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(file) }
            throw e
        }
    }

    /** Replaces the access list by the one entry for [owner]; this also removes everything the file inherited. */
    private fun restrict(file: Path, owner: UserPrincipal) {
        val view = Files.getFileAttributeView(file, AclFileAttributeView::class.java) ?: return
        view.acl = listOf(ownerEntry(owner))
    }

    private fun currentUser(file: Path): UserPrincipal? = try {
        file.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    } catch (_: IOException) {
        null
    }

    private fun ownerEntry(owner: UserPrincipal): AclEntry = AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(owner)
        .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
        .build()

    private fun ownerOnlyAcl(owner: UserPrincipal): FileAttribute<List<AclEntry>> = object : FileAttribute<List<AclEntry>> {
        override fun name(): String = "acl:acl"

        override fun value(): List<AclEntry> = listOf(ownerEntry(owner))
    }
}
