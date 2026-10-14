// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.BlockDefinition
import cringle.management.ManagementCore
import cringle.management.ManagementException
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.Endpoint
import cringle.packaging.PackageNames
import cringle.packaging.FabricConfig
import cringle.packaging.ManifestJson
import cringle.packaging.PackageProblem
import cringle.packaging.PackageValidator
import cringle.packaging.PackageWriter
import cringle.packaging.ProjectManifest
import cringle.packaging.ProjectPackage
import cringle.packaging.ProvidedService
import cringle.packaging.TetherDef
import cringle.contract.TetherType
import cringle.router.users.Permission
import cringle.schema.SchemaParseException
import cringle.schema.SchemaParser
import java.io.ByteArrayOutputStream
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
 * The visual blueprint editor (#213) on Drawflow. A `project` draft holds `{"blueprint": <blueprint json>, "roles": [..]}`; the positions of the nodes are
 * kept as `ui.positions` in the draft, which the package never sees. The browser draws the graph and asks the server, whenever two ports are connected,
 * whether that is allowed ([check]): the server answers with the verdict of the validators that packaging uses (direction, tether type, schema). On
 * save the server turns the graph into the blueprint, validates it completely and stores the draft, also an invalid one as work in progress.
 */
internal class BlueprintPages(private val core: ManagementCore, private val drafts: DraftStore, private val files: ProjectFiles) {
    private val catalog = PluginCatalog(core)
    private val pretty = Json { prettyPrint = true }

    fun register(web: WebServer) {
        val r = web.router
        r.get("/blueprints/{name}", Permission.READ) { req ->
            val name = req.params.getValue("name")
            val draft = try {
                drafts.load("project", name)
            } catch (e: DraftException) {
                null
            }
            if (draft == null) web.problem(404, "Not found", "There is no such draft.", req) else web.render("Blueprint $name", req, editor(req.session!!, draft))
        }
        r.post("/blueprints/{name}/check", Permission.READ) { req -> json(check(req.form)) }
        r.post("/blueprints/{name}/publish", Permission.OPERATE) { req ->
            var done: String? = null
            val error = attempt { done = publish(drafts.load("project", req.params.getValue("name")) ?: throw ManagementException(io.grpc.Status.Code.NOT_FOUND, "there is no such draft")) }
            fragment(html(notice(error), done?.let { d -> html(info(d), raw("<p><a href=\"/deployments\">Go to the deployments</a></p>")) } ?: Html("")))
        }
        r.post("/blueprints/{name}/save", Permission.OPERATE) { req ->
            var saved: Draft? = null
            var problems: List<PackageProblem> = emptyList()
            var blocks = 0
            var checked: Blueprint? = null
            val error = attempt {
                val name = req.params.getValue("name")
                val previous = drafts.load("project", name)
                val ranges = req.form["plugins"]?.let { parsePluginRanges(it) } ?: previous?.let { pluginRangesOf(it) }.orEmpty()
                val snapshot = catalog.snapshot(ranges)
                val before = previous?.let { blueprintOf(it) }
                val kept = before?.tethers.orEmpty().filterNot { BlueprintGraph.drawable(it) } + (before?.let { BlueprintGraph.unresolved(it, snapshot::block) }.orEmpty())
                val graph = parse(req.form["graph"]).jsonObject
                val blueprint = try {
                    BlueprintGraph.toBlueprint(name, graph, parse(req.form["options"]) as? JsonObject ?: JsonObject(emptyMap()), kept, parseProvides(req.form["provides"].orEmpty()), previous?.let { blueprintOf(it) }?.assertions.orEmpty(), previous?.let { blueprintOf(it) }?.blocks.orEmpty(), snapshot::block)
                } catch (e: GraphException) {
                    throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, e.message ?: "invalid graph")
                }
                blocks = blueprint.blocks.size
                checked = blueprint
                problems = PackageValidator.validateBlueprint(blueprint, snapshot::block, snapshot.registry, "blueprints/$name.json")
                val fabrics = req.form["fabrics"]?.let { parseFabrics(it) } ?: req.form["roles"]?.let { listOf(FabricLine(1, roles(it), emptyMap())) } ?: previous?.let { fabricsOf(it) } ?: listOf(FabricLine(1, emptyList(), emptyMap()))
                val schemas = req.form["schemas"]?.let { names(it) } ?: previous?.let { schemasOf(it) }.orEmpty()
                saved = drafts.save("project", name, req.form["version"].orEmpty().trim().ifEmpty { "1.0.0" }, content(blueprint, BlueprintGraph.positions(graph), fabrics, schemas, ranges), req.form["revision"]?.toLongOrNull())
            }
            fragment(result(error, saved, problems, blocks, checked))
        }
    }

    /** What a new project draft contains. */
    fun emptyContent(name: String): JsonElement = content(Blueprint(name, emptyList(), emptyList()), emptyMap(), listOf(FabricLine(1, emptyList(), emptyMap())), emptyList(), emptyMap())

    /** One fabric of the project: [instances] copies of the blueprint on the engines that have all [roles] and [labels]. */
    private data class FabricLine(val instances: Int, val roles: List<String>, val labels: Map<String, String>)

    private fun content(blueprint: Blueprint, positions: Map<String, Pair<Double, Double>>, fabrics: List<FabricLine>, schemas: List<String>, plugins: Map<String, String>): JsonElement = buildJsonObject {
        put("blueprint", Json.parseToJsonElement(ManifestJson.encode(blueprint)))
        put(
            "fabrics",
            JsonArray(
                fabrics.map { f ->
                    buildJsonObject {
                        put("instances", f.instances)
                        put("roles", JsonArray(f.roles.map { JsonPrimitive(it) }))
                        put("labels", JsonObject(f.labels.mapValues { JsonPrimitive(it.value) }))
                    }
                },
            ),
        )
        put("schemas", JsonArray(schemas.map { JsonPrimitive(it) }))
        put("plugins", JsonObject(plugins.mapValues { JsonPrimitive(it.value) }))
        put("ui", buildJsonObject { put("positions", JsonObject(positions.mapValues { (_, p) -> JsonArray(listOf(JsonPrimitive(p.first), JsonPrimitive(p.second))) })) })
    }

    internal fun blueprintOf(draft: Draft): Blueprint? = try {
        ManifestJson.parseBlueprint(draft.content.jsonObject.getValue("blueprint").toString(), "draft ${draft.name}")
    } catch (e: Exception) {
        null
    }

    /** The fabrics of a draft; a draft from before the fabrics has the list `roles` and so one fabric. */
    private fun fabricsOf(draft: Draft): List<FabricLine> {
        val o = draft.content.jsonObject
        o["fabrics"]?.jsonArray?.let { list ->
            return list.map { e ->
                val f = e.jsonObject
                FabricLine(
                    f["instances"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
                    f["roles"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                    f["labels"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                )
            }
        }
        return listOf(FabricLine(1, o["roles"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(), emptyMap()))
    }

    /** `plugin = range`, one per line; an empty range or a line that is only a name is left out; a range that is not valid is refused. */
    private fun parsePluginRanges(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val name = line.substringBefore('=').trim()
            val range = line.substringAfter('=', "").trim()
            if (range.isEmpty()) continue
            PackageNames.nameProblem(name)?.let { throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "plugin '$name': $it") }
            try {
                cringle.packaging.VersionRange.parse(range)
            } catch (e: IllegalArgumentException) {
                throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "the version range of '$name' is not valid: ${e.message}")
            }
            result[name] = range
        }
        return result
    }

    private fun pluginRangesOf(draft: Draft): Map<String, String> = draft.content.jsonObject["plugins"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()

    private fun schemasOf(draft: Draft): List<String> = draft.content.jsonObject["schemas"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun names(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** One fabric per line: `instances | roles | labels` (`2 | edge, gpu | zone=a, tier=1`); roles and labels are optional. */
    private fun parseFabrics(text: String): List<FabricLine> {
        fun bad(line: Int, message: String): Nothing = throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "fabric line $line: $message")
        val lines = text.lines().withIndex().filter { it.value.isNotBlank() }.map { (i, l) ->
            val parts = l.split('|').map { it.trim() }
            if (parts.size > 3) bad(i + 1, "write instances | roles | labels, not more than three parts")
            val instances = parts[0].toIntOrNull()?.takeIf { it >= 1 } ?: bad(i + 1, "the number of instances is a whole number of at least 1, not '${parts[0]}'")
            val labels = names(parts.getOrNull(2).orEmpty()).associate { pair ->
                if ('=' !in pair || pair.startsWith("=")) bad(i + 1, "a label is written key=value, not '$pair'")
                pair.substringBefore('=').trim() to pair.substringAfter('=').trim()
            }
            FabricLine(instances, names(parts.getOrNull(1).orEmpty()), labels)
        }
        if (lines.isEmpty()) throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "a project needs at least one fabric: write at least 1 (one instance)")
        return lines
    }

    private fun fabricsText(fabrics: List<FabricLine>): String = fabrics.joinToString("\n") { f ->
        buildString {
            append(f.instances)
            if (f.roles.isNotEmpty() || f.labels.isNotEmpty()) append(" | ").append(f.roles.joinToString(", "))
            if (f.labels.isNotEmpty()) append(" | ").append(f.labels.entries.joinToString(", ") { "${it.key}=${it.value}" })
        }
    }

    private fun roles(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun parse(text: String?): JsonElement = try {
        Json.parseToJsonElement(text ?: throw GraphException("the graph is missing"))
    } catch (e: kotlinx.serialization.SerializationException) {
        throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "the graph is not valid JSON")
    }

    /** One `service=block.port` or `service=block.port:TYPE` per line. */
    private fun parseProvides(text: String): List<ProvidedService> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        val service = line.substringBefore('=').trim()
        val rest = line.substringAfter('=', "").trim()
        val target = rest.substringBefore(':')
        if (service.isEmpty() || '.' !in target) throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "a provided service is written service=block.port (optionally :TYPE), not '$line'")
        val type = rest.substringAfter(':', "").trim().takeIf { it.isNotEmpty() }?.let { t ->
            TetherType.entries.firstOrNull { it.name == t.uppercase() } ?: throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "unknown tether type '$t'")
        }
        ProvidedService(service, target.substringBefore('.'), target.substringAfter('.'), type)
    }

    // --- the verdict on a connection ---

    private fun json(value: JsonObject): WebResponse = WebResponse(200, value.toString().toByteArray(), "application/json")

    /** The counts of the VarArg ports of a node as the browser sends them (`{"port": 2}`); a malformed value counts as none. */
    private fun counts(text: String?): Map<String, Int> = try {
        (Json.parseToJsonElement(text ?: "{}") as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.toIntOrNull()?.let { k to it } }.toMap()
    } catch (e: kotlinx.serialization.SerializationException) {
        emptyMap()
    }

    private fun types(text: String?): Map<String, cringle.contract.SchemaRef> = try {
        (Json.parseToJsonElement(text ?: "{}") as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { s -> runCatching { cringle.contract.SchemaRef.parse(s) }.getOrNull()?.let { k to it } } }.toMap()
    } catch (e: kotlinx.serialization.SerializationException) {
        emptyMap()
    }

    /** A connection between a local port and an external end (another engine, a service): the local port has to support the tether type of the end. */
    private fun checkExternal(snapshot: PluginCatalog.Snapshot, form: Map<String, String>): JsonObject {
        fun no(message: String) = buildJsonObject { put("ok", false); put("message", message) }
        if (form["fromExternal"] != null && form["toExternal"] != null) return no("two external ends cannot be connected to each other")
        val fromExternal = form["fromExternal"] != null
        val typeName = form["fromExternal"] ?: form["toExternal"].orEmpty()
        val type = TetherType.entries.firstOrNull { it.name == typeName } ?: return no("the external end has no tether type")
        if (type !in setOf(TetherType.MESSAGE, TetherType.REQUEST_RESPONSE, TetherType.STREAM, TetherType.BYTE_STREAM)) {
            return no("a tether to another engine or to a service is MESSAGE, REQUEST_RESPONSE, STREAM or BYTE_STREAM, not $type")
        }
        val blockType = (if (fromExternal) form["toBlock"] else form["fromBlock"]).orEmpty()
        val definition = snapshot.block(blockType) ?: return no("unknown block '$blockType'")
        val direction = if (fromExternal) cringle.contract.PortDirection.IN else cringle.contract.PortDirection.OUT
        val counts = BlueprintGraph.countsFor(definition, counts(if (fromExternal) form["toCounts"] else form["fromCounts"]))
        val number = ((if (fromExternal) form["toInput"] else form["fromOutput"])?.toIntOrNull() ?: 0) - 1
        val slot = BlueprintGraph.slots(definition, direction, counts).getOrNull(number) ?: return no("'$blockType' has no such ${if (fromExternal) "input" else "output"}")
        if (type !in slot.port.tetherTypes) return no("'${slot.port.name}' supports ${slot.port.tetherTypes.joinToString()}, not $type")
        return buildJsonObject { put("ok", true); put("type", type.name) }
    }

    private suspend fun check(form: Map<String, String>): JsonObject {
        fun no(message: String) = buildJsonObject { put("ok", false); put("message", message) }
        val snapshot = try {
            catalog.snapshot(runCatching { parsePluginRanges(form["plugins"].orEmpty()) }.getOrDefault(emptyMap()))
        } catch (e: Exception) {
            return no(describe(e))
        }
        if (form["fromExternal"] != null || form["toExternal"] != null) return checkExternal(snapshot, form)
        val fromType = form["fromBlock"].orEmpty()
        val toType = form["toBlock"].orEmpty()
        val from = snapshot.block(fromType) ?: return no("unknown block '$fromType'")
        val to = snapshot.block(toType) ?: return no("unknown block '$toType'")
        val fromCounts = BlueprintGraph.countsFor(from, counts(form["fromCounts"]))
        val toCounts = BlueprintGraph.countsFor(to, counts(form["toCounts"]))
        val outSlot = BlueprintGraph.slots(from, cringle.contract.PortDirection.OUT, fromCounts).getOrNull((form["fromOutput"]?.toIntOrNull() ?: 0) - 1) ?: return no("'$fromType' has no such output")
        val inSlot = BlueprintGraph.slots(to, cringle.contract.PortDirection.IN, toCounts).getOrNull((form["toInput"]?.toIntOrNull() ?: 0) - 1) ?: return no("'$toType' has no such input")
        val out = outSlot.port
        val input = inSlot.port
        val type = BlueprintGraph.commonType(out, input)
            ?: return no("'${out.name}' (${out.tetherTypes.joinToString()}) and '${input.name}' (${input.tetherTypes.joinToString()}) share no tether type")
        // the verdict of the same validator that the package build and the deploy use, for this one tether only
        val probe = Blueprint(
            "probe",
            listOf(
                BlueprintBlock("a", fromType, varArgCounts = fromCounts, types = types(form["fromTypes"])),
                BlueprintBlock("b", toType, varArgCounts = toCounts, types = types(form["toTypes"])),
            ),
            listOf(TetherDef(type, Endpoint("a", out.name, outSlot.index), Endpoint("b", input.name, inSlot.index))),
        )
        val problems = PackageValidator.validateBlueprint(probe, snapshot::block, snapshot.registry, "probe").filter { it.path.contains("$.tethers[") }
        if (problems.isNotEmpty()) return no(problems.joinToString("; ") { it.message })
        return buildJsonObject { put("ok", true); put("type", type.name) }
    }

    // --- for the MCP server (McpTools) ---

    /** Every block of the plugins in the repository (each plugin in its highest version) as `plugin@version` and its definition. */
    internal suspend fun catalogBlocks(): List<Triple<String, String, BlockDefinition>> =
        catalog.snapshot().plugins.flatMap { p -> p.manifest.blocks.map { Triple(p.manifest.name, p.manifest.version, it) } }

    /** The problems that the validators find in [blueprint] (the check of the editor and of publishing). */
    internal suspend fun checkBlueprint(blueprint: Blueprint): List<PackageProblem> {
        val snapshot = catalog.snapshot()
        return PackageValidator.validateBlueprint(blueprint, snapshot::block, snapshot.registry, "blueprints/${blueprint.name}.json")
    }

    /**
     * Stores [blueprint] as the blueprint of the project draft [name] (fabrics, schemas and positions of the draft stay) and returns the draft with the problems of
     * the check; a blueprint with problems is stored as work in progress, as by the editor.
     */
    internal suspend fun saveBlueprint(name: String, blueprint: Blueprint, version: String?): Pair<Draft, List<PackageProblem>> {
        val previous = drafts.load("project", name) ?: throw DraftException("there is no project '$name'")
        val named = blueprint.copy(name = name)
        val problems = checkBlueprint(named)
        val positions = previous.content.jsonObject["ui"]?.jsonObject?.get("positions")?.jsonObject.orEmpty()
            .mapValues { (_, p) -> p.jsonArray[0].jsonPrimitive.content.toDouble() to p.jsonArray[1].jsonPrimitive.content.toDouble() }
        val saved = drafts.save("project", name, version?.trim()?.ifEmpty { null } ?: previous.version, content(named, positions, fabricsOf(previous), schemasOf(previous), pluginRangesOf(previous)), null)
        return saved to problems
    }

    // --- publishing ---

    /** Builds, validates and publishes the project package of the draft; used by the publish route of the drafts. */
    suspend fun publish(draft: Draft): String {
        val blueprint = blueprintOf(draft) ?: throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "the draft holds no blueprint")
        if (blueprint.blocks.isEmpty()) throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "the blueprint has no block")
        val ranges = pluginRangesOf(draft)
        val snapshot = catalog.snapshot(ranges)
        val used = blueprint.blocks.map { it.block.substringBefore('/') }.distinct()
        val plugins = used.map { name -> snapshot.plugins.firstOrNull { it.manifest.name == name } ?: throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "the plugin '$name' is not in the repository") }
        snapshot.unmatched.filter { it in used }.firstOrNull()?.let { throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "no version of the plugin '$it' satisfies ${ranges[it]}") }
        val schemas = LinkedHashMap<String, String>()
        for (schemaName in schemasOf(draft)) {
            val schemaDraft = drafts.load("schema", schemaName) ?: throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "the schema draft '$schemaName' of this project does not exist")
            val text = pretty.encodeToString(JsonObject.serializer(), schemaDraft.content.jsonObject)
            val document = try {
                SchemaParser.parse(text, "draft $schemaName")
            } catch (e: SchemaParseException) {
                throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "the schema draft '$schemaName': ${e.message}")
            }
            if (document.types.isEmpty()) throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "the schema draft '$schemaName' defines no type")
            if (schemas.put("schemas/${document.namespace}.json", text) != null) throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "two schema drafts have the namespace ${document.namespace}")
        }
        val manifest = ProjectManifest(
            draft.name, draft.version, plugins.associate { it.manifest.name to (ranges[it.manifest.name] ?: "^${it.manifest.version}") },
            listOf("blueprints/${blueprint.name}.json"), schemas.keys.toList(), fabricsOf(draft).map { FabricConfig(blueprint.name, it.instances, it.roles, it.labels) },
        )
        val project = ProjectPackage(manifest, listOf(blueprint), schemas, emptyList())
        val problems = PackageValidator.validateProjectSources(project) + PackageValidator.validateProject(project, plugins)
        if (problems.isNotEmpty()) throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, problems.joinToString("; ") { "${it.path}: ${it.message}" })
        val bytes = ByteArrayOutputStream().also { PackageWriter.writeProject(manifest, listOf(blueprint), schemas, files.all(draft.name), it) }.toByteArray()
        val m = publishPackage(core, bytes)
        return "Published ${m.name} ${m.version}; deploy it on the deployments page"
    }

    // --- the page ---

    /** The plugins of the repository with the version that the editor uses and the versions there are. */
    private fun pluginList(snapshot: PluginCatalog.Snapshot, ranges: Map<String, String>): Html = html(
        raw("<ul class=\"plugin-versions\">"),
        snapshot.plugins.map { p ->
            val name = p.manifest.name
            html(
                h("<li><strong>{}</strong> uses {}; available: {}", name, p.manifest.version, snapshot.versions[name].orEmpty().joinToString(", ")),
                if (name in snapshot.unmatched) h(" <span class=\"problem\">no version satisfies {}, the highest is used</span>", ranges[name]) else Html(""),
                raw("</li>"),
            )
        },
        raw("</ul>"),
    )

    private fun portJson(p: cringle.contract.PortDefinition) = buildJsonObject {
        put("name", p.name)
        put("varArg", p.varArg)
        put("schema", "${p.schema.namespace}/${p.schema.name}")
        p.typeParam?.let { put("typeParam", it) }
    }

    private suspend fun editor(session: Session, draft: Draft): Html {
        val ranges = pluginRangesOf(draft)
        val snapshot = try {
            catalog.snapshot(ranges)
        } catch (e: Exception) {
            return html(pageHeader("Blueprint ${draft.name}", "", raw("<a class=\"btn\" href=\"/drafts\">All drafts</a>")), notice(describe(e)))
        }
        val blueprint = blueprintOf(draft) ?: Blueprint(draft.name, emptyList(), emptyList())
        val positions = draft.content.jsonObject["ui"]?.jsonObject?.get("positions")?.jsonObject.orEmpty().mapValues { (_, p) -> p.jsonArray[0].jsonPrimitive.content.toDouble() to p.jsonArray[1].jsonPrimitive.content.toDouble() }
        val palette = buildJsonArray {
            for (plugin in snapshot.plugins) {
                for (b in plugin.manifest.blocks) {
                    add(
                        buildJsonObject {
                            put("block", "${plugin.manifest.name}/${b.name}")
                            put("inputs", JsonArray(BlueprintGraph.inputs(b).map { portJson(it) }))
                            put("outputs", JsonArray(BlueprintGraph.outputs(b).map { portJson(it) }))
                            put("typeParameters", JsonArray(b.typeParameters.map { t -> buildJsonObject { put("name", t.name); put("bound", "${t.bound.namespace}/${t.bound.name}") } }))
                            put("title", b.name)
                            put("varArg", b.ports.any { it.varArg })
                            put("config", b.configSchema?.let { "${it.namespace}/${it.name}" }.orEmpty())
                            put("configFields", ConfigFields.of(b, snapshot.registry))
                        },
                    )
                }
            }
        }
        val base = "/blueprints/${draft.name}"
        val canSave = session.canAnywhere(Permission.OPERATE)
        val fabrics = fabricsText(fabricsOf(draft))
        val chosen = schemasOf(draft).toSet()
        val schemaDrafts = drafts.list("schema")
        val schemaBox = if (schemaDrafts.isEmpty()) {
            raw("<p class=\"empty\">No schema draft yet. Write one on <a href=\"/drafts\">Drafts</a>; a project can carry the schemas it needs.</p>")
        } else {
            html(
                schemaDrafts.map { d -> h("<label class=\"check\"><input type=\"checkbox\" class=\"schema-pick\" value=\"{}\"{}> {}</label>", d.name, if (d.name in chosen) raw(" checked") else Html(""), d.name) },
            )
        }
        val provides = blueprint.provides.joinToString("\n") { "${it.service}=${it.block}.${it.port}" + (it.type?.let { t -> ":$t" } ?: "") }
        val graph = BlueprintGraph.toGraph(blueprint, positions, snapshot::block)
        val kept = blueprint.tethers.count { !BlueprintGraph.drawable(it) } + BlueprintGraph.unresolved(blueprint, snapshot::block).size
        val paletteGroups = snapshot.plugins.filter { it.manifest.blocks.isNotEmpty() }.map { p ->
            // the blocks of a plugin are folded by the theme before the first dot of their name (`time.timer-trigger`: `time`)
            fun button(b: BlockDefinition) = h("<button type=\"button\" data-block=\"{}/{}\" title=\"{}/{}\">{}</button>", p.manifest.name, b.name, p.manifest.name, b.name, b.name)
            html(
                raw("<section class=\"plugin\">"),
                h("<h3>{}</h3>", p.manifest.name),
                p.manifest.blocks.groupBy { it.name.substringBefore('.', "") }.map { (theme, blocks) ->
                    if (theme.isEmpty()) {
                        html(blocks.map { button(it) })
                    } else {
                        html(h("<details class=\"theme\"><summary>{} <span class=\"count\">{}</span></summary>", theme, blocks.size), blocks.map { button(it) }, raw("</details>"))
                    }
                },
                raw("</section>"),
            )
        }
        val paletteBody = if (paletteGroups.isEmpty()) {
            raw("<p class=\"empty\">No plugin with blocks is in the repository yet. Publish a plugin with <code>cringlePublish</code>; it is then listed on <a href=\"/packages\">Packages</a>.</p>")
        } else {
            html(raw("<input type=\"search\" id=\"palette-search\" placeholder=\"Search blocks\" aria-label=\"Search blocks\">"), paletteGroups)
        }
        return html(
            h(
                "<div id=\"blueprint-editor\" data-palette=\"{}\" data-schemas=\"{}\" data-graph=\"{}\" data-options=\"{}\" data-check-url=\"{}/check\" data-save-url=\"{}/save\" data-publish-url=\"{}/publish\">",
                palette.toString(), buildJsonArray { snapshot.registry.types().forEach { add(JsonPrimitive("${it.namespace}/${it.name}")) } }.toString(), graph.toString(), BlueprintGraph.options(blueprint).toString(), base, base, base,
            ),
            raw("<div id=\"canvas\"><div id=\"drawflow\"></div><p id=\"canvas-hint\">Add a block from the palette, then drag from an output to an input.</p></div>"),
            raw("<div id=\"editor-left\"><header id=\"editor-head\" class=\"float\">"),
            h("<h1>Blueprint {}</h1>", draft.name),
            raw("<p class=\"subtitle\">Add blocks from the palette, connect an output to an input; the server checks every connection.</p>"),
            if (kept > 0) h("<p class=\"info\">{} tethers cannot be shown in the editor; they are kept as they are when you save.</p>", kept) else Html(""),
            raw("<div class=\"page-actions\"><a class=\"btn small\" href=\"/drafts\">All drafts</a><a class=\"btn small\" href=\"/deployments\">Deployments</a></div></header>"),
            raw("<aside id=\"palette\" class=\"float\"><h2>Blocks</h2>"),
            paletteBody,
            raw(
                "<div id=\"externals\"><h3>Other engines and services</h3>" +
                    "<button type=\"button\" data-external=\"remote-receive\" title=\"A port on another engine that receives what a local port sends\">Remote end that receives</button>" +
                    "<button type=\"button\" data-external=\"remote-send\" title=\"A port on another engine that sends to a local port\">Remote end that sends</button>" +
                    "<button type=\"button\" data-external=\"service\" title=\"A service of another project that a local port calls\">Service</button></div>",
            ),
            raw("</aside></div>"),
            raw("<div id=\"editor-bar\" class=\"float\">"),
            field("Version", h("<input id=\"version\" value=\"{}\" size=\"10\">", draft.version)),
            raw("<details id=\"fabrics-box\"><summary>Fabrics</summary><div class=\"dropdown\">"),
            h("<textarea id=\"fabrics\" rows=\"3\" cols=\"44\" placeholder=\"2 | edge, gpu | zone=a\">{}</textarea>", fabrics),
            raw("<small>one fabric per line: instances | roles | labels (roles and labels are optional)</small></div></details>"),
            raw("<details id=\"schemas-box\"><summary>Schemas of the project</summary><div class=\"dropdown\">"),
            schemaBox,
            raw("</div></details>"),
            raw("<details id=\"plugins-box\"><summary>Plugins</summary><div class=\"dropdown\">"),
            h("<textarea id=\"plugins\" rows=\"3\" cols=\"44\" placeholder=\"cringle-std = ~1.9.0\">{}</textarea>", ranges.entries.joinToString("\n") { "${it.key} = ${it.value}" }),
            raw("<small>plugin = version range (<code>^1.9.0</code>, <code>~1.9.0</code>, <code>1.9.0</code>), one per line. A plugin without a line is used in its highest version.</small>"),
            pluginList(snapshot, ranges),
            raw("</div></details>"),
            raw("<details id=\"services-box\"><summary>Provided services</summary><div class=\"dropdown\">"),
            h("<textarea id=\"provides\" rows=\"3\" cols=\"44\" placeholder=\"orders=store.in\">{}</textarea>", provides),
            raw("<small>service=block.port, one per line</small></div></details>"),
            h("<input type=\"hidden\" id=\"revision\" value=\"{}\">", draft.revision),
            raw("<div class=\"btn-group\" role=\"group\" aria-label=\"View\"><button type=\"button\" class=\"btn\" id=\"undo\" title=\"Undo\">Undo</button><button type=\"button\" class=\"btn\" id=\"redo\" title=\"Redo\">Redo</button><button type=\"button\" class=\"btn\" id=\"zoom-out\" title=\"Zoom out\">&minus;</button><button type=\"button\" class=\"btn\" id=\"zoom-in\" title=\"Zoom in\">+</button><button type=\"button\" class=\"btn\" id=\"zoom-fit\" title=\"Reset the zoom\">Fit</button></div>"),
            if (canSave) raw("<label class=\"check\"><input type=\"checkbox\" id=\"autosave\" checked> Autosave</label><button type=\"button\" class=\"btn primary\" id=\"save-blueprint\">Save draft</button><button type=\"button\" class=\"btn\" id=\"publish-blueprint\" title=\"Save, then publish as a package\">Publish</button>") else Html(""),
            raw("</div>"),
            raw("<aside id=\"properties\" class=\"float\"><h2>Properties</h2><div id=\"node-properties\"><p>Select a block or a connection.</p></div></aside>"),
            raw("<div id=\"result\" class=\"float\" role=\"status\"></div></div>"),
        )
    }

    private fun result(error: String?, saved: Draft?, problems: List<PackageProblem>, blocks: Int, blueprint: Blueprint?): Html = html(
        notice(error),
        if (saved != null) html(info("Saved as revision ${saved.revision}."), h("<input type=\"hidden\" id=\"revision\" value=\"{}\" hx-swap-oob=\"true\">", saved.revision)) else Html(""),
        if (error == null && problems.isEmpty()) info(if (blocks == 0) "The blueprint has no block yet; add one to be able to publish it." else "The blueprint is valid.") else Html(""),
        if (problems.isNotEmpty()) {
            val described = if (blueprint != null) BlueprintProblems.describe(problems, blueprint) else problems.map { DescribedProblem(it.message, emptyList(), it.path) }
            html(
                raw("<p class=\"error\">The blueprint has problems (click one to go to the block):</p><ul id=\"problems\">"),
                described.map {
                    if (it.blocks.isEmpty()) {
                        h("<li><code>{}</code> {}</li>", it.path, it.text)
                    } else {
                        h("<li><button type=\"button\" class=\"problem\" data-blocks=\"{}\">{}</button></li>", it.blocks.joinToString(","), it.text)
                    }
                },
                raw("</ul>"),
            )
        } else {
            raw("<span id=\"problems-none\"></span>")
        },
    )
}
