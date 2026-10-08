// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import com.google.protobuf.Timestamp
import cringle.engine.v1.DwhKind
import cringle.engine.v1.DwhRetention
import cringle.engine.v1.LogLevel
import cringle.engine.v1.QueryLogsRequest
import cringle.management.ManagementCore
import cringle.management.ManagementException
import cringle.repository.v1.ListPackagesRequest
import cringle.repository.v1.PackageKind
import cringle.router.users.Permission
import java.time.Instant

/**
 * The pages for deployments and bindings, logs, metrics and the data warehouse (#209): the functions of `cringle deploy|undeploy|bind|logs|metrics|dwh`.
 * Like [OverviewPages], an action answers with the refreshed list and the message of the core above it.
 */
internal class DeploymentPages(private val core: ManagementCore) {
    fun register(web: WebServer) {
        val r = web.router
        web.navigation += listOf(NavItem("Deployments", "/deployments"), NavItem("Logs", "/logs"), NavItem("Metrics", "/metrics"), NavItem("Data warehouse", "/dwh"))

        r.get("/deployments", Permission.READ) { web.render("Deployments", it, section("Deployments", "deployments", deployments(it.session!!, null, null))) }
        r.get("/deployments/list", Permission.READ) { fragment(deployments(it.session!!, null, null)) }
        r.post("/deployments", Permission.OPERATE) { req ->
            var done: String? = null
            val error = attempt {
                val result = core.deploy(req.form["project"].orEmpty().trim(), req.form["version"].orEmpty().trim(), req.form["start"] == "on", req.form["relock"] == "on")
                done = "Deployed ${result.project} ${result.version} as " + result.fabrics.joinToString(", ") { "${it.info.fabricId.value} on ${it.machine}/${it.engineId}" }
            }
            fragment(deployments(req.session!!, error, done))
        }
        r.post("/deployments/{project}/undeploy", Permission.OPERATE) { req ->
            var done: String? = null
            val error = attempt { done = "Removed " + core.undeploy(req.params.getValue("project")).joinToString(", ").ifEmpty { "nothing" } }
            fragment(deployments(req.session!!, error, done))
        }
        r.post("/bindings", Permission.OPERATE) { req ->
            val error = attempt {
                val targets = req.form["fabrics"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                core.bind(req.form["project"].orEmpty().trim(), req.form["service"].orEmpty().trim(), targets)
            }
            fragment(deployments(req.session!!, error, null))
        }
        r.post("/bindings/{project}/{service}/unbind", Permission.OPERATE) { req ->
            fragment(deployments(req.session!!, attempt { core.unbind(req.params.getValue("project"), req.params.getValue("service")) }, null))
        }

        r.get("/logs", Permission.READ) { web.render("Logs", it, logsPage()) }
        r.get("/logs/list", Permission.READ) { req -> fragment(logs(req.query)) }

        r.get("/metrics", Permission.READ) { web.render("Metrics", it, section("Metrics", "metrics", metrics())) }
        r.get("/metrics/list", Permission.READ) { fragment(metrics()) }

        r.get("/dwh", Permission.READ) { web.render("Data warehouse", it, html(section("Data warehouse", "dwh", dwh(it.session!!, null, null)), raw("<div id=\"records\"></div>"))) }
        r.get("/dwh/list", Permission.READ) { fragment(dwh(it.session!!, null, null)) }
        r.get("/dwh/records", Permission.READ) { req -> fragment(records(req.query)) }
        r.post("/dwh/recording", Permission.OPERATE) { req ->
            val error = attempt {
                val retention = retention(req.form["maxAgeHours"], req.form["maxBytes"])
                core.setRecording(req.form["fabric"].orEmpty().trim(), req.form["mode"] == "on", retention)
            }
            fragment(dwh(req.session!!, error, null))
        }
        r.post("/dwh/retention", Permission.OPERATE) { req ->
            val error = attempt {
                core.setDwhRetention(
                    cringle.management.v1.SetDwhRetentionRequest.newBuilder().setFabric(req.form["fabric"].orEmpty()).setKind(kind(req.form["kind"].orEmpty()))
                        .setName(req.form["name"].orEmpty()).setRetention(retention(req.form["maxAgeHours"], req.form["maxBytes"])).build(),
                )
            }
            fragment(dwh(req.session!!, error, null))
        }
    }

    // --- deployments and bindings ---

    private suspend fun deployments(session: Session, error: String?, done: String?): Html {
        val states = core.listFabrics(null, null).associateBy { it.info.fabricId.value }
        val rows = core.deployedFabrics().groupBy { it.project to it.version }.map { (key, fabrics) ->
            h(
                "<tr><td>{}</td><td>{}</td><td>{}</td><td>{}</td></tr>",
                key.first, key.second,
                fabrics.map { f -> h("<div><a href=\"/fabrics/{}/{}/{}\">{}</a> on {}/{}: {}</div>", f.machine, f.engineId, f.fabricId, f.fabricId, f.machine, f.engineId, states[f.fabricId]?.info?.state?.pretty() ?: "unknown") },
                button(session, Permission.OPERATE, "Undeploy", "/deployments/${key.first}/undeploy", "Undeploy ${key.first}?"),
            )
        }
        val deployForm = if (!session.can(Permission.OPERATE)) {
            Html("")
        } else {
            val projects = core.repository().listPackages(ListPackagesRequest.newBuilder().setKind(PackageKind.PACKAGE_KIND_PROJECT).build()).packagesList.map { it.name }.distinct().sorted()
            html(
                raw("<form hx-post=\"/deployments\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\"><select name=\"project\">"),
                projects.map { h("<option>{}</option>", it) },
                raw("</select> <input name=\"version\" placeholder=\"version range (default: highest)\"> <label><input type=\"checkbox\" name=\"start\" checked> start</label> <label><input type=\"checkbox\" name=\"relock\"> relock</label> <button>Deploy</button></form>"),
            )
        }
        val bindingRows = core.listBindings("").map {
            h(
                "<tr><td>{}</td><td>{}</td><td>{}</td><td>{}</td></tr>",
                it.consumerProject, it.service, it.targets.joinToString(" > "),
                button(session, Permission.OPERATE, "Unbind", "/bindings/${it.consumerProject}/${it.service}/unbind", "Unbind ${it.service} of ${it.consumerProject}?"),
            )
        }
        val bindForm = if (!session.can(Permission.OPERATE)) {
            Html("")
        } else {
            raw("<form hx-post=\"/bindings\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\"><input name=\"project\" placeholder=\"consumer project\" required> <input name=\"service\" placeholder=\"service\" required> <input name=\"fabrics\" placeholder=\"fabrics in order, comma separated\" required> <button>Bind</button></form>")
        }
        return html(
            notice(error), info(done),
            raw("<table><tr><th>Project</th><th>Version</th><th>Fabrics</th><th></th></tr>"), rows, raw("</table>"), deployForm,
            raw("<h2>Bindings of services</h2><table><tr><th>Project</th><th>Service</th><th>Fabrics (first is used first)</th><th></th></tr>"), bindingRows, raw("</table>"), bindForm,
        )
    }

    // --- logs ---

    private fun logsPage(): Html = raw(
        """<h1>Logs</h1><div x-data="{auto: false, timer: null}">
<form x-ref="filters" hx-get="/logs/list" hx-target="#logs" hx-swap="morph:innerHTML" hx-trigger="submit, load, refresh">
<input name="machine" placeholder="machine"> <input name="engine" placeholder="engine"> <input name="fabric" placeholder="fabric"> <input name="block" placeholder="block">
<select name="level"><option value="">all levels</option><option>DEBUG</option><option>INFO</option><option>WARN</option><option>ERROR</option></select>
<input name="minutes" type="number" min="1" placeholder="last minutes"> <input name="limit" type="number" min="1" value="200">
<button>Show</button> <label><input type="checkbox" x-model="auto" @change="clearInterval(timer); if (auto) timer = setInterval(() => htmx.trigger(${'$'}refs.filters, 'refresh'), 5000)"> refresh every 5 s</label>
</form></div><div id="logs"></div>""",
    )

    private suspend fun logs(q: Map<String, String>): Html {
        val request = QueryLogsRequest.newBuilder().setFabric(q["fabric"].orEmpty().trim()).setBlock(q["block"].orEmpty().trim())
            .setLimit(q["limit"]?.toIntOrNull()?.coerceIn(1, 5000) ?: 200)
        q["level"]?.takeIf { it.isNotEmpty() }?.let { level -> LogLevel.entries.firstOrNull { it.name == "LOG_LEVEL_$level" }?.let { request.minLevel = it } }
        q["minutes"]?.toLongOrNull()?.let { request.since = Timestamp.newBuilder().setSeconds(Instant.now().minusSeconds(it * 60).epochSecond).build() }
        val result = try {
            core.queryLogs(q["machine"]?.trim()?.ifEmpty { null }, q["engine"]?.trim()?.ifEmpty { null }, request.build())
        } catch (e: Exception) {
            return notice(describe(e))
        }
        return html(
            result.problems.map { notice(it) },
            raw("<table><tr><th>Time</th><th>Engine</th><th>Fabric</th><th>Block</th><th>Level</th><th>Message</th><th>Source</th></tr>"),
            result.entries.map {
                val e = it.entry
                h(
                    "<tr><td>{}</td><td>{}/{}{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td></tr>",
                    Instant.ofEpochSecond(e.timestamp.seconds, e.timestamp.nanos.toLong()), it.machine, it.engineId, if (it.collected) " (collected)" else "",
                    e.fabric, e.block, e.level.name.removePrefix("LOG_LEVEL_"), e.message, e.source,
                )
            },
            raw("</table>"),
        )
    }

    // --- metrics ---

    private suspend fun metrics(): Html {
        val result = core.getMetrics(null, null)
        // an engine is a <details>: the browser keeps it open, and the refresh waits while one is open (see `cringleIdle`)
        return html(
            result.problems.map { notice(it) },
            result.metrics.map { m ->
                val x = m.metrics
                html(
                    h(
                        "<details class=\"engine\"><summary><strong>{}/{}</strong>: cpu {}, heap {} / {} MB, {} threads, {} fabrics</summary><ul>",
                        m.machine, m.engineId, if (x.processCpuLoad < 0) "?" else "%.0f %%".format(x.processCpuLoad * 100), x.heapUsedBytes / 1_048_576, x.heapMaxBytes / 1_048_576, x.threadCount, x.fabricsCount,
                    ),
                    x.fabricsList.map { f ->
                        html(
                            h("<li><strong>{}</strong>: {} errors, cpu {} ms<ul>", f.fabricId.value, f.errors, if (f.cpuTimeNs < 0) "?" else f.cpuTimeNs / 1_000_000),
                            f.blocksList.map { b -> h("<li>block {}: {} errors</li>", b.blockId, b.errors) },
                            f.tethersList.map { t -> h("<li>tether {} ({}): {} messages, {} bytes, {} errors</li>", t.tetherId, t.type, t.messages, t.bytes, t.errors) },
                            raw("</ul></li>"),
                        )
                    },
                    raw("</ul></details>"),
                )
            },
            if (result.metrics.isEmpty() && result.problems.isEmpty()) raw("<p>No engine is running.</p>") else Html(""),
        )
    }

    // --- data warehouse ---

    private fun kind(text: String): DwhKind = when (text.lowercase()) {
        "block" -> DwhKind.DWH_KIND_BLOCK
        "tether" -> DwhKind.DWH_KIND_TETHER
        else -> throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "kind must be block or tether")
    }

