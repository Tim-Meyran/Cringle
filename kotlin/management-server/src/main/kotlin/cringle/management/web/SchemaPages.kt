// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.management.ManagementCore
import cringle.management.ManagementException
import cringle.packaging.PackageValidator
import cringle.packaging.PackageWriter
import cringle.packaging.PluginManifest
import cringle.packaging.PluginPackage
import cringle.router.users.Permission
import cringle.schema.SchemaParseException
import cringle.schema.SchemaParser
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The drafts and the schema editor (#212). A draft is kept by the ManagementServer ([DraftStore]); a schema draft is published as a plugin package that
 * carries the schema document and nothing else. The editor is a form (Alpine.js holds the rows); the server turns the form into the document ([SchemaForm]),
 * validates it with the parser of the schema module and shows the document and the problems with their path.
 */
internal class SchemaPages(private val core: ManagementCore, private val drafts: DraftStore, private val blueprints: BlueprintPages) {
    private val pretty = Json { prettyPrint = true }

    fun register(web: WebServer) {
        val r = web.router
        web.navigation += NavItem("Drafts", "/drafts", group = "Build")

        r.get("/drafts", Permission.READ) { web.render("Drafts", it, section("Drafts", "Work in progress: schemas and blueprints you build here. A draft is published as a package when it is ready.", "drafts", list(it.session!!, null, null))) }
        r.get("/drafts/list", Permission.READ) { fragment(list(it.session!!, null, null)) }
        r.post("/drafts", Permission.OPERATE) { req ->
            val error = attempt {
                val kind = req.form["kind"].orEmpty()
                val name = req.form["name"].orEmpty().trim()
                if (drafts.load(kind, name) != null) throw ManagementException(io.grpc.Status.Code.ALREADY_EXISTS, "the $kind draft '$name' exists")
                drafts.save(kind, name, "1.0.0", if (kind == "schema") SchemaForm.toDocument("""{"namespace": "", "types": []}""") else blueprints.emptyContent(name))
            }
            fragment(list(req.session!!, error, null))
        }
        r.post("/drafts/{kind}/{name}/delete", Permission.OPERATE) { req ->
            fragment(list(req.session!!, attempt { drafts.delete(req.params.getValue("kind"), req.params.getValue("name")) }, null))
        }
        r.post("/drafts/{kind}/{name}/publish", Permission.OPERATE) { req ->
            var done: String? = null
            val error = attempt { done = publish(req.params.getValue("kind"), req.params.getValue("name")) }
            fragment(list(req.session!!, error, done))
        }

        r.get("/schemas/{name}", Permission.READ) { req ->
            val name = req.params.getValue("name")
            val draft = try {
                drafts.load("schema", name)
            } catch (e: DraftException) {
                null
            }
            if (draft == null) {
                web.problem(404, "Not found", "There is no such draft.", req)
            } else {
                web.render("Schema $name", req, editor(req.session!!, draft))
            }
        }
        // no side effect: shows the document and its problems
        r.post("/schemas/{name}/check", Permission.READ) { req -> fragment(preview(req.form["model"].orEmpty(), null, null)) }
        r.post("/schemas/{name}/save", Permission.OPERATE) { req ->
            val name = req.params.getValue("name")
            var saved: Draft? = null
            val error = attempt {
                val document = SchemaForm.toDocument(req.form["model"].orEmpty())
                saved = drafts.save("schema", name, req.form["version"].orEmpty().trim().ifEmpty { "1.0.0" }, document, req.form["revision"]?.toLongOrNull())
            }
            fragment(preview(req.form["model"].orEmpty(), error, saved))
        }
    }

    // --- drafts ---

    private fun list(session: Session, error: String?, done: String?): Html {
        val rows = drafts.list().map { d ->
            val base = "/drafts/${d.kind}/${d.name}"
            h(
                "<tr><td>{}</td><td><strong>{}</strong></td><td>{}</td><td class=\"num\">{}</td>{}</tr>",
                badge(if (d.kind == "schema") "schema" else "blueprint", if (d.kind == "schema") Tone.INFO else Tone.NEUTRAL), d.name, d.version, d.revision,
                actionsCell(
                    h("<a class=\"btn small\" href=\"/{}/{}\">{}</a>", if (d.kind == "schema") "schemas" else "blueprints", d.name, if (session.can(Permission.OPERATE)) "Edit" else "Open"),
                    button(session, Permission.OPERATE, "Publish", "$base/publish", "Publish ${d.kind} ${d.name} ${d.version} to the repository?"),
                    button(session, Permission.OPERATE, "Delete", "$base/delete", "Delete the draft ${d.name}?"),
                ),
            )
        }
        val form = if (!session.can(Permission.OPERATE)) {
            Html("")
        } else {
            h(
                "<form class=\"form-row\" hx-post=\"/drafts\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}{}<button class=\"btn primary\">Create draft</button></form>",
                field("What", raw("<select name=\"kind\"><option value=\"schema\">Schema</option><option value=\"project\">Project with a blueprint</option></select>")),
                field("Name", raw("<input name=\"name\" placeholder=\"acme-orders\" required>"), "lower case letters, digits, - and ."),
            )
        }
        return html(
            notice(error), info(done),
            dataTable(listOf("Kind", "Name", "Version", "Revision", ""), rows, raw("No draft yet. Create a schema for your data types or a project with a blueprint below.")),
            formPanel("Create a draft", "The editors open from the list. Nothing reaches the repository before you publish.", form),
        )
    }

    private suspend fun publish(kind: String, name: String): String {
        val draft = drafts.load(kind, name) ?: throw ManagementException(io.grpc.Status.Code.NOT_FOUND, "no $kind draft '$name'")
        if (kind == "project") return blueprints.publish(draft)
        val text = pretty.encodeToString(JsonObject.serializer(), draft.content.jsonObject)
        val document = try {
            SchemaParser.parse(text, "draft $name")
        } catch (e: SchemaParseException) {
            throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, e.message ?: "invalid schema")
        }
        if (document.types.isEmpty()) throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "the schema defines no type")
        val entry = "schemas/${document.namespace}.json"
        val manifest = PluginManifest(name, draft.version, schemas = listOf(entry))
        val problems = PackageValidator.validatePlugin(PluginPackage(manifest, mapOf(entry to text), emptyList()))
        if (problems.isNotEmpty()) throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, problems.joinToString("; ") { "${it.path}: ${it.message}" })
        val bytes = ByteArrayOutputStream().also { PackageWriter.writePlugin(manifest, mapOf(entry to text), emptyMap(), emptyMap(), it) }.toByteArray()
        val m = publishPackage(core, bytes)
        return "Published ${m.name} ${m.version}"
    }

    // --- editor ---

    private fun editor(session: Session, draft: Draft): Html {
        val model = SchemaForm.toModel(draft.content)
        val data = Json.encodeToString(JsonObject.serializer(), JsonObject(model + ("version" to kotlinx.serialization.json.JsonPrimitive(draft.version))))
        val base = "/schemas/${draft.name}"
        val canSave = session.can(Permission.OPERATE)
        return html(
            pageHeader("Schema ${draft.name}", "Types for the data that blocks exchange. The document below is checked while you type.", raw("<a class=\"btn\" href=\"/drafts\">All drafts</a>")),
            raw("<datalist id=\"standard-types\"><option>cringle.std/String</option><option>cringle.std/Boolean</option><option>cringle.std/Int</option><option>cringle.std/Double</option><option>cringle.std/Bytes</option><option>cringle.std/Timestamp</option><option>cringle.std/Empty</option><option>cringle.std/Error</option></datalist>"),
            h(
                "<form id=\"editor\" x-data=\"{}\" hx-post=\"{}/check\" hx-trigger=\"input delay:400ms, change, cringle-changed\" hx-target=\"#preview\" hx-swap=\"morph:innerHTML\">",
                data, base,
            ),
            raw(
                """<input type="hidden" name="model" :value="JSON.stringify({namespace: namespace, types: types})">
<input type="hidden" id="revision" name="revision" value="${draft.revision}">
<label>Namespace <input x-model="namespace" placeholder="acme.orders"></label> <label>Version <input name="version" x-model="version" size="10"></label>
<template x-for="(t, ti) in types" :key="ti"><fieldset>
<input x-model="t.name" placeholder="TypeName"> <select x-model="t.kind"><option value="record">record</option><option value="enum">enum</option></select>
<button type="button" @click="types.splice(ti, 1); ${'$'}dispatch('cringle-changed')">Remove type</button>
<div x-show="t.kind === 'enum'"><input x-model="t.values" placeholder="VALUE_A, VALUE_B" size="40"></div>
<div x-show="t.kind === 'record'">
<template x-for="(f, fi) in t.fields" :key="fi"><div>
<input x-model="f.name" placeholder="fieldName"> <input x-model="f.type" list="standard-types" placeholder="cringle.std/String or Type" size="28">
<select x-model="f.wrap"><option value="">one value</option><option value="list">list</option><option value="map">map</option><option value="optional">optional</option></select>
<button type="button" @click="t.fields.splice(fi, 1); ${'$'}dispatch('cringle-changed')">Remove field</button></div></template>
<button type="button" @click="t.fields.push({name: '', type: 'cringle.std/String', wrap: ''}); ${'$'}dispatch('cringle-changed')">Add field</button>
</div></fieldset></template>
<button type="button" @click="types.push({name: '', kind: 'record', fields: [], values: ''}); ${'$'}dispatch('cringle-changed')">Add type</button>""",
            ),
            if (canSave) h("<button type=\"button\" class=\"btn primary\" hx-post=\"{}/save\" hx-include=\"#editor\" hx-target=\"#preview\" hx-swap=\"morph:innerHTML\">Save draft</button>", base) else Html(""),
            raw("</form><div id=\"preview\"></div>"),
        )
    }

    private fun preview(model: String, error: String?, saved: Draft?): Html {
        val document = try {
            SchemaForm.toDocument(model)
        } catch (e: IllegalArgumentException) {
            return html(notice(e.message), Html(""))
        }
        val text = pretty.encodeToString(JsonObject.serializer(), document)
        val problem = try {
            SchemaParser.parse(text, "draft")
            null
        } catch (e: SchemaParseException) {
            e.message
        }
        return html(
            notice(error),
            if (saved != null) html(info("Saved as revision ${saved.revision}."), h("<input type=\"hidden\" id=\"revision\" name=\"revision\" value=\"{}\" hx-swap-oob=\"true\">", saved.revision)) else Html(""),
            if (problem != null) h("<p class=\"error\" role=\"alert\">{}</p>", problem) else info("The document is valid."),
            h("<pre><code>{}</code></pre>", text),
        )
    }
}
