// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import cringle.contract.BlockDefinition
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import cringle.packaging.JsonReading.enumValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** JSON form of [BlockDefinition] as used in plugin manifests (see `spec/package-format.md`, section 4). */
public object BlockDefinitionJson {
    private val nameKeys = setOf("name", "schemas", "ports", "requiredDrivers", "configSchema")
    private val portKeys = setOf("name", "direction", "tetherTypes", "schema", "varArg")

    /** Parses a block definition from text. */
    public fun parse(text: String): BlockDefinition =
        fromJson(JsonReading.parseObject(text, "block definition"), "$")

    /** Writes [definition] as JSON text (pretty printed, keys in the documented order). */
    public fun encode(definition: BlockDefinition): String = Json { prettyPrint = true }.encodeToString(
        JsonElement.serializer(),
        toJson(definition),
    )

    internal fun fromJson(o: JsonObject, path: String): BlockDefinition {
        JsonReading.keys(o, path, nameKeys)
        val schemas = JsonReading.stringList(o, "schemas", path).mapIndexed { i, s -> ref(s, "$path.schemas[$i]") }
        val ports = JsonReading.objectList(o, "ports", path).mapIndexed { i, p -> port(p, "$path.ports[$i]") }
        val configSchema = JsonReading.optString(o, "configSchema", path)?.let { ref(it, "$path.configSchema") }
        return construct(path) {
            BlockDefinition(
                JsonReading.string(o, "name", path),
                schemas,
                ports,
                JsonReading.stringList(o, "requiredDrivers", path),
                configSchema,
            )
        }
    }

    private fun port(o: JsonObject, path: String): PortDefinition {
        JsonReading.keys(o, path, portKeys)
        val varArg = o["varArg"]?.let {
            (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.content?.toBooleanStrictOrNull()
                ?: throw PackageFormatException("$path.varArg", "must be a boolean")
        } ?: false
        val types = JsonReading.stringList(o, "tetherTypes", path).mapIndexed { i, s ->
            enumValue<TetherType>(s, "$path.tetherTypes[$i]")
        }
        return construct(path) {
            PortDefinition(
                ManifestJson.checkedIdentifier(JsonReading.string(o, "name", path), "$path.name"),
                enumValue<PortDirection>(JsonReading.string(o, "direction", path), "$path.direction"),
                types.toSet(),
                ref(JsonReading.string(o, "schema", path), "$path.schema"),
                varArg,
            )
        }
    }

    private fun ref(text: String, path: String): SchemaRef = construct(path) { SchemaRef.parse(text) }

    private inline fun <T> construct(path: String, build: () -> T): T = try {
        build()
    } catch (e: IllegalArgumentException) {
        throw PackageFormatException(path, e.message ?: "invalid value")
    }

    internal fun toJson(d: BlockDefinition): JsonObject = buildJsonObject {
        put("name", d.name)
        put("schemas", buildJsonArray { d.schemas.forEach { add(JsonPrimitive(it.toString())) } })
        put(
            "ports",
            JsonArray(
                d.ports.map { p ->
                    buildJsonObject {
                        put("name", p.name)
                        put("direction", p.direction.name)
                        put("tetherTypes", buildJsonArray { p.tetherTypes.forEach { add(JsonPrimitive(it.name)) } })
                        put("schema", p.schema.toString())
                        if (p.varArg) put("varArg", true)
                    }
                },
            ),
        )
        put("requiredDrivers", buildJsonArray { d.requiredDrivers.forEach { add(JsonPrimitive(it)) } })
        d.configSchema?.let { put("configSchema", it.toString()) }
    }
}
