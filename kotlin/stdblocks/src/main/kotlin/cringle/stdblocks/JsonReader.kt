// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

/** Why a text is no JSON document; [position] is the index of the character where it went wrong. */
internal class JsonParseException(message: String, val position: Int) : RuntimeException("$message at position $position")

/**
 * Parses [text] as one JSON document into the values of the type `cringle.std/Any`: `String`, `Long` (an integer literal that fits) or `Double`, `Boolean`,
 * `List` and `LinkedHashMap` (keys in the order of the text). `null`, a duplicate key and text after the document are errors.
 */
internal fun parseJson(text: String): Any = JsonReader(text).document()

private class JsonReader(private val text: String) {
    private var at = 0

    fun document(): Any {
        val value = value(0)
        skipSpace()
        if (at < text.length) fail("unexpected text after the document")
        return value
    }

    private fun fail(message: String): Nothing = throw JsonParseException(message, at)

    private fun skipSpace() {
        while (at < text.length && text[at] in " \t\r\n") at++
    }

    private fun value(depth: Int): Any {
        if (depth > 100) fail("nested deeper than 100 levels")
        skipSpace()
        if (at >= text.length) fail("unexpected end")
        return when (val c = text[at]) {
            '{' -> obj(depth)
            '[' -> list(depth)
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> fail("null is not a valid value")
            else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected character '$c'")
        }
    }

    private fun literal(word: String, value: Boolean): Any {
        if (!text.startsWith(word, at)) fail("unexpected character '${text[at]}'")
        at += word.length
        return value
    }

    private fun obj(depth: Int): Any {
        at++
        val map = LinkedHashMap<String, Any>()
        skipSpace()
        if (at < text.length && text[at] == '}') {
            at++
            return map
        }
        while (true) {
            skipSpace()
            if (at >= text.length || text[at] != '"') fail("a key (a text in quotes) is expected")
            val keyAt = at
            val key = string()
            if (key in map) throw JsonParseException("duplicate key '$key'", keyAt)
            skipSpace()
            if (at >= text.length || text[at] != ':') fail("':' is expected")
            at++
            map[key] = value(depth + 1)
            skipSpace()
            if (at >= text.length) fail("unexpected end")
            when (text[at]) {
                ',' -> at++
                '}' -> {
                    at++
                    return map
                }
                else -> fail("',' or '}' is expected")
            }
        }
    }

    private fun list(depth: Int): Any {
        at++
        val list = ArrayList<Any>()
        skipSpace()
        if (at < text.length && text[at] == ']') {
            at++
            return list
        }
        while (true) {
            list += value(depth + 1)
            skipSpace()
            if (at >= text.length) fail("unexpected end")
            when (text[at]) {
                ',' -> at++
                ']' -> {
                    at++
                    return list
                }
                else -> fail("',' or ']' is expected")
            }
        }
    }

    private fun string(): String {
        at++
        val out = StringBuilder()
        while (true) {
            if (at >= text.length) fail("the text is not closed")
            val c = text[at++]
            when {
                c == '"' -> return out.toString()
                c < ' ' -> {
                    at--
                    fail("a control character in a text")
                }
                c == '\\' -> {
                    if (at >= text.length) fail("the text is not closed")
                    when (val e = text[at++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (at + 4 > text.length) fail("a \\u escape needs four hexadecimal digits")
                            val code = text.substring(at, at + 4).toIntOrNull(16) ?: fail("a \\u escape needs four hexadecimal digits")
                            out.append(code.toChar())
                            at += 4
                        }
                        else -> {
                            at--
                            fail("unknown escape '\\$e'")
                        }
                    }
                }
                else -> out.append(c)
            }
        }
    }

    private val numberPattern = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    private fun number(): Any {
        val match = numberPattern.matchAt(text, at) ?: fail("not a number")
        val literal = match.value
        val integral = match.groups[2] == null && match.groups[3] == null
        val start = at
        at += literal.length
        if (integral) literal.toLongOrNull()?.let { return it }
        val d = literal.toDouble()
        if (!d.isFinite()) {
            at = start
            fail("not a finite number")
        }
        return d
    }
}