    private fun retention(hours: String?, bytes: String?): DwhRetention {
        val maxAge = hours?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull()?.takeIf { v -> v >= 0 } ?: throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "max age must be a number of hours") }
        val maxBytes = bytes?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toLongOrNull()?.takeIf { v -> v >= 0 } ?: throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "max bytes must be a number") }
        return DwhRetention.newBuilder().setMaxAgeMs(((maxAge ?: 0.0) * 3_600_000).toLong()).setMaxBytes(maxBytes ?: 0).build()
    }

    private suspend fun dwh(session: Session, error: String?, done: String?): Html {
        val (partitions, problems) = core.listDwhPartitions("")
        val rows = partitions.map { (_, _, p) ->
            val kind = if (p.kind == DwhKind.DWH_KIND_BLOCK) "block" else "tether"
            val retention = html(
                if (p.retention.maxAgeMs > 0) "${p.retention.maxAgeMs / 3_600_000.0} h" else "no age limit", ", ",
                if (p.retention.maxBytes > 0) "${p.retention.maxBytes} bytes" else "no size limit",
            )
            h(
                "<tr><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td><button hx-get=\"/dwh/records\" hx-vals='{\"fabric\": \"{}\", \"kind\": \"{}\", \"name\": \"{}\"}' hx-target=\"#records\" hx-swap=\"morph:innerHTML\">Records</button> {}</td></tr>",
                p.fabric, kind, p.name, p.bytes, retention, p.fabric, kind, p.name,
                if (!session.can(Permission.OPERATE)) {
                    Html("")
                } else {
                    h(
                        "<form hx-post=\"/dwh/retention\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\"><input type=\"hidden\" name=\"fabric\" value=\"{}\"><input type=\"hidden\" name=\"kind\" value=\"{}\"><input type=\"hidden\" name=\"name\" value=\"{}\"><input name=\"maxAgeHours\" placeholder=\"max age (hours)\" size=\"14\"> <input name=\"maxBytes\" placeholder=\"max bytes\" size=\"12\"> <button>Set retention</button></form>",
                        p.fabric, kind, p.name,
                    )
                },
            )
        }
        val recordForm = if (!session.can(Permission.OPERATE)) {
            Html("")
        } else {
            raw("<form hx-post=\"/dwh/recording\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\"><input name=\"fabric\" placeholder=\"fabric\" required> <select name=\"mode\"><option value=\"on\">record all tethers</option><option value=\"off\">record only tethers with record</option></select> <input name=\"maxAgeHours\" placeholder=\"max age (hours)\" size=\"14\"> <input name=\"maxBytes\" placeholder=\"max bytes\" size=\"12\"> <button>Set recording</button></form>")
        }
        return html(
            notice(error), info(done), problems.map { notice(it) },
            raw("<table><tr><th>Fabric</th><th>Kind</th><th>Name</th><th>Bytes</th><th>Retention</th><th></th></tr>"), rows, raw("</table>"), recordForm,
        )
    }

    private suspend fun records(q: Map<String, String>): Html {
        val result = try {
            core.queryDwh(
                cringle.management.v1.QueryDwhRequest.newBuilder().setFabric(q["fabric"].orEmpty()).setKind(kind(q["kind"].orEmpty())).setName(q["name"].orEmpty()).setLimit(q["limit"]?.toIntOrNull()?.coerceIn(1, 5000) ?: 100).build(),
            )
        } catch (e: Exception) {
            return notice(describe(e))
        }
        return html(
            h("<h2>{} {} of {}</h2>", q["kind"], q["name"], q["fabric"]),
            raw("<table><tr><th>Time</th><th>Payload</th><th>Tags</th></tr>"),
            result.recordsList.map { h("<tr><td>{}</td><td><code>{}</code></td><td>{}</td></tr>", Instant.ofEpochSecond(it.timestamp.seconds, it.timestamp.nanos.toLong()), it.payloadJson, it.tagsMap.entries.joinToString(", ") { t -> "${t.key}=${t.value}" }) },
            raw("</table>"),
        )
    }
}
