// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.daemon.v1.EngineProcessState
import cringle.engine.v1.FabricRuntimeState
import cringle.management.ManagementCore
import cringle.management.ManagementException
import cringle.router.users.Permission
import kotlinx.coroutines.CancellationException

/**
 * The pages for machines, engines and fabrics (#208): the functions of `cringle machine|engine|fabric`. A list is a fragment that
 * refreshes itself every 5 seconds; an action answers with the refreshed list, with the error of the core (and its gRPC code) above it.
 */
internal class OverviewPages(private val core: ManagementCore) {
    fun register(web: WebServer) {
        val r = web.router
        web.navigation += listOf(NavItem("Machines", "/machines"), NavItem("Engines", "/engines"), NavItem("Fabrics", "/fabrics"))

        r.get("/machines", Permission.READ) { web.render("Machines", it, section("Machines", "machines", machines(it.session!!, null))) }
        r.get("/machines/list", Permission.READ) { fragment(machines(it.session!!, null)) }
        r.post("/machines", Permission.ADMINISTER) { req ->
            val message = attempt { core.addMachine(req.form["id"].orEmpty().trim(), req.form["address"].orEmpty().trim(), null, null) }
            fragment(machines(req.session!!, message))
        }
        r.post("/machines/{id}/remove", Permission.ADMINISTER) { req ->
            fragment(machines(req.session!!, attempt { core.removeMachine(req.params.getValue("id")) }))
        }

        r.get("/engines", Permission.READ) { web.render("Engines", it, section("Engines", "engines", engines(it.session!!, null))) }
        r.get("/engines/list", Permission.READ) { fragment(engines(it.session!!, null)) }
        r.post("/engines", Permission.OPERATE) { req ->
            val message = attempt {
                core.createEngine(req.form["machine"].orEmpty().trim(), req.form["id"].orEmpty().trim().ifEmpty { null }, null, req.form["autostart"] == "on")
            }
            fragment(engines(req.session!!, message))
        }
        for (action in listOf("start", "stop", "delete")) {
            r.post("/engines/{machine}/{id}/$action", Permission.OPERATE) { req ->
                val machine = req.params.getValue("machine")
                val id = req.params.getValue("id")
                val message = attempt {
                    when (action) {
                        "start" -> core.startEngine(machine, id)
                        "stop" -> core.stopEngine(machine, id)
                        else -> core.deleteEngine(machine, id, false)
                    }
                }
                fragment(engines(req.session!!, message))
            }
        }
        r.post("/engines/{machine}/{id}/tags", Permission.OPERATE) { req ->
            val message = attempt {
                core.setEngineTags(req.params.getValue("machine"), req.params.getValue("id"), parseRoles(req.form["roles"].orEmpty()), parseLabels(req.form["labels"].orEmpty()))
            }
            fragment(engines(req.session!!, message))
        }

        r.get("/fabrics", Permission.READ) { web.render("Fabrics", it, section("Fabrics", "fabrics", fabrics(it.session!!, null))) }
        r.get("/fabrics/list", Permission.READ) { fragment(fabrics(it.session!!, null)) }
        for (action in listOf("start", "stop", "remove")) {
            r.post("/fabrics/{machine}/{engine}/{fabric}/$action", Permission.OPERATE) { req ->
                val machine = req.params.getValue("machine")
                val engine = req.params.getValue("engine")
                val fabric = req.params.getValue("fabric")
                val message = attempt {
                    when (action) {
                        "start" -> core.startFabric(machine, engine, fabric)
                        "stop" -> core.stopFabric(machine, engine, fabric)
                        else -> core.removeFabric(machine, engine, fabric)
                    }
                }
                fragment(fabrics(req.session!!, message))
            }
        }
        r.get("/fabrics/{machine}/{engine}/{fabric}", Permission.READ) { req ->
            val content = try {
                val f = core.getFabric(req.params.getValue("machine"), req.params.getValue("engine"), req.params.getValue("fabric"))
                html(
                    h("<h1>Fabric {}</h1>", f.info.fabricId.value),
                    h("<p>Engine {} on {}; blueprint {}; state {}; wanted: {}</p>", f.engineId, f.machine, f.info.blueprint, f.info.state.pretty(), if (f.desiredRunning) "running" else "stopped"),
                    if (f.info.failure.isNotEmpty()) h("<p class=\"error\">{}</p>", f.info.failure) else Html(""),
                    raw("<table><tr><th>Block</th><th>State</th><th>Restarts</th><th>Last error</th></tr>"),
                    f.info.blocksList.map { h("<tr><td>{}</td><td>{}</td><td>{}</td><td>{}</td></tr>", it.blockId.value, it.state.name.removePrefix("BLOCK_RUNTIME_STATE_").lowercase(), it.restarts, it.lastError) },
                    raw("</table><p><a href=\"/fabrics\">Back</a></p>"),
                )
            } catch (e: Exception) {
                h("<h1>Fabric</h1><p class=\"error\">{}</p>", describe(e))
            }
            web.render("Fabric", req, content)
        }
    }

    // --- fragments ---

    private fun section(title: String, name: String, initial: Html): Html = html(
        h("<h1>{}</h1>", title),
        h("<div id=\"list\" hx-get=\"/{}/list\" hx-trigger=\"every 5s\" hx-swap=\"innerHTML\">", name),
        initial,
        raw("</div>"),
    )

    private fun fragment(content: Html): WebResponse = WebResponse.page(200, content)

    private fun notice(message: String?): Html = if (message == null) Html("") else h("<p class=\"error\" role=\"alert\">{}</p>", message)

    /** Runs [action]; the error text (with the gRPC code) if it fails, `null` if not. */
    private suspend fun attempt(action: suspend () -> Unit): String? = try {
        action()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        describe(e)
    }

