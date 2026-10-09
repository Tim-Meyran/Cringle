// SPDX-License-Identifier: Apache-2.0

package cringle.common

import cringle.common.qr.QrCode
import cringle.common.qr.QrCode.Ecc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The encoder is checked by a decoder written independently in this test: it reads the modules back, unmasks them, checks the error correction and the text. */
class QrCodeTest {
    private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (x / 3 + y / 2) % 2 == 0
        5 -> x * y % 2 + x * y % 3 == 0
        6 -> (x * y % 2 + x * y % 3) % 2 == 0
        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
    }

    private fun decode(code: QrCode): String {
        val function = QrCode.functionModules(code.version)
        val size = code.size
        val bits = ArrayList<Boolean>()
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) for (j in 0..1) {
                val x = right - j
                val y = if (((right + 1) and 2) == 0) size - 1 - vert else vert
                if (!function[y][x]) bits += code.dark(x, y) != maskBit(code.mask, x, y)
            }
            right -= 2
        }
        val all = IntArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, b -> (acc shl 1) or (if (bits[i * 8 + b]) 1 else 0) } }
        val lengths = QrCode.blockLengths(code.version, code.ecc)
        val eccLen = QrCode.eccPerBlock(code.version, code.ecc)
        // undo the interleaving
        val blocks = lengths.map { IntArray(it) }
        var k = 0
        for (i in 0 until lengths.max() - eccLen) for (b in blocks.indices) if (i < lengths[b] - eccLen) blocks[b][i] = all[k++]
        for (i in 0 until eccLen) for (b in blocks.indices) blocks[b][lengths[b] - eccLen + i] = all[k++]
        // a codeword sequence with its error correction is a multiple of the generator: every syndrome at alpha^0..alpha^(eccLen-1) is zero
        for (block in blocks) {
            var alpha = 1
            repeat(eccLen) {
                var sum = 0
                for (c in block) sum = QrCode.multiply(sum, alpha) xor c
                assertEquals(0, sum, "syndrome of a block")
                alpha = QrCode.multiply(alpha, 2)
            }
        }
        val data = blocks.flatMap { b -> b.take(b.size - eccLen) }
        val stream = data.joinToString("") { Integer.toBinaryString(it or 0x100).substring(1) }
        assertEquals("0100", stream.substring(0, 4), "byte mode")
        val lenBits = QrCode.lengthBits(code.version)
        val length = stream.substring(4, 4 + lenBits).toInt(2)
        val bytes = ByteArray(length) { stream.substring(4 + lenBits + it * 8, 12 + lenBits + it * 8).toInt(2).toByte() }
        return String(bytes, Charsets.UTF_8)
    }

    private fun bch(data: Int): Int {
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        return ((data shl 10) or rem) xor 0x5412
    }

    @Test
    fun `text round trips in every level and over the versions`() {
        for (ecc in Ecc.entries) {
            for (length in listOf(1, 7, 17, 40, 100, 300, 700, 1200)) {
                if (length > QrCode.byteCapacity(40, ecc)) continue
                val text = (0 until length).joinToString("") { ('a' + (it * 7 + length) % 26).toString() }
                val code = QrCode.encode(text, ecc)
                assertEquals(text, decode(code), "level $ecc, $length bytes, version ${code.version}")
            }
        }
    }

    @Test
    fun `utf-8 and a token url round trip`() {
        val url = "https://cringle.example:8443/login#token=crt_" + "Ab3-_".repeat(9)
        assertEquals(url, decode(QrCode.encode(url)))
        assertEquals("Grüße ☃ 日本", decode(QrCode.encode("Grüße ☃ 日本")))
    }

    @Test
    fun `version follows the capacity`() {
        assertEquals(1, QrCode.encode("a".repeat(14), Ecc.M).version)
        assertEquals(2, QrCode.encode("a".repeat(15), Ecc.M).version)
        assertEquals(1, QrCode.encode("a".repeat(17), Ecc.L).version)
        assertEquals(2, QrCode.encode("a".repeat(18), Ecc.L).version)
        assertEquals(40, QrCode.encode("a".repeat(2953), Ecc.L).version)
        assertThrows<IllegalArgumentException> { QrCode.encode("a".repeat(2954), Ecc.L) }
    }

    @Test
    fun `format information is a valid BCH word of level and mask`() {
        for (ecc in Ecc.entries) {
            val code = QrCode.encode("format $ecc", ecc)
            assertEquals(bch((ecc.formatBits shl 3) or code.mask), QrCode.formatInfo(codeModules(code)))
        }
    }

    private fun codeModules(code: QrCode) = Array(code.size) { y -> BooleanArray(code.size) { x -> code.dark(x, y) } }

    @Test
    fun `finder patterns and timing are in place`() {
        val code = QrCode.encode("finder", Ecc.Q)
        for ((cx, cy) in listOf(0 to 0, code.size - 7 to 0, 0 to code.size - 7)) {
            for (d in 0..6) {
                assertTrue(code.dark(cx + d, cy) && code.dark(cx + d, cy + 6) && code.dark(cx, cy + d) && code.dark(cx + 6, cy + d))
            }
            assertTrue(code.dark(cx + 3, cy + 3))
            assertTrue(!code.dark(cx + 1, cy + 1) && !code.dark(cx + 5, cy + 5))
        }
        for (i in 8 until code.size - 8) assertEquals(i % 2 == 0, code.dark(i, 6))
    }

    @Test
    fun `svg has no script and no style and names itself`() {
        val svg = QrCode.encode("https://x/").toSvg("Login \"link\" <a>")
        assertTrue(svg.startsWith("<svg"))
        assertTrue("aria-label=\"Login &quot;link&quot; &lt;a&gt;\"" in svg)
        assertTrue("<script" !in svg && "style=" !in svg && "<style" !in svg)
        assertTrue("viewBox=\"0 0 ${21 + 8} ${21 + 8}\"" in svg)
    }

    @Test
    fun `text rendering has two rows per line`() {
        val code = QrCode.encode("terminal")
        val lines = code.toText(border = 2).trimEnd('\n').split('\n')
        assertEquals((code.size + 4 + 1) / 2, lines.size)
        assertTrue(lines.all { it.length == code.size + 4 })
        // inverted: the quiet zone is drawn as full blocks
        assertTrue(lines.first().all { it == '█' })
        assertTrue(code.toText(border = 2, inverted = false).lines().first().all { it == ' ' })
    }
}
