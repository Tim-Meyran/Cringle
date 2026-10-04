// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Unpacks the release archives. Entries that would escape the target directory are rejected. */
internal object Archive {
    /** Unpacks a `.tar.gz` into [target]; the executable bit of the entries is preserved. */
    fun extractTarGz(input: InputStream, target: Path) {
        GZIPInputStream(input).use { gz -> extractTar(gz, target) }
    }

    /** Unpacks a `.zip` into [target]. */
    fun extractZip(input: InputStream, target: Path) {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = resolve(target, entry.name)
                if (entry.isDirectory) {
                    Files.createDirectories(out)
                } else {
                    Files.createDirectories(out.parent)
                    Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun extractTar(input: InputStream, target: Path) {
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            if (input.readNBytes(header, 0, 512) < 512 || header.all { it == 0.toByte() }) break
            val name = text(header, 0, 100)
            val mode = text(header, 100, 8).ifEmpty { "0" }.toInt(8)
            val size = text(header, 124, 12).ifEmpty { "0" }.toLong(8)
            val type = header[156].toInt().toChar()
            val prefix = if (text(header, 257, 5) == "ustar") text(header, 345, 155) else ""
            if (type == 'L') {
                longName = String(input.readNBytes(size.toInt())).trimEnd('\u0000')
                input.skipNBytes((512 - size % 512) % 512)
                continue
            }
            val fullName = longName ?: if (prefix.isNotEmpty()) "$prefix/$name" else name
            longName = null
            val out = resolve(target, fullName)
            when (type) {
                '5' -> Files.createDirectories(out)
                '0', '\u0000' -> {
                    Files.createDirectories(out.parent)
                    Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { copyExactly(input, it, size) }
                    if (mode and 0b001_001_001 != 0) out.toFile().setExecutable(true, false)
                }
                else -> input.skipNBytes(size)
            }
            input.skipNBytes((512 - size % 512) % 512)
        }
    }

    private fun copyExactly(input: InputStream, out: OutputStream, size: Long) {
        var left = size
        val buffer = ByteArray(64 * 1024)
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw IllegalStateException("the archive ends before the entry is complete")
            out.write(buffer, 0, n)
            left -= n
        }
    }

    private fun resolve(target: Path, name: String): Path {
        val normalized = target.resolve(name).normalize()
        if (!normalized.startsWith(target.normalize())) throw IllegalStateException("the archive contains an entry outside the target directory: $name")
        return normalized
    }

    private fun text(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.ISO_8859_1).trim { it == ' ' || it == '\u0000' }
}