    private fun describe(e: Exception): String = when (e) {
        is ManagementException -> "${e.code}: ${e.message}"
        is io.grpc.StatusException -> "${e.status.code}: ${e.status.description.orEmpty()}"
        else -> e.message ?: e.toString()
    }

    private fun button(session: Session, permission: Permission, label: String, url: String, confirm: String? = null): Html =
        if (!session.can(permission)) {
            Html("")
        } else {
            h("<button hx-post=\"{}\" hx-target=\"#list\" hx-swap=\"innerHTML\"{}>{}</button> ", url, if (confirm != null) h(" hx-confirm=\"{}\"", confirm) else Html(""), label)
        }

    private fun Any.pretty(): String = toString().substringAfter("STATE_").lowercase()

    private suspend fun machines(session: Session, message: String?): Html {
        val rows = core.listMachines().map {
            h(
                "<tr><td>{}</td><td>{}</td><td>{}</td><td class=\"error\">{}</td><td>{}</td></tr>",
                it.record.id, it.record.daemonAddress, if (it.reachable) "reachable" else "not reachable", it.lastError,
                button(session, Permission.ADMINISTER, "Remove", "/machines/${it.record.id}/remove", "Forget machine ${it.record.id}?"),
            )
        }
        val form = if (!session.can(Permission.ADMINISTER)) {
            Html("")
        } else {
            raw("<form hx-post=\"/machines\" hx-target=\"#list\" hx-swap=\"innerHTML\"><input name=\"id\" placeholder=\"machine id\" required> <input name=\"address\" placeholder=\"daemon host:port\" required> <button>Add machine</button></form>")
        }
        return html(notice(message), raw("<table><tr><th>Machine</th><th>Daemon</th><th>State</th><th>Last error</th><th></th></tr>"), rows, raw("</table>"), form)
    }

    private suspend fun engines(session: Session, message: String?): Html {
        val rows = core.listEngines(null).map { e ->
            val base = "/engines/${e.machine}/${e.process.engineId.value}"
            val running = e.process.state == EngineProcessState.ENGINE_PROCESS_STATE_RUNNING
            h(
                "<tr><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}{}{}{}</td></tr>",
                e.machine, e.process.engineId.value, e.process.state.pretty(), e.roles.joinToString(", "), e.labels.entries.joinToString(", ") { "${it.key}=${it.value}" },
                if (running) Html("") else button(session, Permission.OPERATE, "Start", "$base/start"),
                if (running) button(session, Permission.OPERATE, "Stop", "$base/stop") else Html(""),
                button(session, Permission.OPERATE, "Delete", "$base/delete", "Delete engine ${e.process.engineId.value}?"),
                tagsForm(session, base, e.roles, e.labels),
            )
        }
        val form = if (!session.can(Permission.OPERATE)) {
            Html("")
        } else {
            raw("<form hx-post=\"/engines\" hx-target=\"#list\" hx-swap=\"innerHTML\"><input name=\"machine\" placeholder=\"machine id\" required> <input name=\"id\" placeholder=\"engine id (optional)\"> <label><input type=\"checkbox\" name=\"autostart\"> autostart</label> <button>Create engine</button></form>")
        }
        return html(notice(message), raw("<table><tr><th>Machine</th><th>Engine</th><th>State</th><th>Roles</th><th>Labels</th><th></th></tr>"), rows, raw("</table>"), form)
    }

    private fun tagsForm(session: Session, base: String, roles: List<String>, labels: Map<String, String>): Html =
        if (!session.can(Permission.OPERATE)) {
            Html("")
        } else {
            h(
                "<details><summary>Tags</summary><form hx-post=\"{}/tags\" hx-target=\"#list\" hx-swap=\"innerHTML\"><input name=\"roles\" value=\"{}\" placeholder=\"roles, comma separated\"> <input name=\"labels\" value=\"{}\" placeholder=\"key=value, ...\"> <button>Save</button></form></details>",
                base, roles.joinToString(","), labels.entries.joinToString(",") { "${it.key}=${it.value}" },
            )
        }

    private suspend fun fabrics(session: Session, message: String?): Html {
        val rows = core.listFabrics(null, null).map { f ->
            val id = f.info.fabricId.value
            val base = "/fabrics/${f.machine}/${f.engineId}/$id"
            val running = f.info.state == FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING || f.info.state == FabricRuntimeState.FABRIC_RUNTIME_STATE_STARTING
            h(
                "<tr><td><a href=\"{}\">{}</a></td><td>{} / {}</td><td>{}</td><td>{}</td><td>{}</td><td>{}{}{}</td></tr>",
                base, id, f.machine, f.engineId, f.info.blueprint, f.info.state.pretty(), if (f.desiredRunning) "running" else "stopped",
                if (running) Html("") else button(session, Permission.OPERATE, "Start", "$base/start"),
                if (running) button(session, Permission.OPERATE, "Stop", "$base/stop") else Html(""),
                button(session, Permission.OPERATE, "Remove", "$base/remove", "Remove fabric $id?"),
            )
        }
        return html(notice(message), raw("<table><tr><th>Fabric</th><th>Engine</th><th>Blueprint</th><th>State</th><th>Wanted</th><th></th></tr>"), rows, raw("</table>"))
    }

    private fun parseRoles(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun parseLabels(text: String): Map<String, String> {
        val labels = LinkedHashMap<String, String>()
        for (part in parseRoles(text)) {
            if ('=' !in part) throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "label '$part' must be key=value")
            labels[part.substringBefore('=').trim()] = part.substringAfter('=').trim()
        }
        return labels
    }
}
