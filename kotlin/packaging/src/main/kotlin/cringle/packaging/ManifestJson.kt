// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import cringle.contract.BlockDefinition
import cringle.contract.IsolationLevel
import cringle.contract.TetherType
import cringle.packaging.JsonReading.enumValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Naming and versioning rules shared by all package files. */
public object PackageNames {
    /** Package name, block name and blueprint name grammar. */
    public val name: Regex = Regex("[a-z][a-z0-9]*([.-][a-z0-9]+)*")

    /** Semantic version `MAJOR.MINOR.PATCH` with optional `-prerelease`. */
    public val version: Regex = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?")

    /** The manifest format number this implementation reads and writes. */
    public const val FORMAT: Int = 1
}

/** JSON (de)serialization of manifests and blueprints (`spec/package-format.md`, sections 3 to 6). */
public object ManifestJson {
    private val pretty = Json { prettyPrint = true }
    private val projectKeys = setOf("format", "kind", "name", "version", "dependencies", "blueprints", "schemas", "fabrics")
    private val pluginKeys = setOf(
        "format", "kind", "name", "version", "dependencies", "providers", "drivers", "blocks", "libs", "schemas", "processors",
    )
    private val fabricKeys = setOf("blueprint", "instances", "roles", "labels")
    private val blueprintKeys = setOf("name", "blocks", "tethers")
    private val blockKeys = setOf("id", "block", "config", "isolation", "varArgCounts")
    private val tetherKeys = setOf("type", "from", "to", "delivery", "port")
    private val endpointKeys = setOf("block", "port", "index")

    /** Parses `cringle-project.json`. */
    public fun parseProject(text: String): ProjectManifest {
        val file = PackageKind.PROJECT.manifestFile
        val o = header(text, file, PackageKind.PROJECT, projectKeys)
        val fabrics = JsonReading.objectList(o, "fabrics", "$").mapIndexed { i, f -> fabric(f, "$.fabrics[$i]") }
        return ProjectManifest(
            name = name(o, file),
            version = version(o, file),
            dependencies = dependencies(o),
            blueprints = JsonReading.stringList(o, "blueprints", "$"),
            schemas = JsonReading.stringList(o, "schemas", "$"),
            fabrics = fabrics,
        )
    }

    /** Parses `cringle-plugin.json`. */
    public fun parsePlugin(text: String): PluginManifest {
        val file = PackageKind.PLUGIN.manifestFile
        val o = header(text, file, PackageKind.PLUGIN, pluginKeys)
        val blocks = JsonReading.objectList(o, "blocks", "$").mapIndexed { i, b -> BlockDefinitionJson.fromJson(b, "$.blocks[$i]") }
        val processors = o["processors"]?.let {
            val p = JsonReading.obj(it, "$.processors")
            JsonReading.keys(p, "$.processors", setOf("update", "downgrade"))
            ProcessorSet(JsonReading.optString(p, "update", "$.processors"), JsonReading.optString(p, "downgrade", "$.processors"))
        } ?: ProcessorSet()
        return PluginManifest(
            name = name(o, file),
            version = version(o, file),
            dependencies = dependencies(o),
            providers = JsonReading.stringList(o, "providers", "$"),
            drivers = JsonReading.stringList(o, "drivers", "$"),
            blocks = blocks,
            libs = JsonReading.stringList(o, "libs", "$"),
            schemas = JsonReading.stringList(o, "schemas", "$"),
            processors = processors,
        )
    }

    /** Parses a blueprint file. [file] names the entry in error messages. */
    public fun parseBlueprint(text: String, file: String): Blueprint {
        val o = JsonReading.parseObject(text, file)
        JsonReading.keys(o, "$file $", blueprintKeys)
        val path = "$file $"
        val blocks = JsonReading.objectList(o, "blocks", path).mapIndexed { i, b -> block(b, "$file $.blocks[$i]") }
        val tethers = JsonReading.objectList(o, "tethers", path).mapIndexed { i, t -> tether(t, "$file $.tethers[$i]") }
        return Blueprint(checkedName(JsonReading.string(o, "name", path), "$path.name"), blocks, tethers)
    }

    private fun header(text: String, file: String, kind: PackageKind, allowed: Set<String>): JsonObject {
        val o = JsonReading.parseObject(text, file)
        JsonReading.keys(o, file, allowed)
        val format = JsonReading.optInt(o, "format", file) ?: throw PackageFormatException(file, "missing key 'format'")
        if (format != PackageNames.FORMAT) throw PackageFormatException("$file $.format", "unsupported format $format (supported: ${PackageNames.FORMAT})")
        val declared = JsonReading.string(o, "kind", file)
        if (declared != kind.jsonName) throw PackageFormatException("$file $.kind", "must be '${kind.jsonName}' in ${kind.manifestFile}, got '$declared'")
        return o
    }

    private fun name(o: JsonObject, file: String): String = checkedName(JsonReading.string(o, "name", file), "$file $.name")

    private fun checkedName(value: String, path: String): String {
        if (!PackageNames.name.matches(value)) throw PackageFormatException(path, "invalid name '$value': expected ${PackageNames.name.pattern}")
        return value
    }

    private fun version(o: JsonObject, file: String): String {
        val v = JsonReading.string(o, "version", file)
        if (!PackageNames.version.matches(v)) throw PackageFormatException("$file $.version", "invalid version '$v': expected MAJOR.MINOR.PATCH[-prerelease]")
        return v
    }

