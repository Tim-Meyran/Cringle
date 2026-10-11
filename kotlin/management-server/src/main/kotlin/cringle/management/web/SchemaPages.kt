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
                    h("<a class=\"btn small\" href=\"/{}/{}\">{}</a>", if (d.kind == "schema") "schemas" else "blueprints", d.name, if (session.canAnywhere(Permission.OPERATE)) "Edit" else "Open"),
                    button(session, Permission.OPERATE, "Publish", "$base/publish", "Publish ${d.kind} ${d.name} ${d.version} to the repository?"),
                    button(session, Permission.OPERATE, "Delete", "$base/delete", "Delete the draft ${d.name}?"),
                ),
            )
        }
        val form = if (!session.canAnywhere(Permission.OPERATE)) {
            Html("")
        } else {
            h(
                "<form class=\"form-row\" hx-post=\"/drafts\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}{}<button class=\"btn primary\">Create draft</button></form>",
                field("What", raw("<select name=\"kind\"><option value=\"schema\">Schema</option><option value=\"project\">Project with a blueprint</option></select>")),
                field("Name", raw("<input name=\"name\" placeholder=\"acme-orders\" required>"), "lower case letters, digits, - and ."),
            )
        }
        return html(
            flash(error, done),
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
        val canSave = session.canAnywhere(Permission.OPERATE)
        val actions = html(
            raw("<a class=\"btn\" href=\"/drafts\">All drafts</a>"),
            if (canSave) h("<button type=\"button\" class=\"btn primary\" hx-post=\"{}/save\" hx-include=\"#editor\" hx-target=\"#preview\" hx-swap=\"morph:innerHTML\">Save draft</button>", base) else Html(""),
        )
        return html(
            pageHeader("Schema ${draft.name}", "Types for the data that blocks exchange. Every change is checked while you type; the document on the right is what is saved.", actions),
            h(
                "<div class=\"schema-layout\"><form id=\"editor\" class=\"stack schema-editor\" x-data=\"schemaEditor({})\" hx-post=\"{}/check\" hx-trigger=\"input delay:400ms, change, cringle-changed, load\" hx-target=\"#preview\" hx-swap=\"morph:innerHTML\">",
                data, base,
            ),
            raw(
                """<input type="hidden" name="model" :value="JSON.stringify({namespace: namespace, types: types})">
<input type="hidden" id="revision" name="revision" value="${draft.revision}">
<section class="panel schema-meta"><h2>Schema</h2>
<div class="form-row">
<label class="field"><span>Namespace</span><input x-model="namespace" placeholder="acme.orders" aria-label="Namespace"><small>The types are named <code x-text="(namespace || 'namespace') + '/TypeName'"></code></small></label>
<label class="field"><span>Version</span><input name="version" x-model="version" size="10"><small>Of the package that carries it</small></label>
</div></section>
<div class="types-head"><h2>Types</h2><button type="button" class="btn small" @click="openNew()">New type</button></div>
<div class="empty-card" x-show="types.length === 0"><strong>No type yet</strong><p>A type describes the data that two blocks exchange: a <em>record</em> has named fields, an <em>enum</em> a fixed set of values.</p><button type="button" class="btn primary" @click="openNew()">Create the first type</button></div>
<datalist id="schema-types"><template x-for="o in typeOptions()" :key="o"><option :value="o"></option></template></datalist>
<template x-for="(t, ti) in types" :key="ti"><section class="type-card" :class="{open: t.open}">
<div class="type-head" @click="t.open = !t.open" role="button" tabindex="0" :aria-expanded="t.open" @keydown.enter.prevent="t.open = !t.open">
<span class="chev" aria-hidden="true"></span><strong x-text="t.name || 'unnamed type'"></strong><span class="tag" x-text="t.kind"></span><span class="muted" x-text="summary(t)"></span>
<button type="button" class="icon-btn" title="Remove the type" aria-label="Remove the type" @click.stop="removeType(ti)">&times;</button>
</div>
<div class="type-body" x-show="t.open">
<div class="form-row">
<label class="field grow"><span>Name</span><input x-model="t.name" placeholder="TypeName"><small class="field-error" x-show="t.name &amp;&amp; !validName(t.name)">Start with a capital letter; letters and digits only.</small></label>
<div class="field"><span>Kind</span><div class="segmented" role="group" aria-label="Kind"><button type="button" :class="{on: t.kind === 'record'}" @click="t.kind = 'record'; changed()">Record</button><button type="button" :class="{on: t.kind === 'enum'}" @click="t.kind = 'enum'; changed()">Enum</button></div></div>
</div>
<div x-show="t.kind === 'record'" class="fields">
<div class="field-row field-row-head" x-show="t.fields.length &gt; 0"><span>Field</span><span>Type</span><span>How often</span><span></span></div>
<template x-for="(f, fi) in t.fields" :key="fi"><div class="field-row">
<input x-model="f.name" placeholder="fieldName" aria-label="Name of the field"><input x-model="f.type" list="schema-types" placeholder="cringle.std/String" aria-label="Type of the field">
<select x-model="f.wrap" aria-label="How often"><option value="">one value</option><option value="list">list</option><option value="map">map</option><option value="optional">optional</option></select>
<button type="button" class="icon-btn" title="Remove the field" aria-label="Remove the field" @click="removeField(t, fi)">&times;</button></div></template>
<div><button type="button" class="btn small" @click="addField(t)">Add field</button></div>
</div>
<div x-show="t.kind === 'enum'" class="enum-values">
<span class="muted">Values</span>
<div class="chips"><template x-for="(v, vi) in valueList(t)" :key="vi"><span class="chip"><span x-text="v"></span><button type="button" :aria-label="'Remove ' + v" @click="removeValue(t, vi)">&times;</button></span></template>
<input class="chip-input" placeholder="Add a value, press Enter" aria-label="Add a value" @keydown.enter.prevent="addValue(t, ${'$'}event)" @keydown.comma.prevent="addValue(t, ${'$'}event)" @blur="addValue(t, ${'$'}event)"></div>
</div>
</div></section></template>
<dialog class="dialog small" x-ref="newType" @input.stop @change.stop>
<div class="dialog-head"><h2>New type</h2><button type="button" class="icon-btn" aria-label="Close" @click="${'$'}refs.newType.close()">&times;</button></div>
<div class="dialog-body"><p class="hint">A record has named fields, an enum a fixed set of values. You can change the kind later.</p>
<div class="dialog-error" x-show="newError" x-text="newError"></div>
<label class="field"><span>Name</span><input x-ref="newName" x-model="newName" placeholder="Order" autocomplete="off" @keydown.enter.prevent="createType()"></label>
<div class="field"><span>Kind</span><div class="segmented" role="group" aria-label="Kind"><button type="button" :class="{on: newKind === 'record'}" @click="newKind = 'record'">Record</button><button type="button" :class="{on: newKind === 'enum'}" @click="newKind = 'enum'">Enum</button></div></div>
<div class="dialog-actions"><button type="button" class="btn" @click="${'$'}refs.newType.close()">Cancel</button><button type="button" class="btn primary" @click="createType()">Create type</button></div></div>
</dialog>""",
            ),
            raw("</form><aside class=\"schema-preview\"><h2>Document</h2><div id=\"preview\"></div></aside></div>"),
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
