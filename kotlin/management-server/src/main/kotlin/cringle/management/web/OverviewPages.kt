// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.daemon.v1.EngineProcessState
import cringle.engine.v1.FabricRuntimeState
import cringle.management.ManagementCore
import cringle.management.ManagementException
import cringle.router.users.Permission

/**
 * The pages for machines, engines and fabrics (#208): the functions of `cringle machine|engine|fabric`. A list is a fragment that
 * refreshes itself every 5 seconds; an action answers with the refreshed list, with the error of the core (and its gRPC code) above it.
 */
internal class OverviewPages(private val core: ManagementCore) {
    fun register(web: WebServer) {
        val r = web.router
        web.navigation += listOf(NavItem("Machines", "/machines"), NavItem("Engines", "/engines"), NavItem("Fabrics", "/fabrics"))

        r.get("/machines", Permission.READ) { web.render("Machines", it, section("Machines", "The computers that run a daemon and so the engines.", "machines", machines(it.session!!, null))) }
        r.get("/machines/list", Permission.READ) { fragment(machines(it.session!!, null)) }
        r.post("/machines", Permission.ADMINISTER) { req ->
            val message = attempt { core.addMachine(req.form["id"].orEmpty().trim(), req.form["address"].orEmpty().trim(), null, null) }
            fragment(machines(req.session!!, message))
        }
        r.post("/machines/{id}/remove", Permission.ADMINISTER) { req ->
            fragment(machines(req.session!!, attempt { core.removeMachine(req.params.getValue("id")) }))
        }

        r.get("/engines", Permission.READ) { web.render("Engines", it, section("Engines", "The processes that run fabrics. Roles and labels decide where a project is placed.", "engines", engines(it.session!!, null))) }
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

        r.get("/fabrics", Permission.READ) { web.render("Fabrics", it, section("Fabrics", "The running copies of blueprints, one per engine.", "fabrics", fabrics(it.session!!, null))) }
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
                    pageHeader("Fabric ${f.info.fabricId.value}", "Blueprint ${f.info.blueprint} on engine ${f.engineId} of machine ${f.machine}.", raw("<a class=\"btn\" href=\"/fabrics\">All fabrics</a>")),
                    h("<section class=\"panel facts\"><dl><dt>State</dt><dd>{}</dd><dt>Wanted</dt><dd>{}</dd></dl>{}{}</section>", stateBadge(f.info.state.pretty()), stateBadge(if (f.desiredRunning) "running" else "stopped"), if (f.info.failure.isNotEmpty()) h("<p class=\"notice error\" role=\"alert\">{}</p>", f.info.failure) else Html(""), if (f.info.state == FabricRuntimeState.FABRIC_RUNTIME_STATE_MIGRATION_FAILED) h("<p class=\"hint\">{}</p>", "The data was backed up before the migration and is not changed back. Fix the cause (or restore the backup), then start the fabric again: the migration is retried.") else Html("")),
                    raw("<h2>Blocks</h2>"),
                    dataTable(
                        listOf("Block", "State", "Restarts", "Last error"),
                        f.info.blocksList.map { b -> h("<tr><td>{}</td><td>{}</td><td>{}</td><td class=\"error\">{}</td></tr>", b.blockId.value, stateBadge(b.state.name.removePrefix("BLOCK_RUNTIME_STATE_").lowercase()), b.restarts, b.lastError) },
                        raw("This fabric has no block."),
                    ),
                )
            } catch (e: Exception) {
                html(pageHeader("Fabric", "", raw("<a class=\"btn\" href=\"/fabrics\">All fabrics</a>")), h("<p class=\"notice error\" role=\"alert\">{}</p>", describe(e)))
            }
            web.render("Fabric", req, content)
        }
    }

    private suspend fun machines(session: Session, message: String?): Html {
        val rows = core.listMachines().map {
            h(
                "<tr><td><strong>{}</strong></td><td><code>{}</code></td><td>{}{}</td>{}</tr>",
                it.record.id, it.record.daemonAddress, stateBadge(if (it.reachable) "reachable" else "not reachable"), if (it.lastError.isEmpty()) Html("") else h("<small class=\"error\"> {}</small>", it.lastError),
                actionsCell(button(session, Permission.ADMINISTER, "Remove", "/machines/${it.record.id}/remove", "Forget machine ${it.record.id}?")),
            )
        }
        val form = if (!session.can(Permission.ADMINISTER)) {
            Html("")
        } else {
            h(
                "<form class=\"form-row\" hx-post=\"/machines\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}{}<button class=\"btn primary\">Add machine</button></form>",
                field("Machine id", raw("<input name=\"id\" placeholder=\"m1\" required>")),
                field("Daemon address", raw("<input name=\"address\" placeholder=\"host:port\" required>")),
            )
        }
        return html(
            notice(message),
            dataTable(listOf("Machine", "Daemon", "State", ""), rows, raw("No machine yet. Add the first one below: every engine runs on a machine that has a daemon.")),
            formPanel("Add a machine", "The daemon has to run there and has to trust this server, and the other way round.", form),
        )
    }

    private suspend fun engines(session: Session, message: String?): Html {
        val rows = core.listEngines(null).map { e ->
            val base = "/engines/${e.machine}/${e.process.engineId.value}"
            val running = e.process.state == EngineProcessState.ENGINE_PROCESS_STATE_RUNNING
            h(
                "<tr><td>{}</td><td><strong>{}</strong></td><td>{}</td><td>{}</td><td>{}</td>{}</tr>",
                e.machine, e.process.engineId.value, stateBadge(e.process.state.pretty()),
                if (e.roles.isEmpty()) h("<span class=\"muted\">none</span>") else e.roles.map { h("<span class=\"tag\">{}</span> ", it) },
                if (e.labels.isEmpty()) h("<span class=\"muted\">none</span>") else e.labels.entries.map { h("<span class=\"tag\">{}={}</span> ", it.key, it.value) },
                actionsCell(
                    if (running) button(session, Permission.OPERATE, "Stop", "$base/stop") else button(session, Permission.OPERATE, "Start", "$base/start"),
                    tagsForm(session, base, e.roles, e.labels),
                    button(session, Permission.OPERATE, "Delete", "$base/delete", "Delete engine ${e.process.engineId.value}?"),
                ),
            )
        }
        val form = if (!session.can(Permission.OPERATE)) {
            Html("")
        } else {
            h(
                "<form class=\"form-row\" hx-post=\"/engines\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}{}<label class=\"check\"><input type=\"checkbox\" name=\"autostart\"> Start with the daemon</label><button class=\"btn primary\">Create engine</button></form>",
                field("Machine", html(raw("<select name=\"machine\" required>"), core.listMachines().map { h("<option>{}</option>", it.record.id) }, raw("</select>"))),
                field("Engine id", raw("<input name=\"id\" placeholder=\"optional\">")),
            )
        }
        return html(
            notice(message),
            dataTable(listOf("Machine", "Engine", "State", "Roles", "Labels", ""), rows, raw("No engine yet. Create one below, then start it.")),
            formPanel("Create an engine", "An engine is created on a machine and started separately. Roles and labels are set afterwards under Tags.", form),
        )
    }

    private fun tagsForm(session: Session, base: String, roles: List<String>, labels: Map<String, String>): Html =
        if (!session.can(Permission.OPERATE)) {
            Html("")
        } else {
            h(
                "<details class=\"popover\"><summary>Tags</summary><form class=\"popover-body\" hx-post=\"{}/tags\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}{}<button class=\"btn primary small\">Save tags</button></form></details>",
                base,
                field("Roles", h("<input name=\"roles\" value=\"{}\" placeholder=\"role, role\">", roles.joinToString(", ")), "comma separated"),
                field("Labels", h("<input name=\"labels\" value=\"{}\" placeholder=\"key=value, key=value\">", labels.entries.joinToString(", ") { "${it.key}=${it.value}" }), "key=value, comma separated"),
            )
        }

    private suspend fun fabrics(session: Session, message: String?): Html {
        val rows = core.listFabrics(null, null).map { f ->
            val id = f.info.fabricId.value
            val base = "/fabrics/${f.machine}/${f.engineId}/$id"
            val running = f.info.state == FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING || f.info.state == FabricRuntimeState.FABRIC_RUNTIME_STATE_STARTING
            h(
                "<tr><td><a href=\"{}\"><strong>{}</strong></a></td><td>{} / {}</td><td>{}</td><td>{}</td><td>{}</td>{}</tr>",
                base, id, f.machine, f.engineId, f.info.blueprint, stateBadge(f.info.state.pretty()), stateBadge(if (f.desiredRunning) "running" else "stopped"),
                actionsCell(
                    if (running) button(session, Permission.OPERATE, "Stop", "$base/stop")
                    else button(session, Permission.OPERATE, if (f.info.state == FabricRuntimeState.FABRIC_RUNTIME_STATE_MIGRATION_FAILED) "Retry" else "Start", "$base/start"),
                    button(session, Permission.OPERATE, "Remove", "$base/remove", "Remove fabric $id?"),
                ),
            )
        }
        return html(
            notice(message),
            dataTable(listOf("Fabric", "Engine", "Blueprint", "State", "Wanted", ""), rows, raw("No fabric yet. Fabrics come from a deployed project: see Deployments.")),
        )
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
