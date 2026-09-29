// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts a tether value (maps, lists, strings, numbers, booleans, `null`, or a [JsonElement]) to JSON so that it can
 * be checked against a schema. Throws [IllegalArgumentException] for anything else.
 */
internal fun toJson(value: Any?, path: String = "$"): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(
        value.entries.associate { (k, v) ->
            require(k is String) { "$path: map keys must be strings, found ${k?.javaClass?.simpleName}" }
            k to toJson(v, "$path.$k")
        },
    )
    is Iterable<*> -> JsonArray(value.mapIndexed { i, v -> toJson(v, "$path[$i]") })
    else -> throw IllegalArgumentException("$path: unsupported value type ${value.javaClass.name}")
}
