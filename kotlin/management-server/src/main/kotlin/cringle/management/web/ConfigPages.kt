// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.management.ManagementCore
import cringle.router.users.Permission
import cringle.router.users.Scope

/**
 * The page for the settings of a machine (#317): the functions of `cringle config list|get|set|unset`. Reading needs `READ`, changing `ADMINISTER`,
 * both for the machine or for the function `config`. The daemon of the machine restarts what a change affects and says so; a change of the daemon
 * itself is saved and the page says that the daemon has to be restarted.
 */
internal class ConfigPages(private val core: ManagementCore) {
    fun register(web: WebServer) {
        val r = web.router
        web.navigation += NavItem("Configuration", "/config/local", Permission.READ, "Administer")
        r.get("/config", Permission.READ) { WebResponse.redirect("/config/local") }
        r.get("/config/{machine}", Permission.READ) { req ->
            val machine = req.params.getValue("machine")
            web.render("Configuration", req, section("Configuration", "The settings of the services of a machine. A change is written to its settings file and applied by its daemon.", "config/$machine", configList(req.session!!, machine, null, null)))
        }
        r.get("/config/{machine}/list", Permission.READ) { req -> fragment(configList(req.session!!, req.params.getValue("machine"), null, null)) }
        r.post("/config/{machine}/{key}", Permission.ADMINISTER) { req ->
            val machine = req.params.getValue("machine")
            var done: String? = null
            val error = attempt {
                req.session!!.require(Permission.ADMINISTER, scopes(machine))
                done = changeText(core.setConfig(machine, req.params.getValue("key"), req.form["value"].orEmpty().trim()))
            }
            fragment(configList(req.session!!, machine, error, done))
        }
        r.post("/config/{machine}/{key}/unset", Permission.ADMINISTER) { req ->
            val machine = req.params.getValue("machine")
            var done: String? = null
            val error = attempt {
                req.session!!.require(Permission.ADMINISTER, scopes(machine))
                done = changeText(core.unsetConfig(machine, req.params.getValue("key")))
            }
            fragment(configList(req.session!!, machine, error, done))
        }
    }

    private fun scopes(machine: String): List<Scope> = core.access.machine(machine) + core.access.function("config")

    private fun changeText(change: cringle.daemon.v1.ConfigChange): String = buildString {
        append(change.entry.key).append(if (change.entry.isSet) " is " else " is back to its default, ").append(change.entry.value.ifEmpty { "empty" }).append(". ")
        if (change.restartedList.isNotEmpty()) append(change.restartedList.joinToString(", ").replaceFirstChar { it.uppercase() }).append(". ")
        if (change.note.isNotEmpty()) append(change.note).append('.')
    }.trim()

    private suspend fun configList(session: Session, machine: String, error: String?, done: String?): Html {
        val machines = runCatching { core.listMachines().map { it.record.id }.filter { session.canFor(Permission.READ, core.access.machine(it) + core.access.function("config")) } }.getOrDefault(emptyList())
        val nav = if (machines.size < 2) {
            Html("")
        } else {
            h("<p class=\"machines\">Machine: {}</p>", html(machines.map { h("<a class=\"{}\" href=\"/config/{}\">{}</a> ", if (it == machine) "current" else "", it, it) }))
        }
        val result = try {
            core.listConfig(machine)
        } catch (e: Exception) {
            return html(flash(error ?: describe(e)), nav, h("<p class=\"empty\">The settings of machine {} cannot be read.</p>", machine))
        }
        val editable = session.canFor(Permission.ADMINISTER, scopes(machine))
        val rows = result.entriesList.map { e ->
            val value = h("<code>{}</code>", e.value.ifEmpty { "(empty)" })
            val edit = if (editable) {
                rowDialog(
                    "config-$machine-${e.key}", "Edit", "Setting ${e.key}", "${e.description} Changing it restarts: ${e.restartsList.joinToString(", ").ifEmpty { "nothing" }}.",
                    h(
                        "<form class=\"form-row\" hx-post=\"/config/{}/{}\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}<button type=\"button\" class=\"btn\" data-close>Cancel</button><button class=\"btn primary\">Save</button></form>",
                        machine, e.key, field("Value", h("<input name=\"value\" value=\"{}\" aria-label=\"{}\">", e.value, e.key), "the default is " + e.defaultValue.ifEmpty { "empty" }),
                    ),
                )
            } else {
                Html("")
            }
            h(
                "<tr><td><code>{}</code></td><td>{}</td><td>{}</td><td>{}{}<br><small class=\"muted\">{} Changing it restarts: {}.</small></td>{}</tr>",
                e.key, value, h("<code>{}</code>", e.defaultValue.ifEmpty { "(empty)" }), if (e.isSet) badge("set", Tone.INFO) else raw("<span class=\"muted\">default</span>"),
                if (e.overridden) h(" {}", badge("argument wins", Tone.WARN)) else Html(""), e.description, e.restartsList.joinToString(", "),
                actionsCell(edit, if (e.isSet) button(session, Permission.ADMINISTER, "Reset", "/config/$machine/${e.key}/unset", null, scopes(machine)) else Html("")),
            )
        }
        return html(
            flash(error, done),
            nav,
            result.problemsList.takeIf { it.isNotEmpty() }?.let { h("<p class=\"notice error\" role=\"alert\">Settings file: {}</p>", it.joinToString("; ")) } ?: Html(""),
            dataTable(listOf("Key", "Value", "Default", "State", ""), rows, raw("No settings.")),
        )
    }
}
