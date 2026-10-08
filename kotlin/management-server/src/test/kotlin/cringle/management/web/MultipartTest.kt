// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MultipartTest {
    private val type = "multipart/form-data; boundary=XyZ"

    @Test
    fun readsFieldsAndBinaryFilesWithLineBreaksInside() {
        val data = byteArrayOf(0, 13, 10, 13, 10, 45, 45, 88, 121, -1)
        val body = "--XyZ\r\nContent-Disposition: form-data; name=\"note\"\r\n\r\nhello\r\n--XyZ\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n\r\n".toByteArray() +
            data + "\r\n--XyZ--\r\n".toByteArray()
        val parts = Multipart.parse(type, body)
        assertEquals(listOf("note", "file"), parts.map { it.name })
        assertEquals("hello", String(parts[0].data))
        assertEquals("a.bin", parts[1].filename)
        assertArrayEquals(data, parts[1].data)
    }

    @Test
    fun refusesWhatIsNotMultipart() {
        assertThrows<IllegalArgumentException> { Multipart.parse("text/plain", ByteArray(0)) }
        assertThrows<IllegalArgumentException> { Multipart.parse(type, "--XyZ\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nno end".toByteArray()) }
    }
}
