// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.engine.v1.BlockRuntimeState
import cringle.engine.v1.GetFabricGraphResponse
import cringle.engine.v1.GraphTether
import cringle.management.ManagementCore
import cringle.router.users.Permission
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The monitor (#399): a running fabric on the canvas of the blueprint editor, read only. `monitor.js` draws the graph from `/monitor/{fabric}/graph` and
 * polls it; what a click on a block, a port or a tether shows is rendered here (`block`, `port`, `tether`) and loaded by htmx into the side panel or the
 * data dock. The values of a tether are the ring buffer of the engine (`GetTraffic`, needs `OPERATE` like the debugger); a breakpoint on a block holds
 * the values of all its inputs, one on a port those of its tethers, one on a tether those of this tether (the debugger of #323).
 */
internal class MonitorPages(private val core: ManagementCore) {
    fun register(web: WebServer) {
        val r = web.router
        web.navigation += NavItem("Monitor", "/monitor", Permission.READ, "Observe")
        r.get("/monitor", Permission.READ) { web.render("Monitor", it, section("Monitor", "Analyse a running fabric: the state and the activity of its blocks and tethers, their numbers and the last values.", "monitor", fabricList(it.session!!))) }
        r.get("/monitor/list", Permission.READ) { fragment(fabricList(it.session!!)) }
        r.get("/monitor/{fabric}", Permission.READ) { req ->
            val fabric = req.params.getValue("fabric")
            val session = req.session!!
            when {
                !session.canFor(Permission.READ, core.access.fabricById(fabric)) -> web.problem(404, "Not found", "There is no such fabric.", req)
                else -> {
                    val error = attempt { core.fabricGraph(fabric) }
                    web.render("Monitor $fabric", req, page(fabric, error, session.canFor(Permission.OPERATE, core.access.fabricById(fabric))))
                }
            }
        }
        r.get("/monitor/{fabric}/graph", Permission.READ) { req ->
            val fabric = req.params.getValue("fabric")
            var body: JsonObject? = null
            var failure: String? = null
            val denied = guarded(req, Permission.READ) {
                failure = attempt { body = graphJson(fabric, req.session!!.canFor(Permission.OPERATE, core.access.fabricById(fabric))) }
            }
            denied ?: WebResponse(if (failure == null) 200 else 502, (body ?: buildJsonObject { put("error", failure) }).toString().toByteArray(), "application/json")
        }
        r.get("/monitor/{fabric}/block", Permission.READ) { req -> panel(req, Permission.READ) { fabric, graph -> blockPanel(fabric, graph, req.query["id"].orEmpty()) } }
        r.get("/monitor/{fabric}/port", Permission.READ) { req -> panel(req, Permission.READ) { fabric, graph -> portPanel(fabric, graph, req.query["block"].orEmpty(), req.query["port"].orEmpty(), req.session!!.canFor(Permission.OPERATE, core.access.fabricById(fabric))) } }
        r.get("/monitor/{fabric}/tether", Permission.READ) { req ->
            panel(req, Permission.READ) { fabric, graph -> tetherPanel(fabric, graph, req.query["id"].orEmpty(), (req.query["n"]?.toIntOrNull() ?: 50).coerceIn(1, 100), req.session!!.canFor(Permission.OPERATE, core.access.fabricById(fabric))) }
        }
        r.post("/monitor/{fabric}/breakpoint", Permission.OPERATE) { req ->
            val fabric = req.params.getValue("fabric")
            val on = req.form["mode"] != "off"
            var done: String? = null
            val error = attempt {
                req.session!!.require(Permission.OPERATE, core.access.fabricById(fabric))
                val targets = targets(core.fabricGraph(fabric), req.form["tether"].orEmpty(), req.form["block"].orEmpty(), req.form["port"].orEmpty(), req.form["side"].orEmpty())
                if (targets.isEmpty()) throw cringle.management.ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "there is no tether to set a breakpoint on")
                targets.forEach { core.setBreakpoint(fabric, it.id, on) }
                done = "Breakpoint on ${targets.size} tether(s) ${if (on) "set" else "removed"}"
            }
            fragment(flash(error, done))
        }
        r.post("/monitor/{fabric}/resume", Permission.OPERATE) { req ->
            val fabric = req.params.getValue("fabric")
            var done: String? = null
            val error = attempt {
                req.session!!.require(Permission.OPERATE, core.access.fabricById(fabric))
                val targets = targets(core.fabricGraph(fabric), req.form["tether"].orEmpty(), req.form["block"].orEmpty(), req.form["port"].orEmpty(), req.form["side"].orEmpty())
                val released = if (targets.isEmpty()) core.resumeFabric(fabric, "", req.form["one"] == "on") else targets.sumOf { core.resumeFabric(fabric, it.id, req.form["one"] == "on") }
                done = "Released $released value(s)"
            }
            fragment(flash(error, done))
        }
    }

    /** Runs [action] for a request that needs [permission] on the fabric of the path; the answer for a visitor without it, `null` otherwise. */
    private suspend fun guarded(req: WebRequest, permission: Permission, action: suspend () -> Unit): WebResponse? {
        val fabric = req.params.getValue("fabric")
        if (!req.session!!.canFor(permission, core.access.fabricById(fabric))) return WebResponse(404, "{}".toByteArray(), "application/json")
        action()
        return null
    }

    private suspend fun panel(req: WebRequest, permission: Permission, render: suspend (String, GetFabricGraphResponse) -> Html): WebResponse {
        val fabric = req.params.getValue("fabric")
        if (!req.session!!.canFor(permission, core.access.fabricById(fabric))) return WebResponse(404, "".toByteArray(), "text/html")
        var out = Html("")
        val error = attempt { out = render(fabric, core.fabricGraph(fabric)) }
        return fragment(if (error != null) notice(error) else out)
    }

    /** The tethers a breakpoint or a resume applies to: one tether, the inputs of a block, or the tethers at one port (`side` in or out). */
    private fun targets(graph: GetFabricGraphResponse, tether: String, block: String, port: String, side: String): List<GraphTether> = when {
        tether.isNotEmpty() -> graph.tethersList.filter { it.id == tether }
        block.isNotEmpty() && port.isNotEmpty() -> graph.tethersList.filter { (side != "out" && it.toBlock == block && it.toPort == port) || (side != "in" && it.fromBlock == block && it.fromPort == port) }
        block.isNotEmpty() -> graph.tethersList.filter { it.toBlock == block }
        else -> emptyList()
    }

    private suspend fun graphJson(fabric: String, canOperate: Boolean): JsonObject {
        val g = core.fabricGraph(fabric)
        val debug = if (canOperate) try { core.debugState(fabric) } catch (e: Exception) { null } else null
        return buildJsonObject {
            put("fabric", g.fabricId)
            put("blueprint", g.blueprint)
            put("state", g.state.name.removePrefix("FABRIC_RUNTIME_STATE_").lowercase())
            put("failure", g.failure)
            put("canOperate", canOperate)
            put("now", System.currentTimeMillis())
            put(
                "blocks",
                buildJsonArray {
                    for (b in g.blocksList) {
                        add(
                            buildJsonObject {
                                put("id", b.id)
                                put("block", b.block)
                                put("state", blockState(b.state))
                                put("restarts", b.restarts)
                                put("errors", b.errors)
                                put("isolation", b.isolation)
                                put(
                                    "ports",
                                    buildJsonArray {
                                        for (p in b.portsList) add(buildJsonObject { put("name", p.name); put("out", p.out); put("varArg", p.varArg); put("count", p.count); put("types", JsonArray(p.tetherTypesList.map { JsonPrimitive(it) })) })
                                    },
                                )
                            },
                        )
                    }
                },
            )
            put(
                "tethers",
                buildJsonArray {
                    for (t in g.tethersList) {
                        add(
                            buildJsonObject {
                                put("id", t.id)
                                put("type", t.type)
                                put("fromBlock", t.fromBlock); put("fromPort", t.fromPort); put("fromIndex", t.fromIndex)
                                put("toBlock", t.toBlock); put("toPort", t.toPort); put("toIndex", t.toIndex)
                                put("remote", t.remote)
                                put("messages", t.messages); put("bytes", t.bytes); put("errors", t.errors)
                                put("last", t.lastActivityEpochMillis)
                                put("capturable", t.capturable)
                            },
                        )
                    }
                },
            )
            put("breakpoints", JsonArray((debug?.breakpointsList ?: emptyList()).map { JsonPrimitive(it) }))
            put("held", JsonObject((debug?.heldList ?: emptyList()).groupingBy { it.tether }.eachCount().mapValues { JsonPrimitive(it.value) }))
        }
    }

    private fun blockState(s: BlockRuntimeState): String = s.name.removePrefix("BLOCK_RUNTIME_STATE_").lowercase()

    private suspend fun fabricList(session: Session): Html {
        val rows = core.listFabrics(null, null)
            .filter { session.canFor(Permission.READ, core.access.fabric(it.machine, it.info.fabricId.value)) && it.info.state == cringle.engine.v1.FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING }
            .map { f ->
                val id = f.info.fabricId.value
                h("<tr><td><a href=\"/monitor/{}\"><strong>{}</strong></a></td><td>{} / {}</td><td>{}</td><td>{}</td></tr>", id, id, f.machine, f.engineId, f.info.blueprint, stateBadge(f.info.state.pretty()))
            }
        return dataTable(listOf("Fabric", "Engine", "Blueprint", "State"), rows, raw("No fabric is running. Start one on Fabrics."))
    }

    private fun page(fabric: String, error: String?, canOperate: Boolean): Html = html(
        h("<div id=\"monitor\" data-graph-url=\"/monitor/{}/graph\" data-url=\"/monitor/{}\">", fabric, fabric),
        raw("<div id=\"canvas\"><div id=\"drawflow\"></div></div>"),
        raw("<div id=\"editor-left\"><header id=\"editor-head\" class=\"float\">"),
        h("<h1>{}</h1>", fabric),
        raw("<p class=\"subtitle\" id=\"mon-summary\">Loading the fabric &hellip;</p>"),
        error?.let { h("<p class=\"error\">{}</p>", it) } ?: Html(""),
        raw("<div class=\"page-actions\"><a class=\"btn small\" href=\"/fabrics\">Fabrics</a><a class=\"btn small\" href=\"/monitor\">Monitor</a></div></header>"),
        raw("<aside id=\"mon-legend\" class=\"float\"><h2>Legend</h2><ul><li><span class=\"dot ok\"></span>running</li><li><span class=\"dot warn\"></span>starting, restarting</li><li><span class=\"dot bad\"></span>failed, stopped</li><li><span class=\"line active\"></span>values since the last refresh</li><li><span class=\"line held\"></span>breakpoint</li></ul></aside></div>"),
        raw("<div id=\"editor-bar\" class=\"float\">"),
        raw("<span id=\"mon-live\" class=\"live\" role=\"status\"><i></i><span>Loading</span></span>"),
        raw("<button type=\"button\" class=\"btn\" id=\"mon-pause\" aria-pressed=\"false\" title=\"Freeze the view to read the values (Space)\">Pause</button>"),
        raw("<div class=\"btn-group\" role=\"group\" aria-label=\"View\"><button type=\"button\" class=\"btn\" id=\"zoom-out\" title=\"Zoom out\">&minus;</button><button type=\"button\" class=\"btn\" id=\"zoom-in\" title=\"Zoom in\">+</button><button type=\"button\" class=\"btn\" id=\"zoom-fit\" title=\"Fit the graph into the view (F)\">Fit</button></div>"),
        raw("<input type=\"search\" id=\"mon-find\" placeholder=\"Find a block\" aria-label=\"Find a block\" autocomplete=\"off\">"),
        field("Refresh", raw("<select id=\"mon-every\"><option value=\"1000\">1 s</option><option value=\"2000\" selected>2 s</option><option value=\"5000\">5 s</option></select>")),
        field("Values", raw("<select id=\"mon-n\"><option>10</option><option selected>50</option><option>100</option></select>")),
        raw("</div>"),
        raw("<aside id=\"properties\" class=\"float\"><h2>Details<button type=\"button\" class=\"link\" id=\"mon-back\" hidden>&larr; Overview</button></h2><div id=\"mon-props\"><p>Loading &hellip;</p></div></aside>"),
        h(
            "<section id=\"mon-data\" class=\"float\" data-operate=\"{}\" hidden><header class=\"dock-head\"><strong>Tether</strong><code id=\"mon-data-title\"></code><span class=\"filter-note\"></span><input type=\"search\" id=\"mon-filter\" placeholder=\"Filter the values\" aria-label=\"Filter the values\" autocomplete=\"off\"><button type=\"button\" class=\"btn small\" id=\"mon-data-close\" aria-label=\"Close\">&times;</button></header><div id=\"mon-data-body\"></div></section>",
            canOperate.toString(),
        ),
        raw("</div>"),
    )

    private fun statRow(label: String, value: Any?): Html = h("<tr><th>{}</th><td>{}</td></tr>", label, value)

    private fun blockPanel(fabric: String, g: GetFabricGraphResponse, id: String): Html {
        val b = g.blocksList.firstOrNull { it.id == id } ?: return notice("There is no block $id.")
        val ins = g.tethersList.filter { it.toBlock == id }
        val outs = g.tethersList.filter { it.fromBlock == id }
        val portRows = b.portsList.map { p ->
            val count = g.tethersList.count { if (p.out) it.fromBlock == id && it.fromPort == p.name else it.toBlock == id && it.toPort == p.name }
            h("<tr><td><button type=\"button\" class=\"link\" data-port=\"{}\" data-side=\"{}\">{}</button></td><td>{}</td><td>{}</td><td>{}</td></tr>", p.name, if (p.out) "out" else "in", p.name, if (p.out) "out" else "in", p.tetherTypesList.joinToString(", "), count)
        }
        return html(
            h("<h3>{}</h3><p class=\"muted\"><code>{}</code></p>", b.id, b.block),
            raw("<table class=\"kv\">"),
            h("<tr><th>State</th><td>{}</td></tr>", stateBadge(blockState(b.state))),
            statRow("Isolation", b.isolation),
            statRow("Restarts", b.restarts),
            statRow("Errors", b.errors),
            if (b.lastError.isEmpty()) Html("") else statRow("Last error", b.lastError),
            statRow("Received", "${ins.sumOf { it.messages }} values, ${bytes(ins.sumOf { it.bytes })}"),
            statRow("Sent", "${outs.sumOf { it.messages }} values, ${bytes(outs.sumOf { it.bytes })}"),
            statRow("Last activity", lastActivity((ins + outs).maxOfOrNull { it.lastActivityEpochMillis } ?: 0)),
            raw("</table>"),
            if (portRows.isEmpty()) Html("") else html(raw("<h4>Ports</h4>"), dataTable(listOf("Port", "Direction", "Types", "Tethers"), portRows, Html(""))),
            if (ins.isEmpty()) Html("") else html(raw("<div class=\"row-actions\">"), breakForm(fabric, id, "", "", "on", "Break on inputs"), breakForm(fabric, id, "", "", "off", "Remove"), resumeForm(fabric, id, "", "", "Resume"), raw("</div>")),
        )
    }

    private fun portPanel(fabric: String, g: GetFabricGraphResponse, block: String, port: String, canOperate: Boolean): Html {
        val here = g.tethersList.filter { (it.toBlock == block && it.toPort == port) || (it.fromBlock == block && it.fromPort == port) }
        if (here.isEmpty()) return html(h("<h3>{}.{}</h3>", block, port), raw("<p class=\"muted\">No tether is connected to this port.</p>"))
        val rows = here.map { t ->
            h("<tr><td><button type=\"button\" class=\"link\" data-tether=\"{}\">{}</button></td><td>{}</td><td>{}</td></tr>", t.id, t.id, t.type, t.messages)
        }
        return html(
            h("<h3>{}.{}</h3><p class=\"muted\">{} tether(s) at this port</p>", block, port, here.size),
            dataTable(listOf("Tether", "Type", "Values"), rows, Html("")),
            if (canOperate) html(raw("<div class=\"row-actions\">"), breakForm(fabric, block, port, "", "on", "Break on port"), breakForm(fabric, block, port, "", "off", "Remove"), resumeForm(fabric, block, port, "", "Resume"), raw("</div>")) else Html(""),
        )
    }

    private suspend fun tetherPanel(fabric: String, g: GetFabricGraphResponse, id: String, n: Int, canOperate: Boolean): Html {
        val t = g.tethersList.firstOrNull { it.id == id } ?: return notice("There is no tether $id.")
        fun chip(value: Any?, label: String, tone: String = "") = h("<span class=\"mon-stat-chip {}\"><b>{}</b> {}</span>", tone, value, label)
        val head = h(
            "<div class=\"mon-stats\">{}{}{}{}{}</div>",
            chip(t.type, "type"), chip(t.messages, "values"), chip(bytes(t.bytes), "total"), chip(t.errors, "errors", if (t.errors > 0L) "bad" else ""), chip(lastActivity(t.lastActivityEpochMillis), "last activity"),
        )
        if (!canOperate) return html(head, raw("<p class=\"muted\">The values of a tether need the permission to operate the fabric.</p>"))
        val traffic = core.fabricTraffic(fabric, id, n)
        val debug = try { core.debugState(fabric) } catch (e: Exception) { null }
        val breaking = debug?.breakpointsList?.contains(id) == true
        val held = debug?.heldList.orEmpty().filter { it.tether == id }
        val actions = html(
            raw("<div class=\"row-actions\">"),
            if (breaking) breakForm(fabric, "", "", id, "off", "Remove breakpoint") else breakForm(fabric, "", "", id, "on", "Set breakpoint"),
            if (held.isNotEmpty() || breaking) html(resumeForm(fabric, "", "", id, "Resume all"), resumeOneForm(fabric, id)) else Html(""),
            raw("</div>"),
        )
        val heldTable = if (held.isEmpty()) Html("") else html(
            raw("<h4>Held back</h4>"),
            valueTable(listOf("", "Since", "State", "Value"), held.map { v -> h("<tr><td></td><td title=\"{}\">{}</td><td>{}</td><td class=\"val\"><code class=\"payload\">{}</code>{}</td></tr>", fullTime(v.sinceEpochMillis), time(v.sinceEpochMillis), badge("held", Tone.WARN), v.payload, copyButton()) }, Html("")),
        )
        val recent = if (!traffic.capturable) {
            raw("<p class=\"muted\">The values of this tether are not copied (a TCP or serial tether); the numbers above are all the monitor knows.</p>")
        } else {
            html(
                h("<h4>Last {} values</h4>", traffic.recordsCount),
                valueTable(
                    listOf("#", "Time", "Kind", "Value"),
                    traffic.recordsList.map { r -> h("<tr data-seq=\"{}\"><td>{}</td><td title=\"{}\">{}</td><td>{}</td><td class=\"val\"><code class=\"payload\">{}</code>{}</td></tr>", r.seq, r.seq, fullTime(r.timeEpochMillis), time(r.timeEpochMillis), badge(r.kind), r.payload, copyButton()) },
                    raw("No value has crossed this tether since the monitor was opened."),
                ),
            )
        }
        return html(h("<div class=\"dock-top\">{}{}</div>", head, actions), heldTable, recent)
    }

    private fun fullTime(millis: Long): String = Instant.ofEpochMilli(millis).toString().take(23).replace("T", " ")

    /** The time of day with milliseconds (UTC); the whole date is the title of the cell. */
    private fun time(millis: Long): String = fullTime(millis).substring(11)

    private fun copyButton(): Html = raw("<button type=\"button\" class=\"copy\" title=\"Copy the value\">Copy</button>")

    /** The table of values: fixed columns, the last one gets the room; an empty list shows [empty]. */
    private fun valueTable(headers: List<String>, rows: List<Html>, empty: Html): Html =
        if (rows.isEmpty()) h("<p class=\"muted\">{}</p>", empty) else html(raw("<table class=\"values\"><thead><tr>"), headers.map { h("<th>{}</th>", it) }, raw("</tr></thead><tbody>"), rows, raw("</tbody></table>"))

    private fun lastActivity(millis: Long): String = if (millis == 0L) "none yet" else "${((System.currentTimeMillis() - millis) / 1000).coerceAtLeast(0)} s ago"

    private fun bytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "%.1f KiB".format(n / 1024.0)
        else -> "%.1f MiB".format(n / 1048576.0)
    }

    private fun hidden(name: String, value: String): Html = if (value.isEmpty()) Html("") else h("<input type=\"hidden\" name=\"{}\" value=\"{}\">", name, value)

    private fun breakForm(fabric: String, block: String, port: String, tether: String, mode: String, label: String): Html = h(
        "<form class=\"inline\" hx-post=\"/monitor/{}/breakpoint\" hx-swap=\"none\">{}{}{}<input type=\"hidden\" name=\"mode\" value=\"{}\"><button class=\"btn small\">{}</button></form>",
        fabric, hidden("block", block), hidden("port", port), hidden("tether", tether), mode, label,
    )

    private fun resumeForm(fabric: String, block: String, port: String, tether: String, label: String): Html = h(
        "<form class=\"inline\" hx-post=\"/monitor/{}/resume\" hx-swap=\"none\">{}{}{}<button class=\"btn small\">{}</button></form>",
        fabric, hidden("block", block), hidden("port", port), hidden("tether", tether), label,
    )

    private fun resumeOneForm(fabric: String, tether: String): Html = h(
        "<form class=\"inline\" hx-post=\"/monitor/{}/resume\" hx-swap=\"none\"><input type=\"hidden\" name=\"tether\" value=\"{}\"><input type=\"hidden\" name=\"one\" value=\"on\"><button class=\"btn small\">Step</button></form>",
        fabric, tether,
    )
}
