// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.common.OwnerOnlyFiles
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The file `bootstrap-token` in the data directory of the ManagementServer. The first start prints the token of the
 * bootstrap admin once; if that output is lost, the token would be gone for good, so it is also written to this file
 * (atomically, with owner-only rights, before the user is stored). The file is deleted at the first successful
 * authentication with that token ([used]), which is the login of the administrator.
 *
 * Only the hash of the token is kept in memory.
 */
internal class BootstrapTokenFile(val file: Path) {
    @Volatile private var hash: ByteArray? = if (Files.isRegularFile(file)) hashOf(Files.readString(file)) else null

    /** Whether the file is there. */
    fun exists(): Boolean = Files.exists(file)

    /** Stores [secret]; called by `UserManager.bootstrap` before it stores anything else. */
    fun write(secret: String) {
        OwnerOnlyFiles.writeAtomically(file, secret + "\n")
        hash = hashOf(secret)
    }

    /** Reports that a call was authenticated with [token]; the file is deleted if that is the bootstrap token. */
    fun used(token: String) {
        val expected = hash ?: return
        if (!MessageDigest.isEqual(expected, hashOf(token))) return
        Files.deleteIfExists(file)
        hash = null
    }

    private fun hashOf(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.trim().toByteArray(Charsets.UTF_8))
}