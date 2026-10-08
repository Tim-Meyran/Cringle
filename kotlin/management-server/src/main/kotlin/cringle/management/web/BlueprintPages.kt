// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.BlockDefinition
import cringle.management.ManagementCore
import cringle.management.ManagementException
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.Endpoint
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
internal class BlueprintPages(private val core: ManagementCore, private val drafts: DraftStore) {
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
        r.post("/blueprints/{name}/save", Permission.OPERATE) { req ->
            var saved: Draft? = null
            var problems: List<PackageProblem> = emptyList()
            val error = attempt {
                val name = req.params.getValue("name")
                val snapshot = catalog.snapshot()
                val previous = drafts.load("project", name)
                val kept = previous?.let { blueprintOf(it) }?.tethers.orEmpty().filterNot { BlueprintGraph.drawable(it) }
                val graph = parse(req.form["graph"]).jsonObject
                val blueprint = try {
                    BlueprintGraph.toBlueprint(name, graph, parse(req.form["options"]) as? JsonObject ?: JsonObject(emptyMap()), kept, parseProvides(req.form["provides"].orEmpty()), snapshot::block)
                } catch (e: GraphException) {
                    throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, e.message ?: "invalid graph")
                }
                problems = PackageValidator.validateBlueprint(blueprint, snapshot::block, snapshot.registry, "blueprints/$name.json")
                saved = drafts.save("project", name, req.form["version"].orEmpty().trim().ifEmpty { "1.0.0" }, content(blueprint, BlueprintGraph.positions(graph), roles(req.form["roles"].orEmpty())), req.form["revision"]?.toLongOrNull())
            }
            fragment(result(error, saved, problems))
        }
    }

    /** What a new project draft contains. */
    fun emptyContent(name: String): JsonElement = content(Blueprint(name, emptyList(), emptyList()), emptyMap(), emptyList())

    private fun content(blueprint: Blueprint, positions: Map<String, Pair<Double, Double>>, roles: List<String>): JsonElement = buildJsonObject {
        put("blueprint", Json.parseToJsonElement(ManifestJson.encode(blueprint)))
        put("roles", JsonArray(roles.map { JsonPrimitive(it) }))
        put("ui", buildJsonObject { put("positions", JsonObject(positions.mapValues { (_, p) -> JsonArray(listOf(JsonPrimitive(p.first), JsonPrimitive(p.second))) })) })
    }

    private fun blueprintOf(draft: Draft): Blueprint? = try {
        ManifestJson.parseBlueprint(draft.content.jsonObject.getValue("blueprint").toString(), "draft ${draft.name}")
    } catch (e: Exception) {
        null
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

    private suspend fun check(form: Map<String, String>): JsonObject {
        fun no(message: String) = buildJsonObject { put("ok", false); put("message", message) }
        val snapshot = try {
            catalog.snapshot()
        } catch (e: Exception) {
            return no(describe(e))
        }
        val fromType = form["fromBlock"].orEmpty()
        val toType = form["toBlock"].orEmpty()
        val from = snapshot.block(fromType) ?: return no("unknown block '$fromType'")
        val to = snapshot.block(toType) ?: return no("unknown block '$toType'")
        val out = BlueprintGraph.outputs(from).getOrNull((form["fromOutput"]?.toIntOrNull() ?: 0) - 1) ?: return no("'$fromType' has no such output")
        val input = BlueprintGraph.inputs(to).getOrNull((form["toInput"]?.toIntOrNull() ?: 0) - 1) ?: return no("'$toType' has no such input")
        if (out.varArg || input.varArg) return no("VarArg ports cannot be connected in the editor yet")
        val type = BlueprintGraph.commonType(out, input)
            ?: return no("'${out.name}' (${out.tetherTypes.joinToString()}) and '${input.name}' (${input.tetherTypes.joinToString()}) share no tether type")
        // the verdict of the same validator that the package build and the deploy use, for this one tether only
        val probe = Blueprint("probe", listOf(BlueprintBlock("a", fromType), BlueprintBlock("b", toType)), listOf(TetherDef(type, Endpoint("a", out.name), Endpoint("b", input.name))))
        val problems = PackageValidator.validateBlueprint(probe, snapshot::block, snapshot.registry, "probe").filter { it.path.contains("$.tethers[") }
        if (problems.isNotEmpty()) return no(problems.joinToString("; ") { it.message })
        return buildJsonObject { put("ok", true); put("type", type.name) }
    }

    // --- publishing ---

    /** Builds, validates and publishes the project package of the draft; used by the publish route of the drafts. */
    suspend fun publish(draft: Draft): String {
        val blueprint = blueprintOf(draft) ?: throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "the draft holds no blueprint")
        if (blueprint.blocks.isEmpty()) throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "the blueprint has no block")
        val snapshot = catalog.snapshot()
        val used = blueprint.blocks.map { it.block.substringBefore('/') }.distinct()
        val plugins = used.map { name -> snapshot.plugins.firstOrNull { it.manifest.name == name } ?: throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "the plugin '$name' is not in the repository") }
        val roles = draft.content.jsonObject["roles"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val manifest = ProjectManifest(
            draft.name, draft.version, plugins.associate { it.manifest.name to "^${it.manifest.version}" },
            listOf("blueprints/${blueprint.name}.json"), emptyList(), listOf(FabricConfig(blueprint.name, 1, roles, emptyMap())),
        )
        val project = ProjectPackage(manifest, listOf(blueprint), emptyMap(), emptyList())
        val problems = PackageValidator.validateProjectSources(project) + PackageValidator.validateProject(project, plugins)
        if (problems.isNotEmpty()) throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, problems.joinToString("; ") { "${it.path}: ${it.message}" })
        val bytes = ByteArrayOutputStream().also { PackageWriter.writeProject(manifest, listOf(blueprint), emptyMap(), emptyMap(), it) }.toByteArray()
        val m = publishPackage(core, bytes)
        return "Published ${m.name} ${m.version}; deploy it on the deployments page"
    }

    // --- the page ---

    private suspend fun editor(session: Session, draft: Draft): Html {
        val snapshot = try {
            catalog.snapshot()
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
                            put("inputs", JsonArray(BlueprintGraph.inputs(b).map { JsonPrimitive(it.name) }))
                            put("outputs", JsonArray(BlueprintGraph.outputs(b).map { JsonPrimitive(it.name) }))
                            put("title", b.name)
                            put("varArg", b.ports.any { it.varArg })
                            put("config", b.configSchema?.let { "${it.namespace}/${it.name}" }.orEmpty())
                        },
                    )
                }
            }
        }
        val base = "/blueprints/${draft.name}"
        val canSave = session.can(Permission.OPERATE)
        val roles = draft.content.jsonObject["roles"]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content }.orEmpty()
        val provides = blueprint.provides.joinToString("\n") { "${it.service}=${it.block}.${it.port}" + (it.type?.let { t -> ":$t" } ?: "") }
        val graph = BlueprintGraph.toGraph(blueprint, positions, snapshot::block)
        val kept = blueprint.tethers.count { !BlueprintGraph.drawable(it) }
        return html(
            pageHeader("Blueprint ${draft.name}", "Add blocks from the left, connect an output to an input; the server checks every connection.", raw("<a class=\"btn\" href=\"/drafts\">All drafts</a><a class=\"btn\" href=\"/deployments\">Deployments</a>")),
            if (kept > 0) h("<p class=\"info\">{} tethers (to other engines or services) are not drawn; they are kept as they are when you save.</p>", kept) else Html(""),
            h(
                "<div id=\"blueprint-editor\" data-palette=\"{}\" data-graph=\"{}\" data-options=\"{}\" data-check-url=\"{}/check\" data-save-url=\"{}/save\">",
                palette.toString(), graph.toString(), BlueprintGraph.options(blueprint).toString(), base, base,
            ),
            raw("<aside id=\"palette\"><h2>Blocks</h2>"),
            snapshot.plugins.flatMap { p -> p.manifest.blocks.map { b -> h("<button type=\"button\" data-block=\"{}/{}\" title=\"{}\">{}</button>", p.manifest.name, b.name, p.manifest.name, b.name) } },
            raw("</aside><div id=\"drawflow\"></div><aside id=\"properties\"><h2>Properties</h2><div id=\"node-properties\"><p>Select a block or a connection.</p></div></aside></div>"),
            raw("<div id=\"blueprint-meta\">"),
            field("Version", h("<input id=\"version\" value=\"{}\" size=\"10\">", draft.version)),
            field("Roles of the fabric", h("<input id=\"roles\" value=\"{}\" placeholder=\"role, role\">", roles), "engines with all these roles run it"),
            field("Provided services", h("<textarea id=\"provides\" rows=\"3\" cols=\"44\" placeholder=\"orders=store.in\">{}</textarea>", provides), "service=block.port, one per line"),
            h("<input type=\"hidden\" id=\"revision\" value=\"{}\">", draft.revision),
            if (canSave) raw("<button type=\"button\" class=\"btn primary\" id=\"save-blueprint\">Save draft</button>") else Html(""),
            raw("</div><div id=\"result\"></div>"),
        )
    }

    private fun result(error: String?, saved: Draft?, problems: List<PackageProblem>): Html = html(
        notice(error),
        if (saved != null) html(info("Saved as revision ${saved.revision}."), h("<input type=\"hidden\" id=\"revision\" value=\"{}\" hx-swap-oob=\"true\">", saved.revision)) else Html(""),
        if (error == null && problems.isEmpty()) info("The blueprint is valid.") else Html(""),
        if (problems.isNotEmpty()) html(raw("<p class=\"error\">The blueprint has problems:</p><ul>"), problems.map { h("<li><code>{}</code> {}</li>", it.path, it.message) }, raw("</ul>")) else Html(""),
    )
}
