// SPDX-License-Identifier: Apache-2.0

package cringle.management

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Writes [text] to [target] so that [target] is either the old file or the complete new one, never a part of it: the
 * text goes to a temporary file in the same directory, which is then moved over [target]. A failure leaves [target]
 * as it was and removes the temporary file. [beforeMove] runs between writing and moving (for tests).
 */
internal fun writeAtomically(target: Path, text: String, beforeMove: () -> Unit = {}) {
    val directory = target.toAbsolutePath().parent
    Files.createDirectories(directory)
    val tmp = Files.createTempFile(directory, target.fileName.toString(), ".tmp")
    try {
        Files.writeString(tmp, text)
        beforeMove()
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Throwable) {
        runCatching { Files.deleteIfExists(tmp) }
        throw e
    }
}
