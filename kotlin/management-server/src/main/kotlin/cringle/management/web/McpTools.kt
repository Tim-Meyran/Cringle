// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.BlockDefinition
import cringle.engine.v1.LogLevel
import cringle.management.ManagementCore
import cringle.management.FabricView
import cringle.packaging.ManifestJson
import cringle.packaging.PackageProblem
import cringle.router.users.Permission
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The arguments of a tool call, read with a readable error for a missing or wrong one. */
internal class McpArgs(val values: JsonObject) {
    fun string(name: String): String? = (values[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    fun required(name: String): String = string(name)?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("the argument '$name' is required")

    fun int(name: String): Int? = values[name]?.let { (it as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("the argument '$name' must be a number") }

    fun long(name: String): Long? = values[name]?.let { (it as? JsonPrimitive)?.longOrNull ?: throw IllegalArgumentException("the argument '$name' must be a number") }

    fun bool(name: String): Boolean? = values[name]?.let { (it as? JsonPrimitive)?.booleanOrNull ?: throw IllegalArgumentException("the argument '$name' must be true or false") }

    fun strings(name: String): List<String> = values[name]?.let { v ->
        (v as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: throw IllegalArgumentException("the argument '$name' must be a list of texts") }
            ?: throw IllegalArgumentException("the argument '$name' must be a list of texts")
    }.orEmpty()

    fun strings2(name: String): Map<String, String> = values[name]?.let { v ->
        (v as? JsonObject)?.mapValues { (_, x) -> (x as? JsonPrimitive)?.contentOrNull ?: throw IllegalArgumentException("the argument '$name' must map names to texts") }
            ?: throw IllegalArgumentException("the argument '$name' must be an object of texts")
    }.orEmpty()

    fun obj(name: String): JsonObject? = values[name]?.let { it as? JsonObject ?: throw IllegalArgumentException("the argument '$name' must be an object") }
}

/** One argument of a tool: [type] is a JSON schema type. */
internal class McpProp(val name: String, val type: String, val description: String, val required: Boolean = false, val items: String? = null)

/**
 * A tool: [permission] is what the user needs somewhere to see and call it (the object of the call is checked inside [run] with the scopes of that object);
 * [write] tools are not offered in `--mcp read-only`; [destructive] ones say so in their annotations.
 */
internal class McpTool(
    val name: String,
    val description: String,
    val props: List<McpProp>,
    val permission: Permission,
    val write: Boolean,
    val destructive: Boolean = false,
    val run: suspend (Session, McpArgs) -> JsonElement,
) {
    fun allowed(session: Session): Boolean = session.canAnywhere(permission)

    fun describe(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put(
            "inputSchema",
            buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        props.forEach { p ->
                            put(
                                p.name,
                                buildJsonObject {
                                    put("type", p.type)
                                    put("description", p.description)
                                    if (p.type == "array") put("items", buildJsonObject { put("type", p.items ?: "string") })
                                },
                            )
                        }
                    },
                )
                put("required", buildJsonArray { props.filter { it.required }.forEach { add(JsonPrimitive(it.name)) } })
            },
        )
        put("annotations", buildJsonObject { put("readOnlyHint", !write); put("destructiveHint", destructive) })
    }
}

/** The tools of the MCP server: what the CLI and the WebUI can do, on [ManagementCore] and the drafts of the blueprint editor. */
internal class McpTools(private val core: ManagementCore, private val drafts: DraftStore, private val blueprints: BlueprintPages) {
    private val access get() = core.access

