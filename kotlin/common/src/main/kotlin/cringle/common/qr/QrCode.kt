// SPDX-License-Identifier: Apache-2.0

package cringle.common.qr

/**
 * A QR code (ISO/IEC 18004) in byte mode, versions 1 to 40, the four error correction levels. The WebUI draws it as an SVG ([toSvg]) so that a token
 * or an invite link can be scanned from the screen without any script and without the text leaving the page, and the CLI prints it in the terminal ([toText]) (#301).
 */
public class QrCode private constructor(val version: Int, val ecc: Ecc, private val modules: Array<BooleanArray>) {
    /** The error correction level; [formatBits] is its code in the format information. */
    enum class Ecc(val formatBits: Int, val index: Int) { L(1, 0), M(0, 1), Q(3, 2), H(2, 3) }

    /** The side of the code in modules, without the quiet zone. */
    val size: Int get() = modules.size

    /** True if the module in column [x] and row [y] is dark. */
    fun dark(x: Int, y: Int): Boolean = modules[y][x]

    /** The mask (0 to 7) that was applied, read back from the format information. */
    val mask: Int = readMask(modules)

    /**
     * The code as an `<svg>` element: black modules on a white ground (scanners need dark on light whatever the theme is), a quiet zone of [border]
     * modules, [label] as the accessible name. It has no script and no style, so it works under the content security policy.
     */
    fun toSvg(label: String, border: Int = 4): String {
        val total = size + 2 * border
        val path = StringBuilder()
        for (y in 0 until size) {
            var x = 0
            while (x < size) {
                if (!modules[y][x]) {
                    x++
                    continue
                }
                var end = x
                while (end < size && modules[y][end]) end++
                path.append('M').append(x + border).append(' ').append(y + border).append('h').append(end - x).append("v1h-").append(end - x).append('z')
                x = end
            }
        }
        return "<svg class=\"qr\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $total $total\" role=\"img\" aria-label=\"${escAttr(label)}\" shape-rendering=\"crispEdges\">" +
            "<rect width=\"$total\" height=\"$total\" fill=\"#fff\"/><path d=\"$path\" fill=\"#000\"/></svg>"
    }

    /**
     * The code for a terminal: two rows of modules per line with the half blocks `▀ ▄ █` and a space, a quiet zone of [border] modules. Dark modules
     * are drawn light and the ground dark (inverted), as a terminal usually has a dark ground; set [inverted] to false for a light terminal.
     */
    fun toText(border: Int = 2, inverted: Boolean = true): String {
        fun on(x: Int, y: Int): Boolean = (x in 0 until size && y in 0 until size && modules[y][x]) != inverted
        val total = size + 2 * border
        val out = StringBuilder()
        var y = -border
        while (y < total - border) {
            for (x in -border until total - border) {
                val top = on(x, y)
                val bottom = on(x, y + 1)
                out.append(if (top && bottom) '█' else if (top) '▀' else if (bottom) '▄' else ' ')
            }
            out.append('\n')
            y += 2
        }
        return out.toString()
    }

