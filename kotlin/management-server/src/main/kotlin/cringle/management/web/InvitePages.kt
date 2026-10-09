// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.UserRole
import cringle.router.users.InviteInfo
import cringle.router.users.InviteState
import cringle.router.users.Permission
import cringle.router.users.RoleAssignment
import cringle.router.users.Scope
import cringle.router.users.ScopeKind
import cringle.router.users.UserException
import cringle.router.users.UserManager
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Invite links (#303). An administrator creates an invite (roles, groups, scoped roles, lifetime) and gets a one-time link `<base>/invite/<secret>` with its
 * QR code; whoever opens it names a user, which is created with exactly what the invite gives, and gets a token with a login link. The pages for
 * the administrator need `MANAGE_USERS` for `function:users`; the two pages under `/invite/` are public, slowed down after a failure like the login, and
 * never cached or passed on in a `Referer`, because the secret is in the path.
 */
internal class InvitePages(private val users: UserManager, private val web: WebServer, private val clock: Clock = Clock.systemUTC()) {
    private val usersScope = listOf(Scope(ScopeKind.FUNCTION, "users"))

    fun register() {
        web.navigation += NavItem("Invites", "/invites", Permission.MANAGE_USERS, "Administer", usersScope)
        web.router.scoped(usersScope) {
            get("/invites", Permission.MANAGE_USERS) { web.render("Invites", it, section("Invites", "A one-time link that creates a user with the roles you choose. The link is shown once, when it is created.", "invites", invitesList(null, null))) }
            get("/invites/list", Permission.MANAGE_USERS) { fragment(invitesList(null, null)) }
            post("/invites", Permission.MANAGE_USERS) { req ->
                var created: Html? = null
                val error = attempt {
                    val roles = UserRole.entries.filter { req.form["role-${it.name}"] == "on" }.toSet()
                    val groups = req.form["groups"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    val hours = req.form["hours"]?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: throw UserException(UserException.Kind.INVALID, "lifetime must be a number of hours") }
                    val invite = users.createInvite(req.session?.user?.name ?: "unknown", roles, groups, parseScoped(req.form["scoped"].orEmpty()), hours?.let { Duration.ofHours(it) })
                    created = inviteMessage(web.baseUrl(req) + "/invite/" + invite.secret, invite.info.expiresAt)
                }
                fragment(invitesList(error, created))
            }
            post("/invites/{id}/revoke", Permission.MANAGE_USERS) { req -> fragment(invitesList(attempt { users.revokeInvite(req.params.getValue("id")) }, null)) }
        }
        // public: no session needed
        web.router.get("/invite/{secret}", null) { req ->
            val invite = users.peekInvite(req.params.getValue("secret"))
            if (invite == null) invalid() else guest(200, "Invitation", redeemForm(req.params.getValue("secret"), invite, null))
        }
        web.router.post("/invite/{secret}", null) { req ->
            val secret = req.params.getValue("secret")
            try {
                val redeemed = users.redeemInvite(secret, req.form["name"].orEmpty().trim())
                val link = web.baseUrl(req) + "/login#token=" + redeemed.token.secret
                guest(200, "Welcome", h("<div class=\"panel auth-card\"><h1>Welcome, {}</h1>{}</div>", redeemed.user.user.name, tokenShare(link, redeemed.token.secret, redeemed.user.user.name, redeemed.token.info.expiresAt)))
            } catch (e: UserException) {
                when (e.kind) {
                    UserException.Kind.NOT_FOUND -> invalid()
                    else -> users.peekInvite(secret)?.let { guest(200, "Invitation", redeemForm(secret, it, e.message)) } ?: invalid()
                }
            }
        }
    }

    private suspend fun invalid(): WebResponse {
        web.slowDown()
        return guest(404, "Invitation", h("<div class=\"panel auth-card\"><h1>Invitation</h1><p class=\"notice error\" role=\"alert\">This invitation is not valid. It may be used up, expired or withdrawn.</p></div>"))
    }

    private fun guest(status: Int, title: String, content: Html): WebResponse {
        val framed = html(raw("<div class=\"auth\">"), content, raw("</div>"))
        return web.guestPage(status, title, framed)
    }

    private fun redeemForm(secret: String, invite: InviteInfo, error: String?): Html = h(
        "<form class=\"panel auth-card\" method=\"post\" action=\"/invite/{}\"><h1>You are invited</h1><p class=\"subtitle\">Choose a name for your user. The invitation is valid until {}.</p>{}" +
            "<p>It gives you: {}</p><label class=\"field\"><span>User name</span><input name=\"name\" autocomplete=\"username\" autofocus required></label><button class=\"btn primary block\">Create my user</button></form>",
        secret, invite.expiresAt.toString().take(16).replace("T", " ") + " UTC",
        if (error != null) h("<p class=\"notice error\" role=\"alert\">{}</p>", error) else Html(""),
        gives(invite),
    )

    private fun gives(invite: InviteInfo): Html = html(
        tagsOf(invite.roles.map { it.name.lowercase().replace('_', '-') }),
        invite.groups.takeIf { it.isNotEmpty() }?.let { h(" in the groups {}", tagsOf(it.sorted())) } ?: Html(""),
        invite.scoped.takeIf { it.isNotEmpty() }?.let { s -> h(", and {}", tagsOf(s.sortedWith(compareBy({ it.scope.encode() }, { it.role })).map { "${it.role.name.lowercase().replace('_', '-')} for ${it.scope.encode()}" })) } ?: Html(""),
    )

    private fun tagsOf(values: Collection<String>): Html = if (values.isEmpty()) raw("<span class=\"muted\">no global role</span>") else html(values.map { h("<span class=\"tag\">{}</span> ", it) })

    private fun inviteMessage(link: String, expiresAt: Instant): Html = h(
        "<div class=\"token-share\"><p>Invite link <code data-copy=\"{}\" title=\"Click to copy\">{}</code> (shown once, valid until {}).</p>{}<p class=\"hint\">Send the link or let the person scan the code. It works once and creates a user.</p></div>",
        link, link.substringBefore("/invite/") + "/invite/…", expiresAt.toString().take(16).replace("T", " ") + " UTC",
        raw(cringle.common.qr.QrCode.encode(link).toSvg("Invite link")),
    )

    private fun invitesList(error: String?, created: Html?): Html {
        val now = clock.instant()
        val rows = users.listInvites().map { i ->
            val state = i.state(now)
            h(
                "<tr><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td>{}</tr>",
                tagsOf(i.roles.map { it.name.lowercase().replace('_', '-') }), tagsOf(i.groups.sorted()).takeIf { i.groups.isNotEmpty() } ?: raw("<span class=\"muted\">none</span>"),
                if (i.scoped.isEmpty()) raw("<span class=\"muted\">none</span>") else html(i.scoped.sortedWith(compareBy({ it.scope.encode() }, { it.role })).map { h("<code>{}@{}</code> ", it.role.name.lowercase().replace('_', '-'), it.scope.encode()) }),
                i.createdBy, i.expiresAt.toString().take(16).replace("T", " "),
                when (state) {
                    InviteState.OPEN -> badge("open", Tone.OK)
                    InviteState.USED -> badge("used by ${i.usedBy}", Tone.INFO)
                    InviteState.EXPIRED -> badge("expired", Tone.WARN)
                    InviteState.REVOKED -> badge("revoked", Tone.BAD)
                },
                if (state == InviteState.OPEN) actionsCell(button(NO_SESSION_CHECK, Permission.AUTHENTICATED, "Revoke", "/invites/${i.id}/revoke", "Revoke this invite?")) else raw("<td></td>"),
            )
        }
        val form = h(
            "<form class=\"form-row\" hx-post=\"/invites\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}{}{}{}<button class=\"btn primary\">Create invite</button></form>",
            roleBoxesOf(),
            field("Groups", raw("<input name=\"groups\" placeholder=\"none\">"), "comma separated"),
            field("Scoped roles", raw("<textarea name=\"scoped\" rows=\"2\" placeholder=\"operator@machine:m1\"></textarea>"), "one role@scope per line, e.g. viewer@project:shop"),
            field("Lifetime (hours)", raw("<input name=\"hours\" placeholder=\"24\">"), "at most 720"),
        )
        return html(
            flash(error, detail = created),
            dataTable(listOf("Roles", "Groups", "Scoped roles", "Invited by", "Expires", "State", ""), rows, raw("No invite yet.")),
            formPanel("Create an invite", "Whoever opens the link names a user, which gets exactly this. The link works once.", form),
        )
    }

    private fun roleBoxesOf(): Html = html(
        raw("<fieldset class=\"roles\"><legend>Roles</legend>"),
        UserRole.entries.map { h("<label class=\"check\"><input type=\"checkbox\" name=\"role-{}\"> {}</label>", it.name, it.name.lowercase().replace('_', '-')) },
        raw("</fieldset>"),
    )

    /** `role@scope` per line, e.g. `operator@machine:m1`. */
    private fun parseScoped(text: String): Set<RoleAssignment> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        val roleText = line.substringBefore('@')
        val role = UserRole.entries.firstOrNull { it.name.equals(roleText.trim().replace('-', '_'), ignoreCase = true) } ?: throw UserException(UserException.Kind.INVALID, "unknown role '$roleText' (use role@scope)")
        val scope = try {
            Scope.parse(line.substringAfter('@', "").trim())
        } catch (e: IllegalArgumentException) {
            throw UserException(UserException.Kind.INVALID, e.message ?: "invalid scope")
        }
        RoleAssignment(role, scope)
    }.toSet()

    private companion object {
        val NO_SESSION_CHECK = Session("", null, Permission.entries.toSet(), "", Instant.EPOCH)
    }
}
