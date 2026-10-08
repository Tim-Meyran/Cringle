// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The two directions between the form of the schema editor and a schema document (`spec/schema.md`). The form model, as Alpine.js holds it, is
 * `{"namespace": "x.y", "types": [{"name": "Foo", "kind": "record", "fields": [{"name": "a", "type": "cringle.std/String", "wrap": ""}], "values": "A, B"}]}`;
 * `wrap` is empty, `list`, `map` or `optional`, `values` is the comma separated list of an enum. Anything that needs more (a list of optional values,
 * for instance) is not expressible in the form and stays as it is in the document only if the form is not used to save it.
 */
internal object SchemaForm {
    /** The schema document for [model]; throws [IllegalArgumentException] for a model that cannot be turned into one (the parser of the schema checks the rest). */
    fun toDocument(model: String): JsonObject {
        val root = try {
            Json.parseToJsonElement(model).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("the form data is not valid")
        }
        val types = LinkedHashMap<String, JsonElement>()
        for (t in root["types"]?.jsonArray.orEmpty()) {
            val type = t.jsonObject
            val name = type["name"]?.jsonPrimitive?.content.orEmpty().trim()
            require(name !in types) { "the type '$name' is defined twice" }
            types[name] = if (type["kind"]?.jsonPrimitive?.content == "enum") {
                val values = type["values"]?.jsonPrimitive?.content.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                JsonObject(mapOf("enum" to JsonArray(values.map { JsonPrimitive(it) })))
            } else {
                val fields = LinkedHashMap<String, JsonElement>()
                for (f in type["fields"]?.jsonArray.orEmpty()) {
                    val field = f.jsonObject
                    val fieldName = field["name"]?.jsonPrimitive?.content.orEmpty().trim()
                    require(fieldName !in fields) { "the field '$fieldName' of '$name' is defined twice" }
                    fields[fieldName] = wrap(field["wrap"]?.jsonPrimitive?.content.orEmpty(), JsonPrimitive(field["type"]?.jsonPrimitive?.content.orEmpty().trim()))
                }
                JsonObject(mapOf("record" to JsonObject(fields)))
            }
        }
        return JsonObject(mapOf("namespace" to JsonPrimitive(root["namespace"]?.jsonPrimitive?.content.orEmpty().trim()), "types" to JsonObject(types)))
    }

    private fun wrap(kind: String, inner: JsonElement): JsonElement = when (kind) {
        "list", "map", "optional" -> JsonObject(mapOf(kind to inner))
        else -> inner
    }

    /** The form model for the schema document [text]; an empty model if the text is not a document the form understands. */
    fun toModel(text: JsonElement): JsonObject {
        val empty = buildJsonObject {
            put("namespace", "")
            put("types", JsonArray(emptyList()))
        }
        val doc = text as? JsonObject ?: return empty
        return try {
            buildJsonObject {
                put("namespace", doc["namespace"]?.jsonPrimitive?.content.orEmpty())
                put(
                    "types",
                    buildJsonArray {
                        for ((name, def) in doc["types"]?.jsonObject.orEmpty()) {
                            val d = def.jsonObject
                            add(
                                buildJsonObject {
                                    put("name", name)
                                    val enumValues = d["enum"]
                                    put("kind", if (enumValues != null) "enum" else "record")
                                    put("values", enumValues?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content }.orEmpty())
                                    put(
                                        "fields",
                                        buildJsonArray {
                                            for ((fieldName, expr) in d["record"]?.jsonObject.orEmpty()) {
                                                val (wrapKind, base) = unwrap(expr)
                                                add(buildJsonObject { put("name", fieldName); put("type", base); put("wrap", wrapKind) })
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }
        } catch (e: Exception) {
            empty
        }
    }

    private fun unwrap(expr: JsonElement): Pair<String, String> {
        if (expr is JsonPrimitive) return "" to expr.content
        val (kind, inner) = expr.jsonObject.entries.single()
        return kind to inner.jsonPrimitive.content
    }
}
