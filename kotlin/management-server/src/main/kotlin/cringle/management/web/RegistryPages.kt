// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.common.PublicKeyFingerprint
import cringle.contract.UserRole
import cringle.router.users.FederatedToken
import cringle.router.users.Permission
import cringle.router.users.PublicKeyPem
import cringle.router.users.Scope
import cringle.router.users.ScopeKind
import cringle.router.users.UserException
import cringle.router.users.UserManager
import java.security.KeyPair
import java.time.Duration

/**
 * The page for the registries of other sites (#295, Architecture 6.2): the functions of `cringle registry ...`, behind `MANAGE_USERS` for the
 * `users` function like the user pages. A federated token is shown once, in the answer to its creation; it is not kept.
 */
internal class RegistryPages(private val users: UserManager, private val signingKey: KeyPair, private val helper: UserPages) {
    fun register(web: WebServer) {
        val usersScope = listOf(Scope(ScopeKind.FUNCTION, "users"))
        web.navigation += NavItem("Registries", "/registries", Permission.MANAGE_USERS, "Administer", usersScope)
        web.router.scoped(usersScope) { registerRoutes(web, this) }
    }

    private fun registerRoutes(web: WebServer, r: Router) {
        r.get("/registries", Permission.MANAGE_USERS) {
            web.render("Registries", it, section("Registries", "Other sites whose users may sign in here as name@registry, with a token that their registry signed.", "registries", registryList(null, null)))
        }
        r.get("/registries/list", Permission.MANAGE_USERS) { fragment(registryList(null, null)) }
        r.post("/registries", Permission.MANAGE_USERS) { req ->
            val error = attempt {
                val key = try {
                    PublicKeyPem.parse(req.form["key"].orEmpty())
                } catch (e: IllegalArgumentException) {
                    throw UserException(UserException.Kind.INVALID, e.message ?: "invalid key")
                }
                users.trustRegistry(req.form["name"].orEmpty().trim(), key, UserRole.entries.filter { req.form["role-${it.name}"] == "on" }.toSet())
            }
            fragment(registryList(error, null))
        }
        r.post("/registries/token", Permission.MANAGE_USERS) { req ->
            var created: Html? = null
            val error = attempt {
                val user = req.form["user"].orEmpty().trim()
                val name = req.form["as"].orEmpty().trim()
                if (user.isEmpty() || '@' in user) throw UserException(UserException.Kind.INVALID, "the user is empty or has an @")
                if (name.isEmpty()) throw UserException(UserException.Kind.INVALID, "give the name under which the other site has entered this registry")
                val hours = req.form["hours"]?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: throw UserException(UserException.Kind.INVALID, "lifetime must be a number of hours") } ?: 24
                val token = try {
                    FederatedToken.issue(name, user, signingKey, Duration.ofHours(hours))
                } catch (e: IllegalArgumentException) {
                    throw UserException(UserException.Kind.INVALID, e.message ?: "invalid lifetime")
                }
                created = h("Token for <strong>{}@{}</strong> <code data-copy=\"{}\" title=\"Click to copy\">{}</code> (shown once, copy it now).", user, name, token, token)
            }
            fragment(registryList(error, created))
        }
        r.post("/registries/{name}/delete", Permission.MANAGE_USERS) { req -> fragment(registryList(attempt { users.untrustRegistry(req.params.getValue("name")) }, null)) }
        for (grant in listOf(true, false)) {
            val verb = if (grant) "grant" else "revoke"
            r.post("/registries/{name}/$verb", Permission.MANAGE_USERS) { req ->
                val error = attempt {
                    helper.change(req.form) { role, scope ->
                        if (grant) users.grantRegistry(req.params.getValue("name"), role, scope) else users.revokeRegistry(req.params.getValue("name"), role, scope)
                    }
                }
                fragment(registryList(error, null))
            }
        }
    }

    private fun registryList(error: String?, created: Html?): Html {
        val rows = users.listRegistries().map { reg ->
            h(
                "<tr><td><strong>{}</strong></td><td><code>{}</code></td><td>{}</td><td>{}</td>{}</tr>",
                reg.name, reg.fingerprint.take(16) + "…", helper.tags(reg.roles.map { it.name.lowercase() }), helper.scopedPopover("/registries/${reg.name}", reg.scoped),
                actionsCell(button(NO_SESSION_CHECK, Permission.AUTHENTICATED, "Untrust", "/registries/${reg.name}/delete", "Stop trusting registry ${reg.name}? Its users can no longer sign in.")),
            )
        }
        val trustForm = h(
            "<form class=\"form-row\" hx-post=\"/registries\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}{}{}<button class=\"btn primary\">Trust registry</button></form>",
            field("Name", raw("<input name=\"name\" placeholder=\"site-b\" required>"), "users of it come as name@registry"),
            field("Public key or certificate (PEM)", raw("<textarea name=\"key\" rows=\"4\" required></textarea>"), "from 'cringle registry key' on the other site; compare the fingerprint with it"),
            helper.roleBoxes(),
        )
        val tokenForm = h(
            "<form class=\"form-row\" hx-post=\"/registries/token\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}{}{}<button class=\"btn primary\">Issue token</button></form>",
            field("User of this site", raw("<input name=\"user\" required>")),
            field("Known there as", raw("<input name=\"as\" placeholder=\"site-a\" required>"), "the name the other site entered this registry with"),
            field("Lifetime (hours)", raw("<input name=\"hours\" placeholder=\"24\">"), "at most 720"),
        )
        return html(
            flash(error, detail = created),
            dataTable(listOf("Registry", "Key", "Roles", "Scoped roles", ""), rows, raw("No trusted registry yet.")),
            formPanel("Trust a registry", "Give the other site's public key. Its users get the roles you tick (none: they can sign in and may do nothing) and the scoped roles you add.", trustForm),
            formPanel(
                "This registry's key",
                "Give this to the other site so that it can trust this one. Fingerprint " + PublicKeyFingerprint.of(signingKey.public) + ".",
                h("<pre class=\"key\">{}</pre>", PublicKeyPem.encode(signingKey.public)),
            ),
            formPanel("Issue a token for another site", "A user of this site gets a token that the other site, which trusts this registry, accepts as user@name. It is shown once.", tokenForm),
        )
    }

    private companion object {
        val NO_SESSION_CHECK = Session("", null, Permission.entries.toSet(), "", java.time.Instant.EPOCH)
    }
}
