// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.UserRole
import cringle.router.users.Permission
import cringle.router.users.UserException
import cringle.router.users.RoleAssignment
import cringle.router.users.Scope
import cringle.router.users.UserManager
import java.time.Duration

/**
 * The pages for users, groups and tokens (#210): the functions of `cringle user|group|token ...`, all behind `MANAGE_USERS`. A token value is shown
 * once, in the answer to its creation; it is not kept. Only registered when the ManagementServer runs with `--auth`.
 */
internal class UserPages(private val users: UserManager) {
    fun register(web: WebServer) {
        val r = web.router
        web.navigation += listOf(NavItem("Users", "/users", Permission.MANAGE_USERS, "Administer"), NavItem("Groups", "/groups", Permission.MANAGE_USERS, "Administer"))

        r.get("/users", Permission.MANAGE_USERS) { web.render("Users", it, section("Users", "Who may sign in, with which roles. A token is shown once, when it is created.", "users", usersList(null, null))) }
        r.get("/users/list", Permission.MANAGE_USERS) { fragment(usersList(null, null)) }
        r.post("/users", Permission.MANAGE_USERS) { req ->
            val error = attempt {
                val roles = UserRole.entries.filter { req.form["role-${it.name}"] == "on" }.toSet()
                val groups = req.form["groups"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                users.createUser(req.form["name"].orEmpty().trim(), roles, groups)
            }
            fragment(usersList(error, null))
        }
        r.post("/users/{id}/delete", Permission.MANAGE_USERS) { req -> fragment(usersList(attempt { users.deleteUser(req.params.getValue("id")) }, null)) }
        r.post("/users/{id}/tokens", Permission.MANAGE_USERS) { req ->
            var created: Html? = null
            val error = attempt {
                val hours = req.form["hours"]?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: throw UserException(UserException.Kind.INVALID, "lifetime must be a number of hours") }
                val token = users.createToken(req.params.getValue("id"), req.form["label"].orEmpty().trim().ifEmpty { "web" }, hours?.let { Duration.ofHours(it) })
                created = h("Token <code data-copy=\"{}\" title=\"Click to copy\">{}</code> (shown once, copy it now).", token.secret, token.secret)
            }
            fragment(usersList(error, created))
        }
        for (grant in listOf(true, false)) {
            val verb = if (grant) "grant" else "revoke"
            r.post("/users/{id}/$verb", Permission.MANAGE_USERS) { req ->
                val error = attempt { change(req.form) { role, scope -> if (grant) users.grantUser(req.params.getValue("id"), role, scope) else users.revokeUser(req.params.getValue("id"), role, scope) } }
                fragment(usersList(error, null))
            }
            r.post("/groups/{name}/$verb", Permission.MANAGE_USERS) { req ->
                val error = attempt { change(req.form) { role, scope -> if (grant) users.grantGroup(req.params.getValue("name"), role, scope) else users.revokeGroup(req.params.getValue("name"), role, scope) } }
                fragment(groupsList(error))
            }
        }
        r.post("/tokens/{id}/revoke", Permission.MANAGE_USERS) { req -> fragment(usersList(attempt { users.revokeToken(req.params.getValue("id")) }, null)) }

        r.get("/groups", Permission.MANAGE_USERS) { web.render("Groups", it, section("Groups", "A group gives its members roles.", "groups", groupsList(null))) }
        r.get("/groups/list", Permission.MANAGE_USERS) { fragment(groupsList(null)) }
        r.post("/groups", Permission.MANAGE_USERS) { req ->
            val error = attempt { users.createGroup(req.form["name"].orEmpty().trim(), UserRole.entries.filter { req.form["role-${it.name}"] == "on" }.toSet()) }
            fragment(groupsList(error))
        }
    }

    /** Reads the role and the scope of a grant or revoke form and runs [action]. */
    private fun change(form: Map<String, String>, action: (UserRole, Scope) -> Unit) {
        val role = UserRole.entries.firstOrNull { it.name.equals(form["role"].orEmpty().trim().replace('-', '_'), ignoreCase = true) }
            ?: throw UserException(UserException.Kind.INVALID, "unknown role '${form["role"].orEmpty()}'")
        val text = form["scope"]?.trim()?.takeIf { it.isNotEmpty() } ?: "${form["kind"].orEmpty().trim()}:${form["name"].orEmpty().trim()}"
        val scope = try {
            Scope.parse(text)
        } catch (e: IllegalArgumentException) {
            throw UserException(UserException.Kind.INVALID, e.message ?: "invalid scope")
        }
        action(role, scope)
    }

