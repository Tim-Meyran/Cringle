// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import com.google.protobuf.Timestamp
import cringle.engine.v1.DwhKind
import cringle.engine.v1.DwhRetention
import cringle.engine.v1.LogLevel
import cringle.engine.v1.QueryLogsRequest
import cringle.management.LogResult
import cringle.management.ManagementCore
import cringle.management.MetricsResult
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
        web.navigation += listOf(NavItem("Deployments", "/deployments", group = "Operate"), NavItem("Logs", "/logs", group = "Observe"), NavItem("Metrics", "/metrics", group = "Observe"), NavItem("Data warehouse", "/dwh", group = "Observe"))

        r.get("/deployments", Permission.READ) { web.render("Deployments", it, section("Deployments", "Projects that run on engines, and how their service dependencies are bound.", "deployments", deployments(it.session!!, null, null))) }
        r.get("/deployments/list", Permission.READ) { fragment(deployments(it.session!!, null, null)) }
        r.post("/deployments", Permission.OPERATE) { req ->
            var done: String? = null
            val error = attempt {
                req.session!!.require(Permission.OPERATE, core.access.project(req.form["project"].orEmpty().trim()))
                val result = core.deploy(req.form["project"].orEmpty().trim(), req.form["version"].orEmpty().trim(), req.form["start"] == "on", req.form["relock"] == "on", req.form["stopFirst"] != "on")
                done = "Deployed ${result.project} ${result.version} as " + result.fabrics.joinToString(", ") { "${it.info.fabricId.value} on ${it.machine}/${it.engineId}" } + " (${result.strategy})"
            }
            fragment(deployments(req.session!!, error, done))
        }
        r.post("/deployments/{project}/rollback", Permission.OPERATE) { req ->
            var done: String? = null
            val error = attempt {
                req.session!!.require(Permission.OPERATE, core.access.project(req.params.getValue("project")))
                val result = core.rollback(req.params.getValue("project"), req.form["version"]?.trim()?.ifEmpty { null })
                done = "Went back to ${result.project} ${result.version} (${result.strategy})"
            }
            fragment(deployments(req.session!!, error, done))
        }
        r.post("/deployments/{project}/undeploy", Permission.OPERATE) { req ->
            var done: String? = null
            val error = attempt { req.session!!.require(Permission.OPERATE, core.access.project(req.params.getValue("project"))); done = "Removed " + core.undeploy(req.params.getValue("project")).joinToString(", ").ifEmpty { "nothing" } }
            fragment(deployments(req.session!!, error, done))
        }
        r.post("/bindings", Permission.OPERATE) { req ->
            val error = attempt {
                req.session!!.require(Permission.OPERATE, core.access.project(req.form["project"].orEmpty().trim()))
                val targets = req.form["fabrics"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                core.bind(req.form["project"].orEmpty().trim(), req.form["service"].orEmpty().trim(), targets)
            }
            fragment(deployments(req.session!!, error, null))
        }
        r.post("/bindings/{project}/{service}/unbind", Permission.OPERATE) { req ->
            fragment(deployments(req.session!!, attempt { req.session!!.require(Permission.OPERATE, core.access.project(req.params.getValue("project"))); core.unbind(req.params.getValue("project"), req.params.getValue("service")) }, null))
        }

        r.get("/logs", Permission.READ) { web.render("Logs", it, logsPage()) }
        r.get("/logs/list", Permission.READ) { req -> fragment(logs(req.session!!, req.query)) }

        r.get("/metrics", Permission.READ) { web.render("Metrics", it, section("Metrics", "What the engines and their fabrics do right now. Open an engine for its fabrics, blocks and tethers.", "metrics", metrics(it.session!!))) }
        r.get("/metrics/list", Permission.READ) { fragment(metrics(it.session!!)) }

        r.get("/dwh", Permission.READ) { web.render("Data warehouse", it, html(section("Data warehouse", "What the engines recorded: block data and tether messages, with their retention.", "dwh", dwh(it.session!!, null, null)), raw("<div id=\"records\"></div>"))) }
        r.get("/dwh/list", Permission.READ) { fragment(dwh(it.session!!, null, null)) }
        r.get("/dwh/records", Permission.READ) { req -> fragment(records(req.session!!, req.query)) }
        r.post("/dwh/recording", Permission.OPERATE) { req ->
            val error = attempt {
                val retention = retention(req.form["maxAgeHours"], req.form["maxBytes"])
                req.session!!.require(Permission.OPERATE, core.access.fabricById(req.form["fabric"].orEmpty().trim()))
                core.setRecording(req.form["fabric"].orEmpty().trim(), req.form["mode"] == "on", retention)
            }
            fragment(dwh(req.session!!, error, null))
        }
        r.post("/dwh/retention", Permission.OPERATE) { req ->
            val error = attempt {
                req.session!!.require(Permission.OPERATE, core.access.fabricById(req.form["fabric"].orEmpty()))
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
        val visible = core.deployedFabrics().filter { session.canFor(Permission.READ, core.access.fabric(it.machine, it.fabricId)) }
        val rows = visible.groupBy { it.project to it.version }.map { (key, fabrics) ->
            val project = core.access.project(key.first)
            h(
                "<tr><td><strong>{}</strong></td><td>{}</td><td>{}</td>{}</tr>",
                key.first, key.second,
                fabrics.map { f -> h("<div class=\"fabric-line\"><a href=\"/fabrics/{}/{}/{}\">{}</a> <span class=\"muted\">on {}/{}</span> {}</div>", f.machine, f.engineId, f.fabricId, f.fabricId, f.machine, f.engineId, stateBadge(states[f.fabricId]?.info?.state?.pretty() ?: "unknown")) },
                actionsCell(
                    core.rollbackTargets(key.first).firstOrNull()?.let { button(session, Permission.OPERATE, "Roll back", "/deployments/${key.first}/rollback", "Go back to ${key.first} $it? The running version is replaced, its data is migrated back by the downgrade processors.", project) } ?: Html(""),
                    button(session, Permission.OPERATE, "Undeploy", "/deployments/${key.first}/undeploy", "Undeploy ${key.first}? Its fabrics are stopped and removed.", project),
                ),
            )
        }
        val deployForm = if (!session.canAnywhere(Permission.OPERATE)) {
            Html("")
        } else {
            val projects = core.repository().listPackages(ListPackagesRequest.newBuilder().setKind(PackageKind.PACKAGE_KIND_PROJECT).build()).packagesList.map { it.name }.distinct().sorted().filter { session.canFor(Permission.OPERATE, core.access.project(it)) }
            if (projects.isEmpty()) {
                raw("<p class=\"empty\">The repository has no project yet. Build one in the blueprint editor (Drafts) or publish a package (Packages).</p>")
            } else {
                h(
                    "<form class=\"form-row\" hx-post=\"/deployments\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}{}<label class=\"check\"><input type=\"checkbox\" name=\"start\" checked> Start</label><label class=\"check\"><input type=\"checkbox\" name=\"relock\"> Resolve again (relock)</label><label class=\"check\" title=\"Stop the running fabrics of the project first instead of starting the new ones next to them\"><input type=\"checkbox\" name=\"stopFirst\"> Stop the old ones first</label><button class=\"btn primary\">Deploy</button></form>",
                    field("Project", html(raw("<select name=\"project\">"), projects.map { h("<option>{}</option>", it) }, raw("</select>"))),
                    field("Version range", raw("<input name=\"version\" placeholder=\"highest\">"), "empty: the highest release"),
                )
            }
        }
        val bindingRows = core.listBindings("").filter { session.canFor(Permission.READ, core.access.project(it.consumerProject)) }.map {
            h(
                "<tr><td>{}</td><td>{}</td><td>{}</td>{}</tr>",
                it.consumerProject, it.service, it.targets.joinToString(" \u203a "),
                actionsCell(button(session, Permission.OPERATE, "Unbind", "/bindings/${it.consumerProject}/${it.service}/unbind", "Unbind ${it.service} of ${it.consumerProject}?", core.access.project(it.consumerProject))),
            )
        }
        val bindForm = if (!session.canAnywhere(Permission.OPERATE)) {
            Html("")
        } else {
            h(
                "<form class=\"form-row\" hx-post=\"/bindings\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}{}{}<button class=\"btn primary\">Bind</button></form>",
                field("Consumer project", raw("<input name=\"project\" required>")),
                field("Service", raw("<input name=\"service\" required>")),
                field("Fabrics", raw("<input name=\"fabrics\" required>"), "in order of preference, comma separated"),
            )
        }
        return html(
            flash(error, done),
            dataTable(listOf("Project", "Version", "Fabrics", ""), rows, raw("Nothing is deployed yet.")),
            formPanel("Deploy a project", "A project of the repository is placed on engines by the roles and labels of its fabric configs.", deployForm),
            raw("<h2>Bindings of service dependencies</h2>"),
            dataTable(listOf("Project", "Service", "Fabrics (the first is used first)", ""), bindingRows, raw("No binding. A project that depends on a service of another project needs one before it can be deployed.")),
            formPanel("Bind a service", "Connects the service dependency of a project to the fabrics that provide it; the next fabric takes over when the first fails.", bindForm),
        )
    }

    // --- logs ---

    private fun logsPage(): Html = html(
        pageHeader("Logs", "What the blocks and engines wrote. Newest at the bottom; lines kept by the daemon of a stopped engine are marked collected."),
        raw(
            """<section class="panel" x-data="{auto: false, timer: null}">
<form class="form-row" x-ref="filters" hx-get="/logs/list" hx-target="#logs" hx-swap="morph:innerHTML" hx-trigger="submit, load, refresh">
<label class="field"><span>Machine</span><input name="machine" placeholder="all"></label>
<label class="field"><span>Engine</span><input name="engine" placeholder="all"></label>
<label class="field"><span>Fabric</span><input name="fabric" placeholder="all"></label>
<label class="field"><span>Block</span><input name="block" placeholder="all"></label>
<label class="field"><span>Level</span><select name="level"><option value="">all</option><option>DEBUG</option><option>INFO</option><option>WARN</option><option>ERROR</option></select></label>
<label class="field"><span>Last minutes</span><input name="minutes" type="number" min="1" placeholder="any" size="6"></label>
<label class="field"><span>Lines</span><input name="limit" type="number" min="1" value="200" size="6"></label>
<button class="btn primary">Show</button>
<label class="check"><input type="checkbox" x-model="auto" @change="clearInterval(timer); if (auto) timer = setInterval(() => htmx.trigger(${'$'}refs.filters, 'refresh'), 5000)"> Refresh every 5 s</label>
</form></section><div id="logs"></div>""",
        ),
    )

    private suspend fun logs(session: Session, q: Map<String, String>): Html {
        val request = QueryLogsRequest.newBuilder().setFabric(q["fabric"].orEmpty().trim()).setBlock(q["block"].orEmpty().trim())
            .setLimit(q["limit"]?.toIntOrNull()?.coerceIn(1, 5000) ?: 200)
        q["level"]?.takeIf { it.isNotEmpty() }?.let { level -> LogLevel.entries.firstOrNull { it.name == "LOG_LEVEL_$level" }?.let { request.minLevel = it } }
        q["minutes"]?.toLongOrNull()?.let { request.since = Timestamp.newBuilder().setSeconds(Instant.now().minusSeconds(it * 60).epochSecond).build() }
        val result = try {
            val machine = q["machine"]?.trim()?.ifEmpty { null }
            if (machine != null) session.require(Permission.READ, core.access.machine(machine))
            core.queryLogs(machine, q["engine"]?.trim()?.ifEmpty { null }, request.build()).let { r -> LogResult(r.entries.filter { session.canFor(Permission.READ, core.access.machine(it.machine)) }, r.problems) }
        } catch (e: Exception) {
            return notice(describe(e))
        }
        return html(
            result.problems.map { notice(it) },
            dataTable(
                listOf("Time", "Engine", "Fabric", "Block", "Level", "Message", "Source"),
                result.entries.map {
                    val e = it.entry
                    val level = e.level.name.removePrefix("LOG_LEVEL_")
                    h(
                        "<tr class=\"log\"><td class=\"nowrap\">{}</td><td>{}/{}{}</td><td>{}</td><td>{}</td><td>{}</td><td class=\"message\">{}</td><td>{}</td></tr>",
                        Instant.ofEpochSecond(e.timestamp.seconds, e.timestamp.nanos.toLong()).toString().replace("T", " ").removeSuffix("Z"), it.machine, it.engineId, if (it.collected) h(" {}", badge("collected")) else Html(""),
                        e.fabric, e.block, badge(level.lowercase(), when (level) { "ERROR" -> Tone.BAD; "WARN" -> Tone.WARN; "INFO" -> Tone.INFO; else -> Tone.NEUTRAL }), e.message, if (e.source.isEmpty()) Html("") else h("<code>{}</code>", e.source),
                    )
                },
                raw("No log lines for this filter."),
            ),
        )
    }

    // --- metrics ---

    private suspend fun metrics(session: Session): Html {
        val result = core.getMetrics(null, null).let { r -> MetricsResult(r.metrics.filter { session.canFor(Permission.READ, core.access.machine(it.machine)) }, r.problems) }
        // an engine is a <details>: the browser keeps it open, and the refresh waits while one is open (see `cringleIdle`)
        return html(
            result.problems.map { notice(it) },
            result.metrics.map { m ->
                val x = m.metrics
                html(
                    h(
                        "<details class=\"engine\"><summary><strong>{}/{}</strong><span class=\"stat\">cpu {}</span><span class=\"stat\">heap {} / {} MB</span><span class=\"stat\">{} threads</span><span class=\"stat\">{} fabrics</span></summary><ul>",
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
            if (result.metrics.isEmpty() && result.problems.isEmpty()) raw("<p class=\"empty\">No engine is running. Start one on the Engines page.</p>") else Html(""),
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
        val (allPartitions, problems) = core.listDwhPartitions("")
        val partitions = allPartitions.filter { (machine, _, p) -> session.canFor(Permission.READ, core.access.fabric(machine, p.fabric)) }
        val canOperate = session.canAnywhere(Permission.OPERATE)
        val rows = partitions.map { (machine, _, p) ->
            val kind = if (p.kind == DwhKind.DWH_KIND_BLOCK) "block" else "tether"
            val retention = html(
                if (p.retention.maxAgeMs > 0) "${p.retention.maxAgeMs / 3_600_000.0} h" else "no age limit", ", ",
                if (p.retention.maxBytes > 0) "${p.retention.maxBytes} bytes" else "no size limit",
            )
            h(
                "<tr><td>{}</td><td>{}</td><td>{}</td><td class=\"num\">{}</td><td>{}</td>{}</tr>",
                p.fabric, kind, p.name, p.bytes, retention,
                actionsCell(
                    h("<button hx-get=\"/dwh/records\" hx-vals='{\"fabric\": \"{}\", \"kind\": \"{}\", \"name\": \"{}\"}' hx-target=\"#records\" hx-swap=\"morph:innerHTML\">Records</button>", p.fabric, kind, p.name),
                    if (!session.canFor(Permission.OPERATE, core.access.fabric(machine, p.fabric))) {
                        Html("")
                    } else {
                        rowDialog(
                            "retention-${p.fabric}-$kind-${p.name}", "Retention", "Retention of ${p.name}", "How long and how much of this partition is kept; empty means no limit.",
                            h(
                                "<form class=\"form-row\" hx-post=\"/dwh/retention\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\"><input type=\"hidden\" name=\"fabric\" value=\"{}\"><input type=\"hidden\" name=\"kind\" value=\"{}\"><input type=\"hidden\" name=\"name\" value=\"{}\">{}{}<button class=\"btn primary\">Save retention</button></form>",
                                p.fabric, kind, p.name,
                                field("Max age (hours)", raw("<input name=\"maxAgeHours\" placeholder=\"no limit\">")),
                                field("Max size (bytes)", raw("<input name=\"maxBytes\" placeholder=\"no limit\">")),
                            ),
                        )
                    },
                ),
            )
        }
        val recordForm = if (!canOperate) {
            Html("")
        } else {
            h(
                "<form class=\"form-row\" hx-post=\"/dwh/recording\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}{}{}{}<button class=\"btn primary\">Apply</button></form>",
                field("Fabric", raw("<input name=\"fabric\" required>")),
                field("Record", raw("<select name=\"mode\"><option value=\"on\">all typed tethers</option><option value=\"off\">only tethers with a record setting</option></select>")),
                field("Max age (hours)", raw("<input name=\"maxAgeHours\" placeholder=\"no limit\">")),
                field("Max size (bytes)", raw("<input name=\"maxBytes\" placeholder=\"no limit\">")),
            )
        }
        return html(
            flash(error, done), problems.map { notice(it) },
            dataTable(listOf("Fabric", "Kind", "Name", "Bytes", "Retention", ""), rows, raw("Nothing is recorded yet. Mark a tether with `record` in its blueprint, or switch the recording of a fabric on below.")),
            formPanel("Recording", "Switches the recording of a fabric on or off; the retention is the default for the tethers that have none of their own.", recordForm),
        )
    }

    private suspend fun records(session: Session, q: Map<String, String>): Html {
        val result = try {
            session.require(Permission.READ, core.access.fabricById(q["fabric"].orEmpty()))
            core.queryDwh(
                cringle.management.v1.QueryDwhRequest.newBuilder().setFabric(q["fabric"].orEmpty()).setKind(kind(q["kind"].orEmpty())).setName(q["name"].orEmpty()).setLimit(q["limit"]?.toIntOrNull()?.coerceIn(1, 5000) ?: 100).build(),
            )
        } catch (e: Exception) {
            return notice(describe(e))
        }
        return html(
            h("<h2>{} {} of {}</h2>", q["kind"], q["name"], q["fabric"]),
            dataTable(
                listOf("Time", "Payload", "Tags"),
                result.recordsList.map { h("<tr><td class=\"nowrap\">{}</td><td><code>{}</code></td><td>{}</td></tr>", Instant.ofEpochSecond(it.timestamp.seconds, it.timestamp.nanos.toLong()).toString().replace("T", " ").removeSuffix("Z"), it.payloadJson, it.tagsMap.entries.joinToString(", ") { t -> "${t.key}=${t.value}" }) },
                raw("No records in this partition yet."),
            ),
        )
    }
}