    val all: List<McpTool> = listOf(
        // --- read ---
        read("whoami", "The user of the token and what it may do.") { s, _ ->
            buildJsonObject {
                put("user", s.user?.name ?: "open mode (no --auth: everybody is administrator)")
                put("permissions", buildJsonArray { Permission.entries.filter { s.can(it) }.forEach { add(JsonPrimitive(it.name)) } })
                put("operate", s.canAnywhere(Permission.OPERATE))
                put("administer", s.canAnywhere(Permission.ADMINISTER))
            }
        },
        read("list_machines", "The machines (their daemons) and whether they answer.") { s, _ ->
            JsonArray(
                core.listMachines().filter { s.canFor(Permission.READ, access.machine(it.record.id)) }.map {
                    buildJsonObject { put("id", it.record.id); put("daemon", it.record.daemonAddress); put("reachable", it.reachable); put("lastError", it.lastError) }
                },
            )
        },
        read("list_engines", "The engines of one machine or of all machines, with their state.", prop("machine", "string", "Only this machine")) { s, a ->
            JsonArray(core.listEngines(a.string("machine")).filter { s.canFor(Permission.READ, access.machine(it.machine)) }.map(::engineJson))
        },
        read(
            "list_fabrics", "The fabrics (running blueprints) of one engine or of all engines, with the state of their blocks.",
            prop("machine", "string", "Only this machine"), prop("engine", "string", "Only this engine (with machine)"),
        ) { s, a ->
            JsonArray(core.listFabrics(a.string("machine"), a.string("engine")).filter { canReadFabric(s, it) }.map(::fabricJson))
        },
        read(
            "get_fabric", "One fabric: state, failure, every block with its state, restarts and last error, and the assertions.",
            prop("machine", "string", "The machine", true), prop("engine", "string", "The engine", true), prop("fabric", "string", "The fabric id", true),
        ) { s, a ->
            s.require(Permission.READ, access.fabric(a.required("machine"), a.required("fabric")))
            fabricJson(core.getFabric(a.required("machine"), a.required("engine"), a.required("fabric")))
        },
        read(
            "query_logs",
            "Log entries (newest last). Without machine and engine all running engines are asked. Use this to see what the blocks logged (log.write, flow.log, errors).",
            prop("machine", "string", "Only this machine"), prop("engine", "string", "Only this engine (with machine)"), prop("fabric", "string", "Only this fabric"),
            prop("block", "string", "Only this block id"), prop("level", "string", "Minimum level: debug, info, warn or error"),
            prop("sinceMinutes", "integer", "Only entries of the last minutes"), prop("limit", "integer", "At most this many entries (default 200)"),
        ) { s, a ->
            val request = cringle.engine.v1.QueryLogsRequest.newBuilder().setFabric(a.string("fabric").orEmpty()).setBlock(a.string("block").orEmpty())
                .setMinLevel(level(a.string("level"))).setLimit(a.int("limit") ?: 200)
            a.long("sinceMinutes")?.let { m -> Instant.now().minusSeconds(m * 60).let { request.since = com.google.protobuf.Timestamp.newBuilder().setSeconds(it.epochSecond).setNanos(it.nano).build() } }
            val result = core.queryLogs(a.string("machine"), a.string("engine"), request.build())
            val shown = result.entries.filter { s.canFor(Permission.READ, access.fabric(it.machine, it.entry.fabric)) || it.entry.fabric.isEmpty() && s.canFor(Permission.READ, access.machine(it.machine)) }
            buildJsonObject {
                put(
                    "entries",
                    buildJsonArray {
                        shown.forEach { v ->
                            add(
                                buildJsonObject {
                                    put("time", ts(v.entry.timestamp)); put("machine", v.machine); put("engine", v.engineId); put("fabric", v.entry.fabric); put("block", v.entry.block)
                                    put("level", v.entry.level.name.removePrefix("LOG_LEVEL_").lowercase()); put("message", v.entry.message); put("collected", v.collected)
                                },
                            )
                        }
                    },
                )
                put("problems", JsonArray(result.problems.map { JsonPrimitive(it) }))
            }
        },
        read("get_metrics", "CPU, heap and threads of the engines, errors and message counts of their fabrics, blocks and tethers.", prop("machine", "string", "Only this machine"), prop("engine", "string", "Only this engine")) { s, a ->
            val r = core.getMetrics(a.string("machine"), a.string("engine"))
            buildJsonObject {
                put(
                    "engines",
                    buildJsonArray {
                        r.metrics.filter { s.canFor(Permission.READ, access.machine(it.machine)) }.forEach { m ->
                            add(
                                buildJsonObject {
                                    put("machine", m.machine); put("engine", m.engineId); put("cpuLoad", m.metrics.processCpuLoad); put("heapUsedBytes", m.metrics.heapUsedBytes)
                                    put("heapMaxBytes", m.metrics.heapMaxBytes); put("threads", m.metrics.threadCount)
                                    put(
                                        "fabrics",
                                        buildJsonArray {
                                            m.metrics.fabricsList.forEach { f ->
                                                add(
                                                    buildJsonObject {
                                                        put("fabric", f.fabricId.value); put("errors", f.errors)
                                                        put("blocks", buildJsonArray { f.blocksList.forEach { b -> add(buildJsonObject { put("block", b.blockId); put("errors", b.errors) }) } })
                                                        put("tethers", buildJsonArray { f.tethersList.forEach { t -> add(buildJsonObject { put("tether", t.tetherId); put("type", t.type); put("messages", t.messages); put("bytes", t.bytes) }) } })
                                                    },
                                                )
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
                put("problems", JsonArray(r.problems.map { JsonPrimitive(it) }))
            }
        },
        read("list_deployments", "The projects that were deployed from the repository, with version, fabrics and whether they should run.") { s, _ ->
            JsonArray(
                core.deployedFabrics().filter { s.canFor(Permission.READ, access.fabric(it.machine, it.fabricId)) }.map {
                    buildJsonObject {
                        put("project", it.project); put("version", it.version); put("machine", it.machine); put("engine", it.engineId); put("fabric", it.fabricId); put("desiredRunning", it.desiredRunning)
                    }
                },
            )
        },
        read("list_bindings", "The bindings of service dependencies of projects to the fabrics that provide the services.", prop("project", "string", "Only this project")) { s, a ->
            JsonArray(
                core.listBindings(a.string("project").orEmpty()).filter { s.canFor(Permission.READ, access.project(it.consumerProject)) }.map {
                    buildJsonObject { put("project", it.consumerProject); put("service", it.service); put("fabrics", JsonArray(it.targets.map { t -> JsonPrimitive(t) })) }
                },
            )
        },
        read("list_packages", "The packages in the repository: plugins (blocks) and projects, with versions.", prop("kind", "string", "plugin or project")) { _, a ->
            val kind = when (a.string("kind")?.lowercase()) {
                null, "" -> cringle.repository.v1.PackageKind.PACKAGE_KIND_UNSPECIFIED
                "plugin" -> cringle.repository.v1.PackageKind.PACKAGE_KIND_PLUGIN
                "project" -> cringle.repository.v1.PackageKind.PACKAGE_KIND_PROJECT
                else -> throw IllegalArgumentException("kind is plugin or project")
            }
            val packages = core.repository().listPackages(cringle.repository.v1.ListPackagesRequest.newBuilder().setKind(kind).build()).packagesList
                .sortedWith(compareBy({ it.kind.number }, { it.name }, { it.version }))
            JsonArray(
                packages.map {
                    buildJsonObject {
                        put("kind", it.kind.name.removePrefix("PACKAGE_KIND_").lowercase()); put("name", it.name); put("version", it.version); put("sizeBytes", it.sizeBytes)
                        if (it.kind == cringle.repository.v1.PackageKind.PACKAGE_KIND_PLUGIN) put("trust", it.trust.name.lowercase())
                        put("dependencies", JsonObject(it.dependenciesMap.mapValues { d -> JsonPrimitive(d.value) }))
                    }
                },
            )
        },
        read("list_blocks", "The blocks that blueprints can use: the plugins of the repository (highest version) with every block's ports (direction, tether types, schema, varArg, type parameter), configuration schema and type parameters.", prop("plugin", "string", "Only this plugin")) { _, a ->
            JsonArray(blueprints.catalogBlocks().filter { a.string("plugin") == null || it.first == a.string("plugin") }.map { (plugin, version, block) -> blockJson(plugin, version, block) })
        },
        read("dwh_list", "The partitions of the data warehouse (block data and recorded tether messages) with size and retention.", prop("fabric", "string", "Only this fabric")) { s, a ->
            val (partitions, problems) = core.listDwhPartitions(a.string("fabric").orEmpty())
            buildJsonObject {
                put(
                    "partitions",
                    buildJsonArray {
                        partitions.filter { (machine, _, info) -> s.canFor(Permission.READ, access.fabric(machine, info.fabric)) }.forEach { (machine, engine, info) ->
                            add(
                                buildJsonObject {
                                    put("machine", machine); put("engine", engine); put("fabric", info.fabric); put("kind", info.kind.name.removePrefix("DWH_KIND_").lowercase())
                                    put("name", info.name); put("bytes", info.bytes); put("maxAgeMs", info.retention.maxAgeMs); put("maxBytes", info.retention.maxBytes)
                                },
                            )
                        }
                    },
                )
                put("problems", JsonArray(problems.map { JsonPrimitive(it) }))
            }
        },
        read(
            "dwh_query", "The newest records of a data warehouse partition (block data or the recorded messages of a tether), oldest first.",
            prop("fabric", "string", "The fabric id", true), prop("kind", "string", "block or tether", true), prop("name", "string", "The block id, or the tether as dwh_list names it", true), prop("limit", "integer", "At most this many (default 100)"),
        ) { s, a ->
            s.require(Permission.READ, access.fabricById(a.required("fabric")))
            val kind = when (a.required("kind").lowercase()) {
                "block" -> cringle.engine.v1.DwhKind.DWH_KIND_BLOCK
                "tether" -> cringle.engine.v1.DwhKind.DWH_KIND_TETHER
                else -> throw IllegalArgumentException("kind is block or tether")
            }
            val response = core.queryDwh(cringle.management.v1.QueryDwhRequest.newBuilder().setFabric(a.required("fabric")).setKind(kind).setName(a.required("name")).setLimit(a.int("limit") ?: 100).build())
            JsonArray(response.recordsList.map { r -> buildJsonObject { put("time", ts(r.timestamp)); put("payload", r.payloadJson); put("tags", JsonObject(r.tagsMap.mapValues { JsonPrimitive(it.value) })) } })
        },
        read("debug_state", "The breakpoints of a fabric and the values held at them.", prop("fabric", "string", "The fabric id", true)) { s, a ->
            s.require(Permission.READ, access.fabricById(a.required("fabric")))
            val state = core.debugState(a.required("fabric"))
            buildJsonObject {
                put("breakpoints", JsonArray(state.breakpointsList.map { JsonPrimitive(it) }))
                put("held", buildJsonArray { state.heldList.forEach { h -> add(buildJsonObject { put("tether", h.tether); put("from", h.from); put("to", h.to); put("kind", h.kind); put("payload", h.payload); put("sinceMillis", h.sinceEpochMillis) }) } })
            }
        },
        read("get_config", "The settings of a machine (daemon, repository, management server) with value, default and description.", prop("machine", "string", "The machine (default local)")) { s, a ->
            val machine = a.string("machine")?.ifEmpty { null } ?: "local"
            s.require(Permission.READ, access.machine(machine))
            val response = core.listConfig(machine)
            buildJsonObject {
                put("entries", buildJsonArray { response.entriesList.forEach { e -> add(buildJsonObject { put("key", e.key); put("value", e.value); put("default", e.defaultValue); put("set", e.isSet); put("type", e.type); put("description", e.description) }) } })
                put("problems", JsonArray(response.problemsList.map { JsonPrimitive(it) }))
            }
        },
        read("list_trust", "The keys that the management server trusts (daemons, routers, engines, components).") { s, _ ->
            s.require(Permission.READ, access.function("trust"))
            JsonArray(core.listTrust().map { buildJsonObject { put("name", it.name); put("kind", it.kind); put("fingerprint", it.fingerprint); put("address", it.address); put("origin", it.origin) } })
        },
        read("list_projects", "The project drafts of the blueprint editor (not the published packages: see list_packages).") { s, _ ->
            JsonArray(
                drafts.list("project").filter { s.canFor(Permission.READ, access.project(it.name)) }.map {
                    buildJsonObject {
                        put("name", it.name); put("version", it.version); put("revision", it.revision)
                        put("blocks", blueprints.blueprintOf(it)?.blocks?.size ?: 0); put("tethers", blueprints.blueprintOf(it)?.tethers?.size ?: 0)
                    }
                },
            )
        },
        read("get_blueprint", "The blueprint of a project draft as JSON (blocks with config, isolation, varArgCounts and types; tethers) and its version and revision.", prop("project", "string", "The project name", true)) { s, a ->
            s.require(Permission.READ, access.project(a.required("project")))
            val draft = drafts.load("project", a.required("project")) ?: throw IllegalArgumentException("there is no project '${a.required("project")}'")
            val blueprint = blueprints.blueprintOf(draft)
            buildJsonObject {
                put("project", draft.name); put("version", draft.version); put("revision", draft.revision)
                put("blueprint", blueprint?.let { Json.parseToJsonElement(ManifestJson.encode(it)) } ?: JsonNull)
            }
        },
        read(
            "check_blueprint", "Checks a blueprint like the editor and publishing do (blocks, config against the config schema, tether types and schemas, type parameters). Give the project (its saved blueprint) or a blueprint.",
            prop("project", "string", "A project draft"), prop("blueprint", "object", "A blueprint to check instead"),
        ) { s, a ->
            val blueprint = blueprintArgument(a, s, Permission.READ)
            problemsJson(blueprints.checkBlueprint(blueprint))
        },

        // --- write ---
        write("add_machine", "Adds a machine (its daemon) to the machine park.", Permission.ADMINISTER, prop("id", "string", "The id of the machine", true), prop("address", "string", "host:port of the daemon", true)) { s, a ->
            s.require(Permission.ADMINISTER, emptyList())
            val m = core.addMachine(a.required("id"), a.required("address"), null, null)
            buildJsonObject { put("id", m.record.id); put("reachable", m.reachable); put("lastError", m.lastError) }
        },
        destroy("remove_machine", "Forgets a machine; its engines keep running.", Permission.ADMINISTER, prop("id", "string", "The id of the machine", true)) { s, a ->
            s.require(Permission.ADMINISTER, emptyList())
            core.removeMachine(a.required("id"))
            done("removed machine ${a.required("id")}")
        },
        write(
            "create_engine", "Creates an engine on a machine.", Permission.OPERATE, prop("machine", "string", "The machine", true), prop("engine", "string", "The engine id (default: generated)"), prop("name", "string", "A name"),
            prop("autostart", "boolean", "Start with the machine (default true)"), prop("roles", "array", "Roles of the engine"), prop("labels", "object", "Labels of the engine"),
        ) { s, a ->
            s.require(Permission.OPERATE, access.machine(a.required("machine")))
            engineJson(core.createEngine(a.required("machine"), a.string("engine"), a.string("name"), a.bool("autostart") ?: true, a.strings("roles"), a.strings2("labels")))
        },
        write("start_engine", "Starts an engine.", Permission.OPERATE, prop("machine", "string", "The machine", true), prop("engine", "string", "The engine", true)) { s, a ->
            s.require(Permission.OPERATE, access.machine(a.required("machine")))
            engineJson(core.startEngine(a.required("machine"), a.required("engine")))
        },
        write("stop_engine", "Stops an engine and its fabrics.", Permission.OPERATE, prop("machine", "string", "The machine", true), prop("engine", "string", "The engine", true)) { s, a ->
            s.require(Permission.OPERATE, access.machine(a.required("machine")))
            engineJson(core.stopEngine(a.required("machine"), a.required("engine")))
        },
        write(
            "delete_engine", "Stops and removes an engine.", Permission.OPERATE, prop("machine", "string", "The machine", true), prop("engine", "string", "The engine", true),
            prop("deleteData", "boolean", "Also delete its data (default false)"),
        ) { s, a ->
            s.require(Permission.OPERATE, access.machine(a.required("machine")))
            core.deleteEngine(a.required("machine"), a.required("engine"), a.bool("deleteData") ?: false)
            done("deleted engine ${a.required("engine")}")
        },
        write("set_engine_tags", "Sets the roles and labels of an engine (they replace the old ones).", Permission.OPERATE, prop("machine", "string", "The machine", true), prop("engine", "string", "The engine", true), prop("roles", "array", "Roles"), prop("labels", "object", "Labels")) { s, a ->
            s.require(Permission.OPERATE, access.machine(a.required("machine")))
            engineJson(core.setEngineTags(a.required("machine"), a.required("engine"), a.strings("roles"), a.strings2("labels")))
        },
        write("start_fabric", "Starts a fabric (again).", Permission.OPERATE, prop("machine", "string", "The machine", true), prop("engine", "string", "The engine", true), prop("fabric", "string", "The fabric id", true)) { s, a ->
            s.require(Permission.OPERATE, access.fabric(a.required("machine"), a.required("fabric")))
            fabricJson(core.startFabric(a.required("machine"), a.required("engine"), a.required("fabric")))
        },
        write("stop_fabric", "Stops a fabric.", Permission.OPERATE, prop("machine", "string", "The machine", true), prop("engine", "string", "The engine", true), prop("fabric", "string", "The fabric id", true)) { s, a ->
            s.require(Permission.OPERATE, access.fabric(a.required("machine"), a.required("fabric")))
            fabricJson(core.stopFabric(a.required("machine"), a.required("engine"), a.required("fabric")))
        },
        destroy("remove_fabric", "Removes a fabric from its engine.", Permission.OPERATE, prop("machine", "string", "The machine", true), prop("engine", "string", "The engine", true), prop("fabric", "string", "The fabric id", true)) { s, a ->
            s.require(Permission.OPERATE, access.fabric(a.required("machine"), a.required("fabric")))
            core.removeFabric(a.required("machine"), a.required("engine"), a.required("fabric"))
            done("removed fabric ${a.required("fabric")}")
        },
        write(
            "deploy", "Deploys a published project onto the engines its fabric configuration selects (blue/green by default).", Permission.OPERATE,
            prop("project", "string", "The project", true), prop("versionRange", "string", "Version range (default: the highest release)"), prop("start", "boolean", "Start the fabrics (default true)"),
            prop("relock", "boolean", "Resolve the dependencies again (default false)"), prop("blueGreen", "boolean", "Start the new fabrics next to the old ones (default true)"),
        ) { s, a ->
            s.require(Permission.OPERATE, access.project(a.required("project")))
            val r = core.deploy(a.required("project"), a.string("versionRange").orEmpty(), a.bool("start") ?: true, a.bool("relock") ?: false, a.bool("blueGreen") ?: true)
            buildJsonObject { put("project", r.project); put("version", r.version); put("strategy", r.strategy); put("fabrics", JsonArray(r.fabrics.map(::fabricJson))) }
        },
        destroy("undeploy", "Stops and removes all fabrics of a project.", Permission.OPERATE, prop("project", "string", "The project", true)) { s, a ->
            s.require(Permission.OPERATE, access.project(a.required("project")))
            buildJsonObject { put("removed", JsonArray(core.undeploy(a.required("project")).map { JsonPrimitive(it) })) }
        },
        write("rollback", "Goes back to an earlier version of a project (the one deployed before, or the given one).", Permission.OPERATE, prop("project", "string", "The project", true), prop("to", "string", "The version")) { s, a ->
            s.require(Permission.OPERATE, access.project(a.required("project")))
            val r = core.rollback(a.required("project"), a.string("to"))
            buildJsonObject { put("project", r.project); put("version", r.version); put("fabrics", JsonArray(r.fabrics.map(::fabricJson))) }
        },
        write("bind", "Binds a service dependency of a project to the fabrics that provide it, the preferred one first.", Permission.OPERATE, prop("project", "string", "The consuming project", true), prop("service", "string", "The service", true), prop("fabrics", "array", "The providing fabrics, preferred first", true)) { s, a ->
            s.require(Permission.OPERATE, access.project(a.required("project")))
            val b = core.bind(a.required("project"), a.required("service"), a.strings("fabrics"))
            buildJsonObject { put("project", b.consumerProject); put("service", b.service); put("fabrics", JsonArray(b.targets.map { JsonPrimitive(it) })) }
        },
        write("unbind", "Removes the binding of a service dependency.", Permission.OPERATE, prop("project", "string", "The consuming project", true), prop("service", "string", "The service", true)) { s, a ->
            s.require(Permission.OPERATE, access.project(a.required("project")))
            core.unbind(a.required("project"), a.required("service"))
            done("unbound ${a.required("service")} of ${a.required("project")}")
        },
        write(
            "set_recording", "Switches the recording of all typed tethers of a fabric into the data warehouse on or off.", Permission.OPERATE, prop("fabric", "string", "The fabric id", true), prop("on", "boolean", "true to record", true),
            prop("maxAgeMs", "integer", "Retention: oldest record in ms (0: no limit)"), prop("maxBytes", "integer", "Retention: size in bytes (0: no limit)"),
        ) { s, a ->
            s.require(Permission.OPERATE, access.fabricById(a.required("fabric")))
            val on = a.bool("on") ?: throw IllegalArgumentException("the argument 'on' is required")
            core.setRecording(a.required("fabric"), on, cringle.engine.v1.DwhRetention.newBuilder().setMaxAgeMs(a.long("maxAgeMs") ?: 0).setMaxBytes(a.long("maxBytes") ?: 0).build())
            done("recording of ${a.required("fabric")} is ${if (on) "on" else "off"}")
        },
        write(
            "set_breakpoint", "Sets or removes a breakpoint on a tether: values on it are held back before the receiver gets them (see debug_state, resume_fabric).", Permission.OPERATE,
            prop("fabric", "string", "The fabric id", true), prop("tether", "string", "The tether as dwh_list names it, e.g. 'c.out -> s.in'", true), prop("on", "boolean", "true to set, false to remove", true),
        ) { s, a ->
            s.require(Permission.OPERATE, access.fabricById(a.required("fabric")))
            val on = a.bool("on") ?: throw IllegalArgumentException("the argument 'on' is required")
            core.setBreakpoint(a.required("fabric"), a.required("tether"), on)
            done("breakpoint on ${a.required("tether")} is ${if (on) "set" else "removed"}")
        },
        write(
            "resume_fabric", "Releases the values held at breakpoints of a fabric (of one tether, or of all; only the oldest if one is true).", Permission.OPERATE,
            prop("fabric", "string", "The fabric id", true), prop("tether", "string", "Only this tether"), prop("one", "boolean", "Release only the oldest value"),
        ) { s, a ->
            s.require(Permission.OPERATE, access.fabricById(a.required("fabric")))
            buildJsonObject { put("released", core.resumeFabric(a.required("fabric"), a.string("tether").orEmpty(), a.bool("one") ?: false)) }
        },
        write("set_config", "Changes a setting of a machine, or removes it (without value) so that the default applies.", Permission.ADMINISTER, prop("machine", "string", "The machine (default local)"), prop("key", "string", "The key", true), prop("value", "string", "The value; leave out to unset")) { s, a ->
            val machine = a.string("machine")?.ifEmpty { null } ?: "local"
            s.require(Permission.ADMINISTER, access.machine(machine))
            val change = a.string("value")?.let { core.setConfig(machine, a.required("key"), it) } ?: core.unsetConfig(machine, a.required("key"))
            buildJsonObject {
                put("key", change.entry.key); put("value", change.entry.value); put("restarted", JsonArray(change.restartedList.map { JsonPrimitive(it) }))
                put("restartRequired", change.restartRequired); put("note", change.note)
            }
        },
        write("recover", "Starts the registered engines and restores their fabrics.", Permission.OPERATE) { s, _ ->
            s.require(Permission.OPERATE, emptyList())
            val r = core.recover()
            buildJsonObject { put("enginesStarted", r.enginesStarted); put("fabricsRestored", r.fabricsRestored); put("problems", JsonArray(r.problems.map { JsonPrimitive(it) })) }
        },
        write("create_project", "Creates an empty project draft (the workspace of a blueprint) in the blueprint editor.", Permission.OPERATE, prop("name", "string", "The project name", true), prop("version", "string", "The version (default 1.0.0)")) { s, a ->
            s.require(Permission.OPERATE, access.project(a.required("name")))
            if (drafts.load("project", a.required("name")) != null) throw IllegalArgumentException("the project '${a.required("name")}' exists")
            val d = drafts.save("project", a.required("name"), a.string("version")?.ifEmpty { null } ?: "1.0.0", blueprints.emptyContent(a.required("name")), null)
            buildJsonObject { put("project", d.name); put("version", d.version); put("revision", d.revision) }
        },
        write(
            "save_blueprint",
            "Stores the blueprint of a project draft. Format: {\"blocks\":[{\"id\":\"t\",\"block\":\"cringle-std/time.timer-trigger\",\"config\":{},\"varArgCounts\":{},\"types\":{\"T\":\"cringle.std/String\"}}]," +
                "\"tethers\":[{\"type\":\"MESSAGE\",\"from\":{\"block\":\"t\",\"port\":\"tick\"},\"to\":{\"block\":\"l\",\"port\":\"in\",\"index\":0}}]}. A blueprint with problems is stored as work in progress; the problems are returned.",
            Permission.OPERATE, prop("project", "string", "The project", true), prop("blueprint", "object", "The blueprint", true), prop("version", "string", "A new version of the project"),
        ) { s, a ->
            s.require(Permission.OPERATE, access.project(a.required("project")))
            val blueprint = parseBlueprint(a.required("project"), a.obj("blueprint") ?: throw IllegalArgumentException("the argument 'blueprint' is required"))
            val (draft, problems) = blueprints.saveBlueprint(a.required("project"), blueprint, a.string("version"))
            buildJsonObject { put("project", draft.name); put("version", draft.version); put("revision", draft.revision); put("valid", problems.isEmpty() && blueprint.blocks.isNotEmpty()); put("problems", problemsJson(problems)) }
        },
        write("publish_project", "Builds the project package from the draft, checks it and publishes it into the repository; deploy it afterwards.", Permission.OPERATE, prop("project", "string", "The project", true)) { s, a ->
            s.require(Permission.OPERATE, access.project(a.required("project")))
            val draft = drafts.load("project", a.required("project")) ?: throw IllegalArgumentException("there is no project '${a.required("project")}'")
            JsonPrimitive(blueprints.publish(draft))
        },
    )

    // --- helpers ---

    private fun read(name: String, description: String, vararg props: McpProp, run: suspend (Session, McpArgs) -> JsonElement) = McpTool(name, description, props.toList(), Permission.READ, false, false, run)

    private fun write(name: String, description: String, permission: Permission, vararg props: McpProp, run: suspend (Session, McpArgs) -> JsonElement) =
        McpTool(name, description, props.toList(), permission, true, false, run)

    private fun destroy(name: String, description: String, permission: Permission, vararg props: McpProp, run: suspend (Session, McpArgs) -> JsonElement) =
        McpTool(name, description, props.toList(), permission, true, true, run)

    private fun prop(name: String, type: String, description: String, required: Boolean = false) = McpProp(name, type, description, required)

    private fun done(message: String): JsonElement = buildJsonObject { put("ok", true); put("message", message) }

    private fun canReadFabric(s: Session, f: FabricView) = s.canFor(Permission.READ, access.fabric(f.machine, f.info.fabricId.value))

    private fun ts(t: com.google.protobuf.Timestamp): String = Instant.ofEpochSecond(t.seconds, t.nanos.toLong()).toString()

    private fun level(text: String?): LogLevel = when (text?.lowercase()) {
        null, "", "debug" -> LogLevel.LOG_LEVEL_DEBUG
        "info" -> LogLevel.LOG_LEVEL_INFO
        "warn", "warning" -> LogLevel.LOG_LEVEL_WARN
        "error" -> LogLevel.LOG_LEVEL_ERROR
        else -> throw IllegalArgumentException("level is debug, info, warn or error")
    }

    private fun engineJson(e: cringle.management.EngineView): JsonElement = buildJsonObject {
        put("machine", e.machine); put("engine", e.process.engineId.value); put("name", e.process.name); put("state", e.process.state.name.removePrefix("ENGINE_PROCESS_STATE_").lowercase())
        put("pid", e.process.pid); put("autostart", e.autostart); put("roles", JsonArray(e.roles.map { JsonPrimitive(it) })); put("labels", JsonObject(e.labels.mapValues { JsonPrimitive(it.value) }))
        put("lastError", e.process.lastError)
    }

    private fun fabricJson(f: FabricView): JsonObject = buildJsonObject {
        put("machine", f.machine); put("engine", f.engineId); put("fabric", f.info.fabricId.value); put("blueprint", f.info.blueprint)
        put("state", f.info.state.name.removePrefix("FABRIC_RUNTIME_STATE_").lowercase()); put("desiredRunning", f.desiredRunning); put("failure", f.info.failure)
        put("blocks", buildJsonArray { f.info.blocksList.forEach { b -> add(buildJsonObject { put("block", b.blockId.value); put("state", b.state.name.removePrefix("BLOCK_RUNTIME_STATE_").lowercase()); put("restarts", b.restarts); put("lastError", b.lastError) }) } })
        put("assertions", buildJsonArray { f.info.assertionsList.forEach { x -> add(buildJsonObject { put("id", x.id); put("type", x.type); put("state", x.state.name.removePrefix("ASSERTION_STATE_").lowercase()); put("detail", x.detail) }) } })
    }

    private fun blockJson(plugin: String, version: String, b: BlockDefinition): JsonObject = buildJsonObject {
        put("block", "$plugin/${b.name}"); put("plugin", plugin); put("version", version)
        b.configSchema?.let { put("configSchema", "${it.namespace}/${it.name}") }
        put("typeParameters", buildJsonArray { b.typeParameters.forEach { t -> add(buildJsonObject { put("name", t.name); put("bound", "${t.bound.namespace}/${t.bound.name}") }) } })
        put(
            "ports",
            buildJsonArray {
                b.ports.forEach { p ->
                    add(
                        buildJsonObject {
                            put("name", p.name); put("direction", p.direction.name); put("tetherTypes", JsonArray(p.tetherTypes.map { JsonPrimitive(it.name) }))
                            put("schema", "${p.schema.namespace}/${p.schema.name}"); if (p.varArg) put("varArg", true); p.typeParam?.let { put("typeParam", it) }
                        },
                    )
                }
            },
        )
    }

    private fun problemsJson(problems: List<PackageProblem>): JsonArray = JsonArray(problems.map { buildJsonObject { put("path", it.path); put("message", it.message) } })

    /** The blueprint of a call: the `blueprint` argument, or the saved blueprint of the `project` (the user needs [permission] for that project). */
    private suspend fun blueprintArgument(a: McpArgs, s: Session, permission: Permission): cringle.packaging.Blueprint {
        a.obj("blueprint")?.let { return parseBlueprint(a.string("project") ?: "check", it) }
        val project = a.string("project") ?: throw IllegalArgumentException("give a project or a blueprint")
        s.require(permission, access.project(project))
        val draft = drafts.load("project", project) ?: throw IllegalArgumentException("there is no project '$project'")
        return blueprints.blueprintOf(draft) ?: throw IllegalArgumentException("the project '$project' has no blueprint")
    }

    private fun parseBlueprint(name: String, blueprint: JsonObject): cringle.packaging.Blueprint {
        val withName = JsonObject(blueprint + ("name" to JsonPrimitive(name)))
        return try {
            ManifestJson.parseBlueprint(Json.encodeToString(JsonObject.serializer(), withName), "blueprint")
        } catch (e: cringle.packaging.PackageFormatException) {
            throw IllegalArgumentException("the blueprint is not valid: ${e.message}")
        }
    }

}
