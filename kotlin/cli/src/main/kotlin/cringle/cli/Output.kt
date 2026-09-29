// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a command produces; rendered as text or, with `--json`, as JSON. Values are strings, numbers, booleans, lists and maps. */
internal sealed interface Output {
    /** A list of things, shown as a table; [empty] is shown when there are none. */
    class Rows(val rows: List<Map<String, Any?>>, val empty: String = "nothing found") : Output

    /** One thing, shown as `key: value` lines. */
    class Detail(val fields: Map<String, Any?>) : Output

    /** Log-like output: one line of text per entry, with the same entries as JSON. */
    class Lines(val lines: List<String>, val entries: List<Map<String, Any?>>) : Output

    /** A confirmation. */
    class Message(val text: String, val json: Map<String, Any?> = mapOf("ok" to true)) : Output
}

internal object Render {
    private val pretty = Json { prettyPrint = true }

    fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toJson(it.value) })
        is Iterable<*> -> JsonArray(v.map { toJson(it) })
        else -> JsonPrimitive(v.toString())
    }

    fun json(output: Output): String {
        val element: JsonElement = when (output) {
            is Output.Rows -> toJson(output.rows)
            is Output.Detail -> toJson(output.fields)
            is Output.Lines -> toJson(output.entries)
            is Output.Message -> toJson(output.json)
        }
        return pretty.encodeToString(JsonElement.serializer(), element)
    }

    fun text(v: Any?): String = when (v) {
        null -> ""
        is Map<*, *> -> v.entries.joinToString(", ") { "${it.key}=${text(it.value)}" }
        is Iterable<*> -> v.joinToString(", ") { text(it) }
        else -> v.toString()
    }

    fun human(output: Output): String = when (output) {
        is Output.Message -> output.text
        is Output.Lines -> output.lines.joinToString("\n").ifEmpty { "no log entries" }
        is Output.Detail -> {
            val width = output.fields.keys.maxOfOrNull { it.length } ?: 0
            output.fields.entries.joinToString("\n") { (k, v) ->
                if (v is Iterable<*> && v.any { it is Map<*, *> }) {
                    "${k.padEnd(width)}:" + v.joinToString("") { "\n${" ".repeat(width + 2)}${text(it)}" }
                } else {
                    "${k.padEnd(width)}: ${text(v)}"
                }
            }
        }
        is Output.Rows -> table(output)
    }

    private fun table(o: Output.Rows): String {
        if (o.rows.isEmpty()) return o.empty
        val headers = o.rows.flatMap { it.keys }.distinct()
        val cells = o.rows.map { row -> headers.map { text(row[it]) } }
        val widths = headers.indices.map { c -> maxOf(headers[c].length, cells.maxOf { it[c].length }) }
        fun line(values: List<String>) = values.indices.joinToString("  ") { values[it].padEnd(widths[it]) }.trimEnd()
        return (listOf(line(headers.map { it.uppercase() })) + cells.map { line(it) }).joinToString("\n")
    }
}