    private fun escAttr(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        /** The largest text a code can hold is 2953 bytes (version 40, level L). */
        fun encode(text: String, ecc: Ecc = Ecc.M): QrCode {
            val data = text.toByteArray(Charsets.UTF_8)
            var version = 1
            while (true) {
                val capacityBits = dataCodewords(version, ecc) * 8
                val needed = 4 + lengthBits(version) + 8 * data.size
                if (needed <= capacityBits) break
                require(version < 40) { "the text of ${data.size} bytes is too long for a QR code" }
                version++
            }
            val bits = BitBuffer()
            bits.append(0b0100, 4)
            bits.append(data.size, lengthBits(version))
            for (b in data) bits.append(b.toInt() and 0xFF, 8)
            val capacityBits = dataCodewords(version, ecc) * 8
            bits.append(0, minOf(4, capacityBits - bits.length))
            bits.append(0, (8 - bits.length % 8) % 8)
            var pad = 0xEC
            while (bits.length < capacityBits) {
                bits.append(pad, 8)
                pad = pad xor (0xEC xor 0x11)
            }
            val codewords = interleave(bits.bytes(), version, ecc)
            return build(version, ecc, codewords)
        }

        // --- the layout, shared with the tests ---

        /** The number of bits of the character count of the byte mode. */
        fun lengthBits(version: Int): Int = if (version <= 9) 8 else 16

        /** The number of modules that carry data and error correction, without the function patterns. */
        fun rawDataModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        /** The number of data codewords of a code. */
        fun dataCodewords(version: Int, ecc: Ecc): Int = rawDataModules(version) / 8 - ECC_PER_BLOCK[ecc.index][version] * BLOCKS[ecc.index][version]

        /** The number of bytes of text a code holds in byte mode. */
        fun byteCapacity(version: Int, ecc: Ecc): Int = (dataCodewords(version, ecc) * 8 - 4 - lengthBits(version)) / 8

        /** The sizes of the blocks (data plus error correction codewords) of a code, shorter blocks first. */
        fun blockLengths(version: Int, ecc: Ecc): List<Int> {
            val blocks = BLOCKS[ecc.index][version]
            val raw = rawDataModules(version) / 8
            val short = blocks - raw % blocks
            return List(blocks) { if (it < short) raw / blocks else raw / blocks + 1 }
        }

        /** The error correction codewords per block. */
        fun eccPerBlock(version: Int, ecc: Ecc): Int = ECC_PER_BLOCK[ecc.index][version]

        /** The multiplication of the field GF(2^8) of the code (polynomial 0x11D). */
        fun multiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        /** True for the modules that are not data: finder, timing and alignment patterns, the format and version information. */
        fun functionModules(version: Int): Array<BooleanArray> {
            val size = version * 4 + 17
            val f = Array(size) { BooleanArray(size) }
            for (i in 0 until size) {
                f[6][i] = true
                f[i][6] = true
            }
            fun fill(x0: Int, y0: Int, x1: Int, y1: Int) {
                for (y in maxOf(y0, 0)..minOf(y1, size - 1)) for (x in maxOf(x0, 0)..minOf(x1, size - 1)) f[y][x] = true
            }
            fill(0, 0, 8, 8)
            fill(size - 8, 0, size - 1, 8)
            fill(0, size - 8, 8, size - 1)
            val positions = alignmentPositions(version)
            for (a in positions.indices) for (b in positions.indices) {
                if ((a == 0 && b == 0) || (a == 0 && b == positions.size - 1) || (a == positions.size - 1 && b == 0)) continue
                fill(positions[a] - 2, positions[b] - 2, positions[a] + 2, positions[b] + 2)
            }
            if (version >= 7) {
                fill(size - 11, 0, size - 9, 5)
                fill(0, size - 11, 5, size - 9)
            }
            return f
        }

        private fun alignmentPositions(version: Int): IntArray {
            if (version == 1) return IntArray(0)
            val count = version / 7 + 2
            val step = if (version == 32) 26 else (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
            val result = IntArray(count)
            result[0] = 6
            var pos = version * 4 + 17 - 7
            for (i in count - 1 downTo 1) {
                result[i] = pos
                pos -= step
            }
            return result
        }

        // --- encoding ---

        private class BitBuffer {
            private val bytes = ArrayList<Int>()
            var length = 0
                private set

            fun append(value: Int, count: Int) {
                for (i in count - 1 downTo 0) {
                    if (length % 8 == 0) bytes += 0
                    if ((value ushr i) and 1 == 1) bytes[length / 8] = bytes[length / 8] or (0x80 ushr (length % 8))
                    length++
                }
            }

            fun bytes(): IntArray = bytes.toIntArray()
        }

        private fun generator(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in 0 until degree) {
                    result[j] = multiply(result[j], root)
                    if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
                }
                root = multiply(root, 0x02)
            }
            return result
        }

        private fun remainder(data: IntArray, from: Int, to: Int, divisor: IntArray): IntArray {
            val result = IntArray(divisor.size)
            for (k in from until to) {
                val factor = data[k] xor result[0]
                for (i in 0 until result.size - 1) result[i] = result[i + 1]
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor multiply(divisor[i], factor)
            }
            return result
        }

        private fun interleave(data: IntArray, version: Int, ecc: Ecc): IntArray {
            val eccLen = ECC_PER_BLOCK[ecc.index][version]
            val lengths = blockLengths(version, ecc)
            val divisor = generator(eccLen)
            val blocks = ArrayList<IntArray>()
            var k = 0
            for (length in lengths) {
                val dataLen = length - eccLen
                val block = IntArray(length)
                for (i in 0 until dataLen) block[i] = data[k + i]
                val e = remainder(data, k, k + dataLen, divisor)
                for (i in 0 until eccLen) block[dataLen + i] = e[i]
                k += dataLen
                blocks += block
            }
            val result = ArrayList<Int>()
            // first the data codewords of all blocks column by column, then the error correction codewords
            val longestData = lengths.max() - eccLen
            for (i in 0 until longestData) for (block in blocks) if (i < block.size - eccLen) result += block[i]
            for (i in 0 until eccLen) for (block in blocks) result += block[block.size - eccLen + i]
            return result.toIntArray()
        }

