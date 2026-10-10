// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.management.ManagementCore
import cringle.router.users.Permission
import java.time.Instant

/**
 * The page for remote debugging (#323, `cringle debug break|state|resume`): the fabrics that have a breakpoint or hold values, the values with
 * the output of the sender and the input of the receiver, and the forms to set or remove a breakpoint and to resume. Everything needs `OPERATE` for
 * the fabric. The list holds only state; what an action answers goes into `#flash`.
 */
internal class DebugPages(private val core: ManagementCore) {
    fun register(web: WebServer) {
        val r = web.router
        web.navigation += NavItem("Debugger", "/debug", Permission.OPERATE, "Operate")
        r.get("/debug", Permission.OPERATE) { web.render("Debugger", it, section("Debugger", "A breakpoint on a tether holds every value back before the receiving block gets it. Resume releases them, one or all.", "debug", debugList(it.session!!, null, null))) }
        r.get("/debug/list", Permission.OPERATE) { fragment(debugList(it.session!!, null, null)) }
        r.post("/debug/break", Permission.OPERATE) { req ->
            val fabric = req.form["fabric"].orEmpty().trim()
            val on = req.form["mode"] != "off"
            var done: String? = null
            val error = attempt {
                req.session!!.require(Permission.OPERATE, core.access.fabricById(fabric))
                core.setBreakpoint(fabric, req.form["tether"].orEmpty().trim(), on)
                done = "Breakpoint on ${req.form["tether"].orEmpty().trim()} of $fabric ${if (on) "set" else "removed"}"
            }
            fragment(debugList(req.session!!, error, done))
        }
        r.post("/debug/resume", Permission.OPERATE) { req ->
            val fabric = req.form["fabric"].orEmpty().trim()
            var done: String? = null
            val error = attempt {
                req.session!!.require(Permission.OPERATE, core.access.fabricById(fabric))
                done = "Released ${core.resumeFabric(fabric, req.form["tether"].orEmpty().trim(), req.form["one"] == "on")} value(s) of $fabric"
            }
            fragment(debugList(req.session!!, error, done))
        }
    }

    private suspend fun debugList(session: Session, error: String?, done: String?): Html {
        val running = core.listFabrics(null, null).filter { session.canFor(Permission.OPERATE, core.access.fabric(it.machine, it.info.fabricId.value)) }
        val sections = running.mapNotNull { f ->
            val id = f.info.fabricId.value
            val state = try {
                core.debugState(id)
            } catch (e: Exception) {
                return@mapNotNull null // not running, or no tethers: nothing to debug
            }
            if (state.breakpointsCount == 0 && state.heldCount == 0) return@mapNotNull null
            val held = state.heldList.map { v ->
                h(
                    "<tr><td><code>{}</code></td><td>{}</td><td>{}</td><td>{}</td><td><code class=\"payload\">{}</code></td></tr>",
                    v.tether, v.from, v.to, Instant.ofEpochMilli(v.sinceEpochMillis).toString().take(19).replace("T", " "), v.payload,
                )
            }
            h(
                "<section class=\"panel\"><h2>{}</h2><p>Breakpoints: {}</p>{}<div class=\"row-actions\">{}{}</div></section>",
                id,
                if (state.breakpointsCount == 0) raw("<span class=\"muted\">none</span>") else html(state.breakpointsList.map { b -> h("<code>{}</code> {}", b, breakForm(id, b, "off", "Remove")) }),
                dataTable(listOf("Tether", "From (output)", "To (input)", "Held since", "Value"), held, raw("No value is held right now.")),
                resumeForm(id, true, "Step (release the oldest)"),
                resumeForm(id, false, "Resume all"),
            )
        }
        val newForm = h(
            "<form class=\"form-row\" hx-post=\"/debug/break\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}{}<button class=\"btn primary\">Set breakpoint</button></form>",
            field("Fabric", html(raw("<input name=\"fabric\" list=\"debug-fabrics\" required><datalist id=\"debug-fabrics\">"), running.map { h("<option value=\"{}\">", it.info.fabricId.value) }, raw("</datalist>"))),
            field("Tether", raw("<input name=\"tether\" placeholder=\"c.out -> s.in\" required>"), "as the data warehouse names it"),
        )
        return html(
            flash(error, done),
            if (sections.isEmpty()) raw("<p class=\"empty\">No breakpoint is set.</p>") else html(sections),
            formPanel("Set a breakpoint", "Values on the tether are held back before the receiving block gets them; the sender goes on until the buffer of the tether is full.", newForm),
        )
    }

    private fun breakForm(fabric: String, tether: String, mode: String, label: String): Html = h(
        "<form class=\"inline\" hx-post=\"/debug/break\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\"><input type=\"hidden\" name=\"fabric\" value=\"{}\"><input type=\"hidden\" name=\"tether\" value=\"{}\"><input type=\"hidden\" name=\"mode\" value=\"{}\"><button class=\"btn small\">{}</button></form>",
        fabric, tether, mode, label,
    )

    private fun resumeForm(fabric: String, one: Boolean, label: String): Html = h(
        "<form class=\"inline\" hx-post=\"/debug/resume\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\"><input type=\"hidden\" name=\"fabric\" value=\"{}\">{}<button class=\"btn small\">{}</button></form>",
        fabric, if (one) raw("<input type=\"hidden\" name=\"one\" value=\"on\">") else Html(""), label,
    )
}
