// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import cringle.schema.JsonDuplicateKeys
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Strict JSON access with path tracking; every problem becomes a [PackageFormatException]. */
internal object JsonReading {
    fun parseObject(text: String, file: String): JsonObject {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw PackageFormatException(file, "malformed JSON: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        JsonDuplicateKeys.find(text)?.let { (path, key) -> throw PackageFormatException("$file $path", "duplicate key '$key'") }
        return root as? JsonObject ?: throw PackageFormatException(file, "must be a JSON object")
    }

    fun obj(element: JsonElement, path: String): JsonObject =
        element as? JsonObject ?: throw PackageFormatException(path, "must be an object")

    fun keys(o: JsonObject, path: String, allowed: Set<String>) {
        for (key in o.keys) if (key !in allowed) throw PackageFormatException("$path.$key", "unknown key '$key'")
    }

    fun string(o: JsonObject, key: String, path: String): String = optString(o, key, path)
        ?: throw PackageFormatException(path, "missing key '$key'")

    fun optString(o: JsonObject, key: String, path: String): String? {
        val value = o[key] ?: return null
        val p = value as? JsonPrimitive
        if (p == null || !p.isString) throw PackageFormatException("$path.$key", "must be a string")
        return p.content
    }

    fun optInt(o: JsonObject, key: String, path: String): Int? {
        val value = o[key] ?: return null
        val p = value as? JsonPrimitive
        val n = if (p == null || p.isString || value is JsonNull) null else p.content.toIntOrNull()
        return n ?: throw PackageFormatException("$path.$key", "must be an integer")
    }

    fun stringList(o: JsonObject, key: String, path: String): List<String> {
        val value = o[key] ?: return emptyList()
        val array = value as? JsonArray ?: throw PackageFormatException("$path.$key", "must be an array of strings")
        return array.mapIndexed { i, e ->
            val p = e as? JsonPrimitive
            if (p == null || !p.isString) throw PackageFormatException("$path.$key[$i]", "must be a string")
            p.content
        }
    }

    fun stringMap(o: JsonObject, key: String, path: String): Map<String, String> {
        val value = o[key] ?: return emptyMap()
        val m = value as? JsonObject ?: throw PackageFormatException("$path.$key", "must be an object of strings")
        return m.mapValues { (k, e) ->
            val p = e as? JsonPrimitive
            if (p == null || !p.isString) throw PackageFormatException("$path.$key.$k", "must be a string")
            p.content
        }
    }

    fun objectList(o: JsonObject, key: String, path: String): List<JsonObject> {
        val value = o[key] ?: return emptyList()
        val array = value as? JsonArray ?: throw PackageFormatException("$path.$key", "must be an array of objects")
        return array.mapIndexed { i, e -> obj(e, "$path.$key[$i]") }
    }

    inline fun <reified E : Enum<E>> enumValue(text: String, path: String): E =
        enumValues<E>().firstOrNull { it.name == text }
            ?: throw PackageFormatException(path, "unknown value '$text': expected one of ${enumValues<E>().joinToString { it.name }}")
}
