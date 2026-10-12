// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.data

import cringle.stdblocks.ANY
import cringle.stdblocks.BOOLEAN
import cringle.stdblocks.DOUBLE
import cringle.stdblocks.Entry
import cringle.stdblocks.INT
import cringle.stdblocks.JsonParseException
import cringle.stdblocks.STRING
import cringle.stdblocks.handlerEntry
import cringle.stdblocks.inPort
import cringle.stdblocks.latest2Entry
import cringle.stdblocks.outPort
import cringle.stdblocks.parseJson
import cringle.stdblocks.toJson
import java.util.Base64

private fun kind(value: Any): String = when (value) {
    is String -> "a text"
    is Boolean -> "a boolean"
    is Number -> "a number"
    is Map<*, *> -> "an object"
    is Iterable<*> -> "a list"
    else -> "something else"
}

/** A cast of the value at `in` (any type): `out` gets it as the wanted type, `error` says what it was when it does not fit. */
private fun cast(name: String, wanted: String, type: cringle.contract.SchemaRef, convert: (Any) -> Any?) =
    handlerEntry(name, listOf(inPort("in", ANY), outPort("out", type), outPort("error", STRING))) { _ ->
        return@handlerEntry { _, v, emit ->
            val converted = convert(v)
            if (converted != null) emit.send("out", converted) else emit.send("error", "expected $wanted, got ${kind(v)}: ${toJson(v)}")
        }
    }

/** A path into a value: `a.b[0].c`, with an optional `$` in front; the segments are names (`String`) and list indexes (`Int`). */
internal fun parsePath(text: String): List<Any> {
    var rest = text.trim().removePrefix("$")
    val segments = ArrayList<Any>()
    var first = true
    while (rest.isNotEmpty()) {
        if (rest.startsWith("[")) {
            val end = rest.indexOf(']')
            val index = if (end > 1) rest.substring(1, end).toIntOrNull() else null
            require(index != null && index >= 0) { "the path '$text' has a bad index at '$rest'" }
            segments += index
            rest = rest.substring(end + 1)
        } else {
            if (rest.startsWith(".")) rest = rest.substring(1) else require(first) { "the path '$text' has no '.' before '$rest'" }
            val end = rest.indexOfAny(charArrayOf('.', '['))
            val name = if (end < 0) rest else rest.substring(0, end)
            require(name.isNotEmpty()) { "the path '$text' has an empty name" }
            segments += name
            rest = if (end < 0) "" else rest.substring(end)
        }
        first = false
    }
    return segments
}

private fun pathOf(config: Map<String, Any?>): List<Any> {
    val text = config["path"] as? String ?: throw IllegalArgumentException("the configuration 'path' is missing")
    return parsePath(text).also { require(it.isNotEmpty()) { "the configuration 'path' must name something" } }
}

private val NOT_FOUND = Any()

private fun getAt(value: Any?, path: List<Any>): Any? {
    var current: Any? = value
    for (segment in path) {
        current = when {
            segment is String && current is Map<*, *> -> if (segment in current) current[segment] else return NOT_FOUND
            segment is Int && current is List<*> -> if (segment < current.size) current[segment] else return NOT_FOUND
            else -> return NOT_FOUND
        }
    }
    return current
}

/** A copy of [document] with [value] at [path]; throws [IllegalArgumentException] with the reason if the path does not fit. */
private fun setAt(document: Any?, path: List<Any>, i: Int, value: Any): Any {
    if (i == path.size) return value
    return when (val segment = path[i]) {
        is String -> {
            require(document == null || document is Map<*, *>) { "'${path.take(i).joinToString(".")}' is not an object" }
            val copy = LinkedHashMap<Any?, Any?>((document as? Map<*, *>).orEmpty())
            copy[segment] = setAt(copy[segment], path, i + 1, value)
            copy
        }
        else -> {
            val index = segment as Int
            require(document == null || document is List<*>) { "'${path.take(i).joinToString(".")}' is not a list" }
            val copy = ArrayList<Any?>((document as? List<*>).orEmpty())
            require(index <= copy.size) { "the index $index is beyond the end of the list (${copy.size} entries)" }
            if (index == copy.size) copy += setAt(null, path, i + 1, value) else copy[index] = setAt(copy[index], path, i + 1, value)
            copy
        }
    }
}

internal val DATA_ENTRIES: List<Entry> = listOf(
    cast("data.as-string", "a text", STRING) { it as? String },
    cast("data.as-number", "a number", DOUBLE) { (it as? Number)?.toDouble() },
    cast("data.as-int", "a whole number", INT) { v ->
        when (v) {
            is Long -> v
            is Int -> v.toLong()
            is Number -> v.toDouble().takeIf { it.isFinite() && it == Math.rint(it) && Math.abs(it) < 9.0e18 }?.toLong()
            else -> null
        }
    },
    cast("data.as-boolean", "a boolean", BOOLEAN) { it as? Boolean },
    handlerEntry("data.json-parse", listOf(inPort("in"), outPort("out", ANY), outPort("error"))) { _ ->
        return@handlerEntry { _, v, emit ->
            try {
                emit.send("out", parseJson(v as String))
            } catch (e: JsonParseException) {
                emit.send("error", e.message ?: "not valid JSON")
            }
        }
    },
    handlerEntry("data.json-stringify", listOf(inPort("in", ANY), outPort("out"))) { _ -> { _, v, emit -> emit.send("out", toJson(v)) } },
    handlerEntry("data.json-get", listOf(inPort("in", ANY), outPort("found", ANY), outPort("missing", ANY)), "PathConfig") { config ->
        val path = pathOf(config)
        return@handlerEntry { _, v, emit ->
            val found = getAt(v, path)
            if (found === NOT_FOUND) emit.send("missing", v) else emit.send("found", found ?: "")
        }
    },
    latest2Entry("data.json-set", ANY, listOf(outPort("out", ANY), outPort("error")), "PathConfig") { config ->
        val path = pathOf(config)
        return@latest2Entry { document, value, emit ->
            try {
                emit.send("out", setAt(document, path, 0, value))
            } catch (e: IllegalArgumentException) {
                emit.send("error", e.message ?: "the path does not fit")
            }
        }
    },
    handlerEntry("data.base64-encode", listOf(inPort("in"), outPort("out"))) { _ ->
        { _, v, emit -> emit.send("out", Base64.getEncoder().encodeToString((v as String).toByteArray(Charsets.UTF_8))) }
    },
    handlerEntry("data.base64-decode", listOf(inPort("in"), outPort("out"), outPort("error"))) { _ ->
        return@handlerEntry { _, v, emit ->
            val decoded = try {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Base64.getDecoder().decode((v as String).trim()))).toString()
            } catch (e: IllegalArgumentException) {
                null
            } catch (e: java.nio.charset.CharacterCodingException) {
                null
            }
            if (decoded != null) emit.send("out", decoded) else emit.send("error", "not Base64 text of UTF-8: $v")
        }
    },
    handlerEntry("data.size", listOf(inPort("in", ANY), outPort("out", INT), outPort("error"))) { _ ->
        return@handlerEntry { _, v, emit ->
            val size = when (v) {
                is String -> v.length
                is Map<*, *> -> v.size
                is Collection<*> -> v.size
                else -> null
            }
            if (size != null) emit.send("out", size.toLong()) else emit.send("error", "expected a text, a list or an object, got ${kind(v)}")
        }
    },
)