    private fun dependencies(o: JsonObject): Map<String, String> {
        val deps = JsonReading.stringMap(o, "dependencies", "$")
        for ((n, range) in deps) {
            checkedName(n, "$.dependencies.$n")
            if (range.isBlank()) throw PackageFormatException("$.dependencies.$n", "version range must not be blank")
        }
        return deps
    }

    private fun fabric(o: JsonObject, path: String): FabricConfig {
        JsonReading.keys(o, path, fabricKeys)
        val instances = JsonReading.optInt(o, "instances", path) ?: 1
        if (instances < 1) throw PackageFormatException("$path.instances", "must be at least 1")
        return FabricConfig(
            JsonReading.string(o, "blueprint", path),
            instances,
            JsonReading.stringList(o, "roles", path),
            JsonReading.stringMap(o, "labels", path),
        )
    }

    private fun block(o: JsonObject, path: String): BlueprintBlock {
        JsonReading.keys(o, path, blockKeys)
        val config = o["config"]?.let { JsonReading.obj(it, "$path.config") } ?: JsonObject(emptyMap())
        val isolation = JsonReading.optString(o, "isolation", path)?.let { enumValue<IsolationLevel>(it, "$path.isolation") }
            ?: IsolationLevel.SHARED
        val counts = o["varArgCounts"]?.let { c ->
            JsonReading.obj(c, "$path.varArgCounts").mapValues { (k, v) ->
                (v as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()?.takeIf { it >= 0 }
                    ?: throw PackageFormatException("$path.varArgCounts.$k", "must be a non-negative integer")
            }
        } ?: emptyMap()
        return BlueprintBlock(JsonReading.string(o, "id", path), JsonReading.string(o, "block", path), config, isolation, counts)
    }

    private fun tether(o: JsonObject, path: String): TetherDef {
        JsonReading.keys(o, path, tetherKeys)
        val type = enumValue<TetherType>(JsonReading.string(o, "type", path), "$path.type")
        val from = endpoint(o["from"] ?: throw PackageFormatException(path, "missing key 'from'"), "$path.from")
        val to = endpoint(o["to"] ?: throw PackageFormatException(path, "missing key 'to'"), "$path.to")
        val delivery = o["delivery"]?.let { enumValue<DeliveryPolicy>(JsonReading.string(o, "delivery", path), "$path.delivery") } ?: DeliveryPolicy.DROP
        val port = JsonReading.optInt(o, "port", path)
        return TetherDef(type, from, to, delivery, port)
    }

    private fun endpoint(e: JsonElement, path: String): Endpoint {
        val o = JsonReading.obj(e, path)
        JsonReading.keys(o, path, endpointKeys)
        val index = JsonReading.optInt(o, "index", path)
        if (index != null && index < 0) throw PackageFormatException("$path.index", "must not be negative")
        return Endpoint(JsonReading.string(o, "block", path), JsonReading.string(o, "port", path), index)
    }

    /** Writes `cringle-project.json`. */
    public fun encode(m: ProjectManifest): String = pretty.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("format", PackageNames.FORMAT)
            put("kind", PackageKind.PROJECT.jsonName)
            put("name", m.name)
            put("version", m.version)
            put("dependencies", strings(m.dependencies))
            put("blueprints", list(m.blueprints))
            put("schemas", list(m.schemas))
            put(
                "fabrics",
                JsonArray(
                    m.fabrics.map { f ->
                        buildJsonObject {
                            put("blueprint", f.blueprint)
                            put("instances", f.instances)
                            put("roles", list(f.roles))
                            put("labels", strings(f.labels))
                        }
                    },
                ),
            )
        },
    ) + "\n"

    /** Writes `cringle-plugin.json`. */
    public fun encode(m: PluginManifest): String = pretty.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("format", PackageNames.FORMAT)
            put("kind", PackageKind.PLUGIN.jsonName)
            put("name", m.name)
            put("version", m.version)
            put("dependencies", strings(m.dependencies))
            put("providers", list(m.providers))
            put("drivers", list(m.drivers))
            put("blocks", JsonArray(m.blocks.map { b: BlockDefinition -> BlockDefinitionJson.toJson(b) }))
            put("libs", list(m.libs))
            put("schemas", list(m.schemas))
            if (m.processors != ProcessorSet()) {
                put(
                    "processors",
                    buildJsonObject {
                        m.processors.update?.let { put("update", it) }
                        m.processors.downgrade?.let { put("downgrade", it) }
                    },
                )
            }
        },
    ) + "\n"

    /** Writes a blueprint file. */
    public fun encode(b: Blueprint): String = pretty.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("name", b.name)
            put(
                "blocks",
                JsonArray(
                    b.blocks.map { x ->
                        buildJsonObject {
                            put("id", x.id)
                            put("block", x.block)
                            put("config", x.config)
                            put("isolation", x.isolation.name)
                            put("varArgCounts", JsonObject(x.varArgCounts.mapValues { JsonPrimitive(it.value) }))
                        }
                    },
                ),
            )
            put(
                "tethers",
                JsonArray(
                    b.tethers.map { t ->
                        buildJsonObject {
                            put("type", t.type.name)
                            put("from", endpoint(t.from))
                            put("to", endpoint(t.to))
                            if (t.delivery != DeliveryPolicy.DROP) put("delivery", t.delivery.name)
                            t.port?.let { put("port", it) }
                        }
                    },
                ),
            )
        },
    ) + "\n"

    private fun endpoint(e: Endpoint): JsonObject = buildJsonObject {
        put("block", e.block)
        put("port", e.port)
        e.index?.let { put("index", it) }
    }

    private fun list(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

    private fun strings(values: Map<String, String>): JsonObject = JsonObject(values.mapValues { JsonPrimitive(it.value) })
}
