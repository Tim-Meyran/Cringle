// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.management.ManagementCore
import cringle.management.ManagementException
import cringle.router.users.Permission
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The projects of the WebUI (#385): a project is a workspace, the project draft of the blueprint editor with its binary files ([ProjectFiles]) and the schema
 * drafts it names. The page of a project shows its content as a **tree**: `project.json`, `blueprints/`, `schemas/` and `binaries/`; publishing builds the project
 * package from it. The files are uploaded in the tree.
 */
internal class ProjectPages(private val core: ManagementCore, private val drafts: DraftStore, private val blueprints: BlueprintPages, private val files: ProjectFiles) {
    fun register(web: WebServer) {
        val r = web.router
        web.navigation += NavItem("Projects", "/projects", group = "Build")

        r.get("/projects", Permission.READ) { web.render("Projects", it, section("Projects", "Projects you build here: blueprints, schemas and files, published as one package.", "projects", list(it.session!!, null, null))) }
        r.get("/projects/list", Permission.READ) { fragment(list(it.session!!, null, null)) }
        r.post("/projects", Permission.OPERATE) { req ->
            val error = attempt {
                val name = req.form["name"].orEmpty().trim()
                if (drafts.load("project", name) != null) throw ManagementException(io.grpc.Status.Code.ALREADY_EXISTS, "the project '$name' exists")
                drafts.save("project", name, "1.0.0", blueprints.emptyContent(name))
            }
            fragment(list(req.session!!, error, null))
        }
        r.post("/projects/{name}/delete", Permission.OPERATE) { req ->
            val name = req.params.getValue("name")
            val error = attempt {
                drafts.delete("project", name)
                files.deleteProject(name)
            }
            fragment(list(req.session!!, error, if (error == null) "Deleted the project $name." else null))
        }

        r.get("/projects/{name}", Permission.READ) { req ->
            val name = req.params.getValue("name")
            if (draft(name) == null) {
                web.problem(404, "Not found", "There is no such project.", req)
            } else {
                web.render("Project $name", req, section("Project $name", "The content of the project as a tree.", "projects/$name", tree(req.session!!, name, null, null), raw("<a class=\"btn\" href=\"/projects\">All projects</a>")))
            }
        }
        r.get("/projects/{name}/list", Permission.READ) { req -> fragment(tree(req.session!!, req.params.getValue("name"), null, null)) }
        r.post("/projects/{name}/publish", Permission.OPERATE) { req ->
            val name = req.params.getValue("name")
            var done: String? = null
            val error = attempt { done = blueprints.publish(draft(name) ?: throw ManagementException(io.grpc.Status.Code.NOT_FOUND, "no project '$name'")) }
            fragment(tree(req.session!!, name, error, done))
        }

        r.post("/projects/{name}/schemas/add", Permission.OPERATE) { req -> fragment(changeSchemas(req.session!!, req.params.getValue("name"), req.form["schema"].orEmpty().ifEmpty { req.query["schema"].orEmpty() }, add = true)) }
        r.post("/projects/{name}/schemas/remove", Permission.OPERATE) { req -> fragment(changeSchemas(req.session!!, req.params.getValue("name"), req.form["schema"].orEmpty().ifEmpty { req.query["schema"].orEmpty() }, add = false)) }

        r.post("/projects/{name}/files", Permission.OPERATE, ProjectFiles.MAX_FILE + 1024 * 1024) { req ->
            val name = req.params.getValue("name")
            var done: String? = null
            val error = attempt {
                val parts = Multipart.parse(req.headers["content-type"], req.body)
                val file = parts.firstOrNull { it.name == "file" && !it.filename.isNullOrEmpty() } ?: throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "choose a file")
                val given = parts.firstOrNull { it.name == "path" }?.data?.decodeToString()?.trim().orEmpty()
                val path = given.ifEmpty { file.filename!!.substringAfterLast('/').substringAfterLast('\\') }
                files.put(name, path, file.data)
                done = "Stored $path (${file.data.size} bytes)."
            }
            fragment(tree(req.session!!, name, error, done))
        }
        r.post("/projects/{name}/files/delete", Permission.OPERATE) { req ->
            val name = req.params.getValue("name")
            val path = req.form["path"].orEmpty()
            val error = attempt { if (!files.delete(name, path)) throw ManagementException(io.grpc.Status.Code.NOT_FOUND, "there is no '$path'") }
            fragment(tree(req.session!!, name, error, if (error == null) "Deleted $path." else null))
        }
        r.get("/projects/{name}/file", Permission.READ) { req ->
            val name = req.params.getValue("name")
            val path = req.query["path"].orEmpty()
            val data = try {
                files.read(name, path)
            } catch (e: DraftException) {
                null
            }
            if (data == null) {
                web.problem(404, "Not found", "There is no such file.", req)
            } else {
                val file = path.substringAfterLast('/')
                WebResponse(200, data, "application/octet-stream", mapOf("Content-Disposition" to "attachment; filename=\"$file\"", "X-Content-Type-Options" to "nosniff"))
            }
        }
    }

    private fun draft(name: String): Draft? = try {
        drafts.load("project", name)
    } catch (e: DraftException) {
        null
    }

    private fun schemasOf(draft: Draft): List<String> = draft.content.jsonObject["schemas"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private suspend fun changeSchemas(session: Session, name: String, schema: String, add: Boolean): Html {
        val error = attempt {
            val draft = draft(name) ?: throw ManagementException(io.grpc.Status.Code.NOT_FOUND, "no project '$name'")
            if (add && drafts.load("schema", schema) == null) throw ManagementException(io.grpc.Status.Code.NOT_FOUND, "there is no schema draft '$schema'")
            val now = schemasOf(draft)
            val next = if (add) (now + schema).distinct() else now - schema
            val content = JsonObject(draft.content.jsonObject + ("schemas" to JsonArray(next.map { JsonPrimitive(it) })))
            drafts.save("project", name, draft.version, content, draft.revision)
        }
        return tree(session, name, error, null)
    }

    // --- the pages ---

    private fun list(session: Session, error: String?, done: String?): Html {
        val rows = drafts.list("project").map { d ->
            val blueprint = blueprints.blueprintOf(d)
            val all = files.list(d.name)
            h(
                "<tr><td><a href=\"/projects/{}\"><strong>{}</strong></a></td><td>{}</td><td class=\"num\">{}</td><td class=\"num\">{}</td>{}</tr>",
                d.name, d.name, d.version, blueprint?.blocks?.size ?: 0, all.size,
                actionsCell(
                    h("<a class=\"btn small\" href=\"/projects/{}\">Open</a>", d.name),
                    button(session, Permission.OPERATE, "Delete", "/projects/${d.name}/delete", "Delete the project ${d.name} with its files?"),
                ),
            )
        }
        val form = if (!session.canAnywhere(Permission.OPERATE)) {
            Html("")
        } else {
            h(
                "<form class=\"form-row\" hx-post=\"/projects\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}<button class=\"btn primary\">Create project</button></form>",
                field("Name", raw("<input name=\"name\" placeholder=\"acme-shop\" required>"), "lower case letters, digits, - and ."),
            )
        }
        return html(
            flash(error, done),
            dataTable(listOf("Project", "Version", "Blocks", "Files", ""), rows, raw("No project yet. Create one below; it starts with an empty blueprint.")),
            formPanel("Create a project", "A project holds its blueprint, schemas and files and is published as one package.", form),
        )
    }

    private fun tree(session: Session, name: String, error: String?, done: String?): Html {
        val d = draft(name) ?: return html(flash("There is no such project.", null))
        val canChange = session.canAnywhere(Permission.OPERATE)
        val blueprint = blueprints.blueprintOf(d)
        val fabrics = d.content.jsonObject["fabrics"]?.jsonArray?.size ?: 1
        val plugins = blueprint?.blocks?.map { it.block.substringBefore('/') }?.distinct().orEmpty()
        val schemas = schemasOf(d)
        val allFiles = files.list(name)
        val available = drafts.list("schema").map { it.name }.filter { it !in schemas }

        val schemaItems = schemas.map { s ->
            val exists = drafts.load("schema", s) != null
            h(
                "<li class=\"leaf\"><span class=\"file\">{}.json</span> {}<span class=\"row-actions\">{}{}</span></li>",
                s, if (exists) Html("") else badge("missing", Tone.BAD),
                if (exists) h("<a class=\"btn small\" href=\"/schemas/{}\">Open</a>", s) else Html(""),
                button(session, Permission.OPERATE, "Remove", "/projects/$name/schemas/remove?schema=$s", "Remove the schema $s from the project? The draft stays."),
            )
        }
        val addSchema = if (canChange && available.isNotEmpty()) {
            h(
                "<li class=\"leaf add\"><form class=\"form-row\" hx-post=\"/projects/{}/schemas/add\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}<button class=\"btn small\">Add</button></form></li>",
                name, raw("<select name=\"schema\" aria-label=\"Schema draft\">" + available.joinToString("") { "<option>" + escape(it) + "</option>" } + "</select>"),
            )
        } else {
            Html("")
        }
        val upload = if (canChange) {
            h(
                "<li class=\"leaf add\"><form class=\"form-row\" hx-post=\"/projects/{}/files\" hx-encoding=\"multipart/form-data\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}{}<button class=\"btn small\">Upload</button></form></li>",
                name, raw("<input type=\"file\" name=\"file\" required aria-label=\"File\">"), raw("<input name=\"path\" placeholder=\"path in the project, e.g. web/index.html (default: the file name)\" aria-label=\"Path\">"),
            )
        } else {
            Html("")
        }

        return html(
            flash(error, done),
            raw("<ul class=\"tree\"><li><details open><summary>"),
            h("<strong>{}</strong> <span class=\"muted\">version {}</span>", name, d.version),
            h(
                "<span class=\"row-actions\"><a class=\"btn small\" href=\"/blueprints/{}\">Edit blueprint</a>{}</span>",
                name, button(session, Permission.OPERATE, "Publish", "/projects/$name/publish", "Publish the project $name ${d.version} to the repository?"),
            ),
            raw("</summary><ul>"),
            h(
                "<li class=\"leaf\"><span class=\"file\">project.json</span> <span class=\"muted\">version {}, {} fabric{}, plugins: {}</span></li>",
                d.version, fabrics, if (fabrics == 1) "" else "s", if (plugins.isEmpty()) "none yet" else plugins.joinToString(", "),
            ),
            h(
                "<li><details open><summary>blueprints/</summary><ul><li class=\"leaf\"><span class=\"file\">{}.json</span> <span class=\"muted\">{} block{}, {} tether{}</span><span class=\"row-actions\"><a class=\"btn small\" href=\"/blueprints/{}\">Open in the editor</a></span></li></ul></details></li>",
                name, blueprint?.blocks?.size ?: 0, if (blueprint?.blocks?.size == 1) "" else "s", blueprint?.tethers?.size ?: 0, if (blueprint?.tethers?.size == 1) "" else "s", name,
            ),
            h("<li><details open><summary>schemas/ <span class=\"muted\">{}</span></summary><ul>{}{}</ul></details></li>", schemas.size, schemaItems, addSchema),
            h(
                "<li><details open><summary>binaries/ <span class=\"muted\">{} file{}, {}</span></summary><ul>{}{}</ul></details></li>",
                allFiles.size, if (allFiles.size == 1) "" else "s", size(allFiles.sumOf { it.size }), fileTree(session, name, allFiles), upload,
            ),
            raw("</ul></details></li></ul>"),
        )
    }

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
        else -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    }

    /** A folder of the tree: its files and its sub folders. */
    private class Folder(val folders: MutableMap<String, Folder> = sortedMapOf(), val files: MutableList<ProjectFile> = ArrayList())

    private fun fileTree(session: Session, project: String, all: List<ProjectFile>): Html {
        val root = Folder()
        for (f in all) {
            var folder = root
            val parts = f.path.split('/')
            for (part in parts.dropLast(1)) folder = folder.folders.getOrPut(part) { Folder() }
            folder.files += f
        }
        fun render(folder: Folder, prefix: String): List<Html> =
            folder.folders.map { (folderName, sub) ->
                val path = prefix + folderName
                h(
                    "<li><details open><summary>{}/<span class=\"row-actions\">{}</span></summary><ul>{}</ul></details></li>",
                    folderName, deleteButton(session, project, path, "folder"), render(sub, "$path/"),
                )
            } + folder.files.map { f ->
                h(
                    "<li class=\"leaf\"><span class=\"file\">{}</span> <span class=\"muted\">{}</span><span class=\"row-actions\"><a class=\"btn small\" href=\"/projects/{}/file?path={}\">Download</a>{}</span></li>",
                    f.path.substringAfterLast('/'), size(f.size), project, f.path, deleteButton(session, project, f.path, "file"),
                )
            }
        return html(render(root, ""))
    }

    private fun deleteButton(session: Session, project: String, path: String, what: String): Html =
        if (!session.can(Permission.OPERATE)) {
            Html("")
        } else {
            h(
                "<button hx-post=\"/projects/{}/files/delete\" hx-vals='{}' hx-target=\"#list\" hx-swap=\"morph:innerHTML\" hx-confirm=\"{}\">Delete</button>",
                project, "{\"path\": \"" + path + "\"}", "Delete the $what $path?",
            )
        }
}
