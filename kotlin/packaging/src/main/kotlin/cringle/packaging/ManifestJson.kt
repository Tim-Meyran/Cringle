// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import cringle.contract.BlockDefinition
import cringle.contract.IsolationLevel
import cringle.contract.Parity
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

    /**
     * Grammar of the identifiers that appear inside a blueprint and become file or directory names of a
     * fabric: block ids, port names and blueprint references. Letters, digits, `.`, `-` and `_`, at most 64
     * characters, never starting or ending with a separator.
     */
    public val identifier: Regex = Regex("[A-Za-z0-9](?:[A-Za-z0-9._-]{0,62}[A-Za-z0-9])?")

    /**
     * Semantic version `MAJOR.MINOR.PATCH` with optional `-prerelease` (`spec/versioning.md`, section 1): a numeric
     * prerelease identifier has no leading zeros, and build metadata is not part of a version.
     */
    public val version: Regex = Regex(
        "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-($PRERELEASE(\\.$PRERELEASE)*))?",
    )

    /** One prerelease identifier: `0`, a decimal without leading zeros, or something that is not all digits. */
    private const val PRERELEASE: String = "(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"

    /** The largest number [Version] holds; the grammar alone does not say it. */
    private const val MAX_NUMBER: String = "2147483647"

    /** Names Windows refuses as a file or directory name, whatever the extension. */
    private val windowsReserved: Regex = Regex("(aux|con|nul|prn|com[1-9]|lpt[1-9])", RegexOption.IGNORE_CASE)

    /** The manifest format number this implementation reads and writes. */
    public const val FORMAT: Int = 1

    /** Why [value] is not a valid [name], or `null` if it is. */
    public fun nameProblem(value: String): String? = when {
        !name.matches(value) -> "invalid name '$value': expected ${name.pattern}"
        else -> reserved(value)
    }

    /** Why [value] is not a valid [identifier], or `null` if it is. */
    public fun identifierProblem(value: String): String? = when {
        !identifier.matches(value) -> "invalid identifier '$value': expected ${identifier.pattern}"
        else -> reserved(value)
    }

    /**
     * Why [value] is not a valid [version], or `null` if it is. The numbers of `MAJOR.MINOR.PATCH` must also fit in
     * the [Int] that [Version] holds them in; prerelease identifiers are only compared, as `BigInteger`, and are not
     * limited.
     */
    public fun versionProblem(value: String): String? = when {
        !version.matches(value) -> "invalid version '$value': expected MAJOR.MINOR.PATCH[-prerelease] without leading zeros"
        !fitsNumber(value) -> "invalid version '$value': MAJOR.MINOR.PATCH must not be larger than $MAX_NUMBER"
        else -> null
    }

    /** Without leading zeros a shorter number is always smaller, and equal lengths compare as text. */
    private fun fitsNumber(value: String): Boolean = value.substringBefore('-').split('.').all { n ->
        n.length < MAX_NUMBER.length || (n.length == MAX_NUMBER.length && n <= MAX_NUMBER)
    }

    /** Windows also refuses `con.txt`, so the part before the first `.` decides. */
    private fun reserved(value: String): String? = value.substringBefore('.').takeIf { windowsReserved.matches(it) }
        ?.let { "'$it' is reserved on Windows and cannot be used as a file or directory name" }
}

/** JSON (de)serialization of manifests and blueprints (`spec/package-format.md`, sections 3 to 6). */
public object ManifestJson {
    private val pretty = Json { prettyPrint = true }
    private val projectKeys = setOf("format", "kind", "name", "version", "dependencies", "blueprints", "schemas", "fabrics")
    private val pluginKeys = setOf(
        "format", "kind", "name", "version", "dependencies", "providers", "drivers", "blocks", "libs", "schemas", "processors",
    )
    private val fabricKeys = setOf("blueprint", "instances", "roles", "labels")
    private val blueprintKeys = setOf("name", "blocks", "tethers", "provides")
    private val providesKeys = setOf("service", "block", "port", "type")
    private val blockKeys = setOf("id", "block", "config", "isolation", "varArgCounts")
    private val tetherKeys = setOf("type", "from", "to", "delivery", "port", "bufferCapacity", "requestTimeout", "retry", "serial", "remote", "record")
    private val endpointKeys = setOf("block", "port", "index")
    private val remoteKeys = setOf("address", "fingerprint", "fabric", "block", "port", "index")

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
        val provides = if (o.containsKey("provides")) JsonReading.objectList(o, "provides", path).mapIndexed { i, p -> provided(p, "$file $.provides[$i]") } else emptyList()
        return Blueprint(checkedName(JsonReading.string(o, "name", path), "$path.name"), blocks, tethers, provides)
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