    /** The scoped roles of a user or group ([base] is `/users/<id>` or `/groups/<name>`): the list with a revoke form each, and the form to grant one. */
    private fun scopedPopover(base: String, scoped: Set<RoleAssignment>): Html = h(
        "<details class=\"popover wide\"><summary>{}</summary><div class=\"popover-body\">{}<form class=\"form-row\" hx-post=\"{}/grant\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}{}{}<button class=\"btn primary small\">Grant</button></form></div></details>",
        if (scoped.isEmpty()) "none" else "${scoped.size} scoped",
        dataTable(
            listOf("Role", "For", ""),
            scoped.sortedWith(compareBy({ it.scope.encode() }, { it.role })).map { a ->
                h(
                    "<tr><td>{}</td><td><code>{}</code></td><td class=\"actions\"><form hx-post=\"{}/revoke\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\"><input type=\"hidden\" name=\"role\" value=\"{}\"><input type=\"hidden\" name=\"scope\" value=\"{}\"><button class=\"btn small\">Revoke</button></form></td></tr>",
                    a.role.name.lowercase().replace('_', '-'), a.scope.encode(), base, a.role.name, a.scope.encode(),
                )
            },
            raw("No scoped role."),
        ),
        base,
        field("Role", raw("<select name=\"role\"><option value=\"OPERATOR\">operator</option><option value=\"VIEWER\">viewer</option><option value=\"ADMIN\">admin</option></select>")),
        field("For", raw("<select name=\"kind\"><option>machine</option><option>project</option><option>fabric</option><option>function</option></select>")),
        field("Name", raw("<input name=\"name\" placeholder=\"m1, shop, trust, ...\" required>"), "function: trust, plugin-trust, users"),
    )

    private fun roleBoxes(): Html = html(
        raw("<fieldset class=\"roles\"><legend>Roles</legend>"),
        UserRole.entries.map { h("<label class=\"check\"><input type=\"checkbox\" name=\"role-{}\"> {}</label>", it.name, it.name.lowercase().replace('_', '-')) },
        raw("</fieldset>"),
    )

    private fun tags(values: Collection<String>): Html = if (values.isEmpty()) raw("<span class=\"muted\">none</span>") else html(values.map { h("<span class=\"tag\">{}</span> ", it) })

    private fun usersList(error: String?, created: Html?): Html {
        val rows = users.listUsers().map { v ->
            val u = v.user
            val tokens = users.listTokens(u.id)
            h(
                "<tr><td><strong>{}</strong></td><td>{}</td><td>{}</td><td>{}</td><td>{}</td>{}</tr>",
                u.name, tags(v.effectiveRoles.map { it.name.lowercase() }), tags(u.groups), scopedPopover("/users/${u.id}", u.scoped), tokenPopover(u.id, tokens),
                actionsCell(button(NO_SESSION_CHECK, Permission.AUTHENTICATED, "Delete", "/users/${u.id}/delete", "Delete user ${u.name} and its tokens?")),
            )
        }
        val createForm = h(
            "<form class=\"form-row\" hx-post=\"/users\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}{}{}<button class=\"btn primary\">Create user</button></form>",
            field("Name", raw("<input name=\"name\" required>")),
            roleBoxes(),
            field("Groups", raw("<input name=\"groups\" placeholder=\"none\">"), "comma separated"),
        )
        return html(
            flash(error, detail = created),
            dataTable(listOf("Name", "Roles", "Groups", "Scoped roles", "Tokens", ""), rows, raw("No user.")),
            formPanel("Create a user", "The new user gets no token yet: create one in the Tokens column. The value is shown once.", createForm),
        )
    }

    private fun tokenPopover(userId: String, tokens: List<cringle.router.users.TokenInfo>): Html = h(
        "<details class=\"popover wide\"><summary>{} active</summary><div class=\"popover-body\">{}<form class=\"form-row\" hx-post=\"/users/{}/tokens\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}{}<button class=\"btn primary small\">Create token</button></form></div></details>",
        tokens.count { !it.revoked }.toString(),
        dataTable(
            listOf("Label", "Created", "Expires", ""),
            tokens.map { t ->
                h(
                    "<tr><td>{}</td><td>{}</td><td>{}</td>{}</tr>",
                    t.label, t.createdAt.toString().take(16).replace("T", " "), t.expiresAt?.toString()?.take(16)?.replace("T", " ") ?: "never",
                    if (t.revoked) h("<td class=\"actions\">{}</td>", badge("revoked", Tone.BAD)) else actionsCell(button(NO_SESSION_CHECK, Permission.AUTHENTICATED, "Revoke", "/tokens/${t.id}/revoke", "Revoke token ${t.label}?")),
                )
            },
            raw("No token yet."),
        ),
        userId,
        field("Label", raw("<input name=\"label\" placeholder=\"laptop\">")),
        field("Lifetime (hours)", raw("<input name=\"hours\" placeholder=\"never expires\">")),
    )

    private fun groupsList(error: String?): Html {
        val rows = users.listGroups().map { h("<tr><td><strong>{}</strong></td><td>{}</td><td>{}</td></tr>", it.name, tags(it.roles.map { r -> r.name.lowercase() }), scopedPopover("/groups/${it.name}", it.scoped)) }
        val form = h(
            "<form class=\"form-row\" hx-post=\"/groups\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}{}<button class=\"btn primary\">Create group</button></form>",
            field("Name", raw("<input name=\"name\" required>")),
            roleBoxes(),
        )
        return html(
            flash(error),
            dataTable(listOf("Group", "Roles", "Scoped roles"), rows, raw("No group yet.")),
            formPanel("Create a group", "Members get the roles of the group in addition to their own.", form),
        )
    }

    private companion object {
        /** The routes of this class already demand MANAGE_USERS, so its buttons need no further check. */
        val NO_SESSION_CHECK = Session("", null, Permission.entries.toSet(), "", java.time.Instant.EPOCH)
    }
}
