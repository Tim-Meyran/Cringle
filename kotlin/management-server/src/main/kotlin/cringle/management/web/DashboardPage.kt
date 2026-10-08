// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.daemon.v1.EngineProcessState
import cringle.engine.v1.FabricRuntimeState
import cringle.management.ManagementCore

/** The dashboard (`GET /`): the size of the installation at a glance, and what needs attention, with a link to the page where it is handled. */
internal class DashboardPage(private val core: ManagementCore) {
    suspend fun content(): Html {
        val machines = core.listMachines()
        val engines = core.listEngines(null)
        val fabrics = core.listFabrics(null, null)
        val projects = core.deployedFabrics().map { it.project }.distinct()
        val runningEngines = engines.count { it.process.state == EngineProcessState.ENGINE_PROCESS_STATE_RUNNING }
        val runningFabrics = fabrics.count { it.info.state == FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING }
        val attention = ArrayList<Html>()
        for (m in machines.filter { !it.reachable }) attention += h("<li>{}<span>Machine <strong>{}</strong> is not reachable{}</span><a href=\"/machines\">Machines</a></li>", badge("machine", Tone.BAD), m.record.id, if (m.lastError.isEmpty()) "" else ": ${m.lastError}")
        for (e in engines.filter { it.process.state == EngineProcessState.ENGINE_PROCESS_STATE_CRASHED || (it.autostart && it.process.state == EngineProcessState.ENGINE_PROCESS_STATE_STOPPED) }) {
            attention += h("<li>{}<span>Engine <strong>{}</strong> on {} is {}{}</span><a href=\"/engines\">Engines</a></li>", badge("engine", Tone.BAD), e.process.engineId.value, e.machine, e.process.state.pretty(), if (e.process.lastError.isEmpty()) "" else ": ${e.process.lastError}")
        }
        for (f in fabrics.filter { it.info.state == FabricRuntimeState.FABRIC_RUNTIME_STATE_FAILED || (it.desiredRunning && it.info.state == FabricRuntimeState.FABRIC_RUNTIME_STATE_STOPPED) }) {
            attention += h("<li>{}<span>Fabric <strong>{}</strong> is {}{}</span><a href=\"/fabrics/{}/{}/{}\">Details</a></li>", badge("fabric", Tone.BAD), f.info.fabricId.value, f.info.state.pretty(), if (f.info.failure.isEmpty()) "" else ": ${f.info.failure}", f.machine, f.engineId, f.info.fabricId.value)
        }
        fun card(label: String, value: Int, detail: String, href: String) = h("<a class=\"card\" href=\"{}\"><span class=\"card-label\">{}</span><span class=\"card-value\">{}</span><span class=\"card-detail\">{}</span></a>", href, label, value, detail)
        return html(
            pageHeader("Dashboard", "The installation at a glance."),
            raw("<section class=\"cards\" aria-label=\"Summary\">"),
            card("Machines", machines.size, "${machines.count { it.reachable }} reachable", "/machines"),
            card("Engines", engines.size, "$runningEngines running", "/engines"),
            card("Fabrics", fabrics.size, "$runningFabrics running", "/fabrics"),
            card("Projects", projects.size, "deployed", "/deployments"),
            raw("</section>"),
            raw("<section class=\"panel\"><h2>Needs attention</h2>"),
            if (attention.isEmpty()) h("<p class=\"empty ok\">{}</p>", "Everything runs as it should.") else html(raw("<ul class=\"attention\">"), attention, raw("</ul>")),
            raw("</section>"),
            raw("<section class=\"panel\"><h2>Machines</h2>"),
            if (machines.isEmpty()) {
                raw("<p class=\"empty\">No machine yet. <a href=\"/machines\">Add the first machine</a> to start.</p>")
            } else {
                html(
                    raw("<table class=\"data\"><thead><tr><th>Machine</th><th>Daemon</th><th>State</th></tr></thead><tbody>"),
                    machines.map { m -> h("<tr><td>{}</td><td><code>{}</code></td><td>{}</td></tr>", m.record.id, m.record.daemonAddress, stateBadge(if (m.reachable) "reachable" else "not reachable")) },
                    raw("</tbody></table>"),
                )
            },
            raw("</section>"),
        )
    }
}