    private fun checkedName(value: String, path: String): String = checked(value, path) { PackageNames.nameProblem(it) }

    /** Rejects anything that is not a valid identifier (`cringle.packaging.PackageNames.identifier`). */
    internal fun checkedIdentifier(value: String, path: String): String = checked(value, path) { PackageNames.identifierProblem(it) }

    private inline fun checked(value: String, path: String, problem: (String) -> String?): String {
        val p = problem(value)
        if (p != null) throw PackageFormatException(path, p)
        return value
    }

    private fun version(o: JsonObject, file: String): String =
        checked(JsonReading.string(o, "version", file), "$file $.version") { PackageNames.versionProblem(it) }

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
            checkedIdentifier(JsonReading.string(o, "blueprint", path), "$path.blueprint"),
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
        return BlueprintBlock(
            checkedIdentifier(JsonReading.string(o, "id", path), "$path.id"),
            JsonReading.string(o, "block", path),
            config,
            isolation,
            counts,
        )
    }

    private fun tether(o: JsonObject, path: String): TetherDef {
        JsonReading.keys(o, path, tetherKeys)
        val type = enumValue<TetherType>(JsonReading.string(o, "type", path), "$path.type")
        val from = o["from"]?.let { endpoint(it, "$path.from") }
        val to = o["to"]?.let { endpoint(it, "$path.to") }
        val delivery = o["delivery"]?.let { enumValue<DeliveryPolicy>(JsonReading.string(o, "delivery", path), "$path.delivery") } ?: DeliveryPolicy.DROP
        val port = JsonReading.optInt(o, "port", path)
        val bufferCapacity = JsonReading.optInt(o, "bufferCapacity", path)
        val requestTimeout = JsonReading.optInt(o, "requestTimeout", path)?.let { java.time.Duration.ofMillis(it.toLong()) }
        val retry = o["retry"]?.let { element ->
            val r = JsonReading.obj(element, "$path.retry")
            JsonReading.keys(r, "$path.retry", setOf("maxAttempts", "backoffMs", "backoff", "maxBackoffMs"))
            RetryConfig(
                maxAttempts = JsonReading.optInt(r, "maxAttempts", "$path.retry"),
                backoffMs = JsonReading.optInt(r, "backoffMs", "$path.retry")?.toLong() ?: 50L,
                backoff = JsonReading.optString(r, "backoff", "$path.retry")?.let { enumValue<Backoff>(it, "$path.retry.backoff") } ?: Backoff.FIXED,
                maxBackoffMs = JsonReading.optInt(r, "maxBackoffMs", "$path.retry")?.toLong() ?: 5000L,
            )
        }
        val serial = o["serial"]?.let { element ->
            val s = JsonReading.obj(element, "$path.serial")
            JsonReading.keys(s, "$path.serial", setOf("device", "baudRate", "dataBits", "parity", "stopBits"))
            SerialTetherConfig(
                device = JsonReading.string(s, "device", "$path.serial"),
                baudRate = JsonReading.optInt(s, "baudRate", "$path.serial") ?: 9600,
                dataBits = JsonReading.optInt(s, "dataBits", "$path.serial") ?: 8,
                parity = JsonReading.optString(s, "parity", "$path.serial")?.let { enumValue<Parity>(it, "$path.serial.parity") } ?: Parity.NONE,
                stopBits = JsonReading.optInt(s, "stopBits", "$path.serial") ?: 1,
            )
        }
        val record = o["record"]?.let { element ->
            val r = JsonReading.obj(element, "$path.record")
            JsonReading.keys(r, "$path.record", setOf("maxAge", "maxBytes"))
            RecordConfig(
                maxAge = JsonReading.optLong(r, "maxAge", "$path.record")?.let { java.time.Duration.ofMillis(it) },
                maxBytes = JsonReading.optLong(r, "maxBytes", "$path.record"),
            )
        }
        val remoteObject = o["remote"]?.let { JsonReading.obj(it, "$path.remote") }
        val service = remoteObject?.get("service")?.let { serviceName(remoteObject, "$path.remote") }
        val remote = if (service == null) remoteObject?.let { remoteEndpoint(it, "$path.remote") } else null
        return TetherDef(type, from, to, delivery, port, bufferCapacity, requestTimeout, retry, serial, remote, service, record)
    }

    /** The abstract form of a `remote`: `{"service": <name>}` and nothing else. */
    private fun serviceName(o: JsonObject, path: String): String {
        JsonReading.keys(o, path, setOf("service"))
        return checkedName(JsonReading.string(o, "service", path), "$path.service")
    }

    private fun provided(o: JsonObject, path: String): ProvidedService {
        JsonReading.keys(o, path, providesKeys)
        return ProvidedService(
            checkedName(JsonReading.string(o, "service", path), "$path.service"),
            JsonReading.string(o, "block", path),
            JsonReading.string(o, "port", path),
            JsonReading.optString(o, "type", path)?.let { enumValue<TetherType>(it, "$path.type") },
        )
    }

    private fun remoteEndpoint(o: JsonObject, path: String): RemoteEndpoint {
        JsonReading.keys(o, path, remoteKeys)
        val index = JsonReading.optInt(o, "index", path)
        if (index != null && index < 0) throw PackageFormatException("$path.index", "must not be negative")
        return RemoteEndpoint(
            JsonReading.optString(o, "address", path),
            JsonReading.string(o, "fingerprint", path),
            JsonReading.string(o, "fabric", path),
            JsonReading.string(o, "block", path),
            JsonReading.string(o, "port", path),
            index,
        )
    }

    private fun endpoint(e: JsonElement, path: String): Endpoint {
        val o = JsonReading.obj(e, path)
        JsonReading.keys(o, path, endpointKeys)
        val index = JsonReading.optInt(o, "index", path)
        if (index != null && index < 0) throw PackageFormatException("$path.index", "must not be negative")
        return Endpoint(
            checkedIdentifier(JsonReading.string(o, "block", path), "$path.block"),
            checkedIdentifier(JsonReading.string(o, "port", path), "$path.port"),
            index,
        )
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
                            t.from?.let { put("from", endpoint(it)) }
                            t.to?.let { put("to", endpoint(it)) }
                            t.remote?.let { r ->
                                put("remote", buildJsonObject {
                                    r.address?.let { put("address", it) }
                                    put("fingerprint", r.fingerprint)
                                    put("fabric", r.fabric)
                                    put("block", r.block)
                                    put("port", r.port)
                                    r.index?.let { put("index", it) }
                                })
                            }
                            t.service?.let { s -> put("remote", buildJsonObject { put("service", s) }) }
                            t.record?.let { r ->
                                put("record", buildJsonObject {
                                    r.maxAge?.let { put("maxAge", it.toMillis()) }
                                    r.maxBytes?.let { put("maxBytes", it) }
                                })
                            }
                            if (t.delivery != DeliveryPolicy.DROP) put("delivery", t.delivery.name)
                            t.port?.let { put("port", it) }
                            t.bufferCapacity?.let { put("bufferCapacity", it) }
                            t.requestTimeout?.let { put("requestTimeout", it.toMillis()) }
                            t.retry?.let { r ->
                                put("retry", buildJsonObject {
                                    r.maxAttempts?.let { put("maxAttempts", it) }
                                    put("backoffMs", r.backoffMs)
                                    put("backoff", r.backoff.name)
                                    put("maxBackoffMs", r.maxBackoffMs)
                                })
                            }
                            t.serial?.let { s ->
                                put("serial", buildJsonObject {
                                    put("device", s.device)
                                    put("baudRate", s.baudRate)
                                    put("dataBits", s.dataBits)
                                    put("parity", s.parity.name)
                                    put("stopBits", s.stopBits)
                                })
                            }
                        }
                    },
                ),
            )
            if (b.provides.isNotEmpty()) {
                put(
                    "provides",
                    JsonArray(
                        b.provides.map { p ->
                            buildJsonObject {
                                put("service", p.service)
                                put("block", p.block)
                                put("port", p.port)
                                p.type?.let { put("type", it.name) }
                            }
                        },
                    ),
                )
            }
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