        private fun build(version: Int, ecc: Ecc, codewords: IntArray): QrCode {
            val size = version * 4 + 17
            val function = functionModules(version)
            val base = Array(size) { BooleanArray(size) }
            drawPatterns(base, version)
            // the data in the zig-zag order, from the lower right corner
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0..1) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!function[y][x] && i < codewords.size * 8) {
                            base[y][x] = ((codewords[i ushr 3] ushr (7 - (i and 7))) and 1) == 1
                            i++
                        }
                    }
                }
                right -= 2
            }
            var best: Array<BooleanArray>? = null
            var bestPenalty = Int.MAX_VALUE
            for (m in 0..7) {
                val candidate = Array(size) { base[it].copyOf() }
                for (y in 0 until size) for (x in 0 until size) if (!function[y][x] && maskBit(m, x, y)) candidate[y][x] = !candidate[y][x]
                drawFormat(candidate, ecc, m)
                val penalty = penalty(candidate)
                if (penalty < bestPenalty) {
                    bestPenalty = penalty
                    best = candidate
                }
            }
            return QrCode(version, ecc, best!!)
        }

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

        private fun drawPatterns(m: Array<BooleanArray>, version: Int) {
            val size = m.size
            for (i in 0 until size) {
                m[6][i] = i % 2 == 0
                m[i][6] = i % 2 == 0
            }
            fun finder(cx: Int, cy: Int) {
                for (dy in -4..4) for (dx in -4..4) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until size && y in 0 until size) {
                        val dist = maxOf(Math.abs(dx), Math.abs(dy))
                        m[y][x] = dist != 2 && dist != 4
                    }
                }
            }
            finder(3, 3)
            finder(size - 4, 3)
            finder(3, size - 4)
            val positions = alignmentPositions(version)
            for (a in positions.indices) for (b in positions.indices) {
                if ((a == 0 && b == 0) || (a == 0 && b == positions.size - 1) || (a == positions.size - 1 && b == 0)) continue
                for (dy in -2..2) for (dx in -2..2) m[positions[b] + dy][positions[a] + dx] = maxOf(Math.abs(dx), Math.abs(dy)) != 1
            }
            if (version >= 7) {
                var rem = version
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = (version shl 12) or rem
                for (i in 0 until 18) {
                    val bit = ((bits ushr i) and 1) == 1
                    val a = size - 11 + i % 3
                    val b = i / 3
                    m[b][a] = bit
                    m[a][b] = bit
                }
            }
        }

        private fun formatBits(ecc: Ecc, mask: Int): Int {
            val data = (ecc.formatBits shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            return ((data shl 10) or rem) xor 0x5412
        }

        private fun drawFormat(m: Array<BooleanArray>, ecc: Ecc, mask: Int) {
            val size = m.size
            val bits = formatBits(ecc, mask)
            fun bit(i: Int) = ((bits ushr i) and 1) == 1
            for (i in 0..5) m[i][8] = bit(i)
            m[7][8] = bit(6)
            m[8][8] = bit(7)
            m[8][7] = bit(8)
            for (i in 9..14) m[8][14 - i] = bit(i)
            for (i in 0..7) m[8][size - 1 - i] = bit(i)
            for (i in 8..14) m[size - 15 + i][8] = bit(i)
            m[size - 8][8] = true
        }

        /** The 15 bits of the first copy of the format information, as written. */
        fun formatInfo(m: Array<BooleanArray>): Int {
            var bits = 0
            fun set(i: Int, v: Boolean) { if (v) bits = bits or (1 shl i) }
            for (i in 0..5) set(i, m[i][8])
            set(6, m[7][8])
            set(7, m[8][8])
            set(8, m[8][7])
            for (i in 9..14) set(i, m[8][14 - i])
            return bits
        }

        private fun readMask(m: Array<BooleanArray>): Int = ((formatInfo(m) xor 0x5412) shr 10) and 7

        private fun penalty(m: Array<BooleanArray>): Int {
            val size = m.size
            var result = 0
            // runs of five or more modules of one color, in rows and columns
            for (pass in 0..1) {
                for (a in 0 until size) {
                    var run = 1
                    for (b in 1 until size) {
                        val same = if (pass == 0) m[a][b] == m[a][b - 1] else m[b][a] == m[b - 1][a]
                        if (same) {
                            run++
                            if (run == 5) result += 3 else if (run > 5) result++
                        } else {
                            run = 1
                        }
                    }
                }
            }
            // blocks of 2 x 2
            for (y in 0 until size - 1) for (x in 0 until size - 1) if (m[y][x] == m[y][x + 1] && m[y][x] == m[y + 1][x] && m[y][x] == m[y + 1][x + 1]) result += 3
            // a pattern like the finder (dark, light, three dark, light, dark) with four light modules on one side
            val a = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
            val b = a.reversedArray()
            for (pass in 0..1) for (p in 0 until size) for (q in 0..size - 11) {
                var matchA = true
                var matchB = true
                for (k in 0 until 11) {
                    val v = if (pass == 0) m[p][q + k] else m[q + k][p]
                    if (v != a[k]) matchA = false
                    if (v != b[k]) matchB = false
                }
                if (matchA) result += 40
                if (matchB) result += 40
            }
            // the share of dark modules
            var dark = 0
            for (row in m) for (v in row) if (v) dark++
            val total = size * size
            result += ((Math.abs(dark * 20 - total * 10) + total - 1) / total - 1) * 10
            return result
        }

        private val ECC_PER_BLOCK = arrayOf(
            intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
            intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        )

        private val BLOCKS = arrayOf(
            intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
            intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
            intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
            intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
        )
    }
}
