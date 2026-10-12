// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

/** The JSON text of a value of the type `cringle.std/Any`: a string, a number, a boolean, a list or a map (keys as text, in the order of the map). */
internal fun toJson(value: Any?): String = StringBuilder().also { writeJson(value, it) }.toString()

private fun writeJson(value: Any?, out: StringBuilder) {
    when (value) {
        null -> out.append("null")
        is String -> writeString(value, out)
        is Boolean -> out.append(value)
        is Double -> out.append(if (value.isFinite() && value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString())
        is Number -> out.append(value.toString())
        is Map<*, *> -> {
            out.append('{')
            var first = true
            for ((k, v) in value) {
                if (!first) out.append(',')
                first = false
                writeString(k.toString(), out)
                out.append(':')
                writeJson(v, out)
            }
            out.append('}')
        }
        is Iterable<*> -> {
            out.append('[')
            var first = true
            for (v in value) {
                if (!first) out.append(',')
                first = false
                writeJson(v, out)
            }
            out.append(']')
        }
        else -> writeString(value.toString(), out)
    }
}

private fun writeString(text: String, out: StringBuilder) {
    out.append('"')
    for (c in text) {
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' -> out.append("\\u%04x".format(c.code))
            else -> out.append(c)
        }
    }
    out.append('"')
}
