// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

/** One part of a `multipart/form-data` body. */
internal class Part(val name: String, val filename: String?, val data: ByteArray)

/** A small parser for `multipart/form-data` (RFC 7578), as a browser or htmx sends a file upload. */
internal object Multipart {
    fun parse(contentType: String?, body: ByteArray): List<Part> {
        val boundary = contentType?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("boundary=") }?.substringAfter('=')?.trim('"')
            ?: throw IllegalArgumentException("not a multipart request")
        val first = "--$boundary".toByteArray()
        // inside the body a delimiter always follows a line break; the same bytes inside the data of a file do not end it
        val delimiter = "\r\n--$boundary".toByteArray()
        val parts = ArrayList<Part>()
        if (body.size < first.size || !body.copyOfRange(0, first.size).contentEquals(first)) throw IllegalArgumentException("malformed multipart body")
        var afterDelimiter = first.size
        while (true) {
            var start = afterDelimiter
            // "--" after the boundary ends the body
            if (start + 1 < body.size && body[start] == '-'.code.toByte() && body[start + 1] == '-'.code.toByte()) break
            if (start + 1 < body.size && body[start] == '\r'.code.toByte() && body[start + 1] == '\n'.code.toByte()) start += 2
            val next = indexOf(body, delimiter, start)
            if (next < 0) throw IllegalArgumentException("unterminated multipart body")
            val end = next
            if (end < start) throw IllegalArgumentException("malformed multipart body")
            val headerEnd = indexOf(body, "\r\n\r\n".toByteArray(), start)
            if (headerEnd < 0 || headerEnd > end) throw IllegalArgumentException("malformed multipart part")
            val headers = String(body, start, headerEnd - start, Charsets.UTF_8)
            val disposition = headers.lines().firstOrNull { it.startsWith("Content-Disposition", ignoreCase = true) } ?: throw IllegalArgumentException("part without Content-Disposition")
            fun attribute(key: String) = Regex("""[; ]$key="([^"]*)"""").find(disposition)?.groupValues?.get(1)
            parts += Part(attribute("name") ?: throw IllegalArgumentException("part without a name"), attribute("filename"), body.copyOfRange(headerEnd + 4, end))
            afterDelimiter = next + delimiter.size
        }
        return parts
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
        outer@ for (i in from..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }
}
