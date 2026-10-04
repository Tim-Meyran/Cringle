// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ArchiveTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun extractTarGzWritesFilesAndKeepsTheExecutableBit() {
        val archive = tarGz(
            Entry("cringle-1.0.0/bin/cringle", "#!/bin/sh\n".toByteArray(), mode = 0b111_101_101),
            Entry("cringle-1.0.0/VERSION", "1.0.0\n".toByteArray(), mode = 0b110_100_100),
        )

        Archive.extractTarGz(ByteArrayInputStream(archive), temp)

        val binary = temp.resolve("cringle-1.0.0/bin/cringle")
        val version = temp.resolve("cringle-1.0.0/VERSION")
        assertTrue(Files.exists(binary))
        assertTrue(Files.exists(version))
        assertEquals("#!/bin/sh\n", Files.readString(binary))
        assertEquals("1.0.0\n", Files.readString(version))
        assertTrue(Files.isExecutable(binary))
    }

    @Test
    fun extractZipWritesFiles() {
        val archive = zip(Entry("cringle-1.0.0/bin/cringle.bat", "@echo off\r\n".toByteArray()))

        Archive.extractZip(ByteArrayInputStream(archive), temp)

        val script = temp.resolve("cringle-1.0.0/bin/cringle.bat")
        assertTrue(Files.exists(script))
        assertEquals("@echo off\r\n", Files.readString(script))
    }

    @Test
    fun tarEntryOutsideTheTargetIsRejected() {
        val archive = tarGz(Entry("../evil", "x".toByteArray()))

        assertThrows(IllegalStateException::class.java) {
            Archive.extractTarGz(ByteArrayInputStream(archive), temp)
        }
        assertFalse(Files.exists(temp.resolve("evil")))
    }

    @Test
    fun zipEntryOutsideTheTargetIsRejected() {
        val archive = zip(Entry("../evil", "x".toByteArray()))

        assertThrows(IllegalStateException::class.java) {
            Archive.extractZip(ByteArrayInputStream(archive), temp)
        }
        assertFalse(Files.exists(temp.resolve("evil")))
    }

    private class Entry(val name: String, val content: ByteArray, val mode: Int = 0b110_100_100, val type: Char = '0')

    private fun tarGz(vararg entries: Entry): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz ->
            for (entry in entries) {
                gz.write(tarHeader(entry))
                gz.write(entry.content)
                gz.write(ByteArray((512 - entry.content.size % 512) % 512))
            }
            gz.write(ByteArray(1024))
        }
        return out.toByteArray()
    }

    private fun tarHeader(entry: Entry): ByteArray {
        val header = ByteArray(512)
        putString(header, 0, entry.name, 100)
        putOctal(header, 100, entry.mode.toLong(), 7)
        putOctal(header, 108, 0, 7)
        putOctal(header, 116, 0, 7)
        putOctal(header, 124, entry.content.size.toLong(), 11)
        putOctal(header, 136, 0, 11)
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        header[156] = entry.type.code.toByte()
        putString(header, 257, "ustar", 5)
        putString(header, 263, "00", 2)
        val checksum = header.sumOf { it.toInt() and 0xFF }
        putString(header, 148, checksum.toString(8).padStart(6, '0'), 6)
        header[154] = 0
        header[155] = ' '.code.toByte()
        return header
    }

    private fun putString(header: ByteArray, offset: Int, value: String, length: Int) {
        val bytes = value.toByteArray(Charsets.ISO_8859_1)
        require(bytes.size <= length) { "'$value' does not fit into $length bytes" }
        System.arraycopy(bytes, 0, header, offset, bytes.size)
    }

    private fun putOctal(header: ByteArray, offset: Int, value: Long, digits: Int) {
        putString(header, offset, value.toString(8).padStart(digits, '0'), digits)
        header[offset + digits] = 0
    }

    private fun zip(vararg entries: Entry): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for (entry in entries) {
                zos.putNextEntry(ZipEntry(entry.name))
                if (!entry.name.endsWith("/")) zos.write(entry.content)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
