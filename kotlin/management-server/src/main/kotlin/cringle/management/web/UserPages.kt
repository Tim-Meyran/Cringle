// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.UserRole
import cringle.router.users.Permission
import cringle.router.users.UserException
import cringle.router.users.UserManager
import java.time.Duration

/**
 * The pages for users, groups and tokens (#210): the functions of `cringle user|group|token ...`, all behind `MANAGE_USERS`. A token value is shown
 * once, in the answer to its creation; it is not kept. Only registered when the ManagementServer runs with `--auth`.
 */
internal class UserPages(private val users: UserManager) {
    fun register(web: WebServer) {
        val r = web.router
        web.navigation += listOf(NavItem("Users", "/users", Permission.MANAGE_USERS), NavItem("Groups", "/groups", Permission.MANAGE_USERS))

        r.get("/users", Permission.MANAGE_USERS) { web.render("Users", it, section("Users", "users", usersList(null, null))) }
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
                created = h("Token <code>{}</code> (shown once, copy it now).", token.secret)
            }
            fragment(usersList(error, created))
        }
        r.post("/tokens/{id}/revoke", Permission.MANAGE_USERS) { req -> fragment(usersList(attempt { users.revokeToken(req.params.getValue("id")) }, null)) }

        r.get("/groups", Permission.MANAGE_USERS) { web.render("Groups", it, section("Groups", "groups", groupsList(null))) }
        r.get("/groups/list", Permission.MANAGE_USERS) { fragment(groupsList(null)) }
        r.post("/groups", Permission.MANAGE_USERS) { req ->
            val error = attempt { users.createGroup(req.form["name"].orEmpty().trim(), UserRole.entries.filter { req.form["role-${it.name}"] == "on" }.toSet()) }
            fragment(groupsList(error))
        }
    }

    private fun roleBoxes(): Html = UserRole.entries.map { h("<label><input type=\"checkbox\" name=\"role-{}\"> {}</label> ", it.name, it.name.lowercase().replace('_', '-')) }.let { html(it) }

    private fun usersList(error: String?, created: Html?): Html {
        val rows = users.listUsers().map { v ->
            val u = v.user
            val tokens = users.listTokens(u.id)
            h(
                "<tr><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}{}</td></tr>",
                u.name, v.effectiveRoles.joinToString(", ") { it.name.lowercase() }, u.groups.joinToString(", "), u.id,
                button(NO_SESSION_CHECK, Permission.AUTHENTICATED, "Delete", "/users/${u.id}/delete", "Delete user ${u.name} and its tokens?"),
                h(
                    "<details><summary>Tokens ({})</summary><table>{}</table><form hx-post=\"/users/{}/tokens\" hx-target=\"#list\" hx-swap=\"innerHTML\"><input name=\"label\" placeholder=\"label\"> <input name=\"hours\" placeholder=\"lifetime in hours (empty: never expires)\" size=\"30\"> <button>Create token</button></form></details>",
                    tokens.count { !it.revoked }.toString(),
                    tokens.map { t ->
                        h(
                            "<tr><td>{}</td><td>{}</td><td>{}</td><td>{}</td></tr>",
                            t.label, t.createdAt, t.expiresAt?.toString() ?: "never expires",
                            if (t.revoked) raw("revoked") else button(NO_SESSION_CHECK, Permission.AUTHENTICATED, "Revoke", "/tokens/${t.id}/revoke", "Revoke token ${t.label}?"),
                        )
                    },
                    u.id,
                ),
            )
        }
        return html(
            notice(error), if (created == null) Html("") else html(raw("<p class=\"info\" role=\"status\">"), created, raw("</p>")),
            raw("<table><tr><th>Name</th><th>Roles</th><th>Groups</th><th>Id</th><th></th></tr>"), rows, raw("</table>"),
            raw("<form hx-post=\"/users\" hx-target=\"#list\" hx-swap=\"innerHTML\"><input name=\"name\" placeholder=\"name\" required> "), roleBoxes(),
            raw("<input name=\"groups\" placeholder=\"groups, comma separated\"> <button>Create user</button></form>"),
        )
    }

    private fun groupsList(error: String?): Html {
        val rows = users.listGroups().map { h("<tr><td>{}</td><td>{}</td></tr>", it.name, it.roles.joinToString(", ") { r -> r.name.lowercase() }) }
        return html(
            notice(error),
            raw("<table><tr><th>Group</th><th>Roles</th></tr>"), rows, raw("</table>"),
            raw("<form hx-post=\"/groups\" hx-target=\"#list\" hx-swap=\"innerHTML\"><input name=\"name\" placeholder=\"name\" required> "), roleBoxes(), raw("<button>Create group</button></form>"),
        )
    }

    private companion object {
        /** The routes of this class already demand MANAGE_USERS, so its buttons need no further check. */
        val NO_SESSION_CHECK = Session("", null, Permission.entries.toSet(), "", java.time.Instant.EPOCH)
    }
}
