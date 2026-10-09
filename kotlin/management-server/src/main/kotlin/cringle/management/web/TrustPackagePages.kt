// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.common.PublicKeyFingerprint
import cringle.common.TlsHelper
import cringle.management.ManagementCore
import cringle.management.ManagementException
import cringle.repository.v1.PackageKind
import cringle.repository.v1.PluginTrust
import cringle.repository.v1.SetPluginTrustRequest
import cringle.router.users.Permission
import cringle.router.v1.AddRemoteRouterRequest
import cringle.router.v1.ListRemoteRoutersRequest
import cringle.router.v1.RemoveRemoteRouterRequest

/**
 * The pages for trust (#211, `cringle trust|router`) and for the packages of the repository (`cringle repo`). Trusting a router is two steps as in the CLI:
 * the page shows the key fingerprint that the router presents, and the operator confirms it (after checking it with the operator of that router).
 */
internal class TrustPackagePages(private val core: ManagementCore) {
    fun register(web: WebServer) {
        val r = web.router
        web.navigation += listOf(NavItem("Trust", "/trust", group = "Administer"), NavItem("Packages", "/packages", group = "Administer"))

        r.get("/trust", Permission.READ) { web.render("Trust", it, section("Trust", "Whose keys this server trusts, and the other routers it is connected to. Trust is by the fingerprint of a key, never by first use.", "trust", trust(it.session!!, null, null))) }
        r.get("/trust/list", Permission.READ) { fragment(trust(it.session!!, null, null)) }
        // step one: connect to the router and show its key; nothing is trusted yet
        r.post("/trust/probe", Permission.ADMINISTER) { req ->
            val address = req.form["address"].orEmpty().trim()
            var confirm: Html? = null
            val error = attempt {
                val (host, port) = hostPort(address)
                val actual = TlsHelper.probeServerFingerprint(host, port)
                confirm = h(
                    "<form class=\"panel confirm\" hx-post=\"/trust/routers\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\"><h2>Check the key of the router</h2><p>The router at <strong>{}</strong> presents the key <code>{}</code>. Compare it with the one the operator of that router gave you; trust it only if both are the same.</p><input type=\"hidden\" name=\"address\" value=\"{}\"><input type=\"hidden\" name=\"fingerprint\" value=\"{}\"><div class=\"row-actions\"><button class=\"btn primary\">Trust this router</button></div></form>",
                    address, actual, address, actual,
                )
            }
            fragment(trust(req.session!!, error, confirm))
        }
        // step two: trust exactly the fingerprint that was confirmed; the router refuses if its key is another one
        r.post("/trust/routers", Permission.ADMINISTER) { req ->
            val error = attempt {
                val fingerprint = req.form["fingerprint"].orEmpty().trim().lowercase()
                if (!PublicKeyFingerprint.pattern.matches(fingerprint)) throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "'$fingerprint' is not a SHA-256 fingerprint (64 hexadecimal characters)")
                core.router().addRemoteRouter(AddRemoteRouterRequest.newBuilder().setAddress(req.form["address"].orEmpty().trim()).setExpectedFingerprint(fingerprint).build())
            }
            fragment(trust(req.session!!, error, null))
        }
        r.post("/trust/routers/remove", Permission.ADMINISTER) { req ->
            fragment(trust(req.session!!, attempt { core.router().removeRemoteRouter(RemoveRemoteRouterRequest.newBuilder().setAddress(req.form["address"].orEmpty()).build()) }, null))
        }
        r.post("/trust/components", Permission.ADMINISTER) { req ->
            val error = attempt { core.addTrustedComponent(req.form["fingerprint"].orEmpty(), req.form["name"].orEmpty(), req.form["kind"].orEmpty(), req.form["address"].orEmpty().trim()) }
            fragment(trust(req.session!!, error, null))
        }
        r.post("/trust/revoke", Permission.ADMINISTER) { req ->
            var done: Html? = null
            val error = attempt { done = h("Revoked ({} entries removed).", core.removeTrust(req.form["fingerprint"].orEmpty())) }
            fragment(trust(req.session!!, error, done))
        }

        r.get("/packages", Permission.READ) { web.render("Packages", it, section("Packages", "Projects and plugins of the repository. A plugin runs only when it is trusted.", "packages", packages(it.session!!, null, null))) }
        r.get("/packages/list", Permission.READ) { fragment(packages(it.session!!, null, null)) }
        r.post("/packages/upload", Permission.OPERATE, UPLOAD_LIMIT) { req ->
            var done: String? = null
            val error = attempt {
                val file = Multipart.parse(req.headers["content-type"], req.body).firstOrNull { it.name == "file" && !it.filename.isNullOrEmpty() }
                    ?: throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "choose a package file")
                val m = publishPackage(core, file.data)
                done = "Published ${m.name} ${m.version}"
            }
            fragment(packages(req.session!!, error, done))
        }
        r.post("/packages/{name}/trust", Permission.ADMINISTER) { req ->
            val trust = when (req.form["trust"]) {
                "trusted" -> PluginTrust.PLUGIN_TRUST_TRUSTED
                "untrusted" -> PluginTrust.PLUGIN_TRUST_UNTRUSTED
                else -> null
            }
            val error = attempt {
                if (trust == null) throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "trust must be trusted or untrusted")
                core.repository().setPluginTrust(SetPluginTrustRequest.newBuilder().setName(req.params.getValue("name")).setTrust(trust).build())
            }
            fragment(packages(req.session!!, error, null))
        }
    }

    private fun hostPort(address: String): Pair<String, Int> {
        val port = address.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 }
        if (port == null || address.substringBeforeLast(':').isEmpty()) throw ManagementException(io.grpc.Status.Code.INVALID_ARGUMENT, "the address must be host:port")
        return address.substringBeforeLast(':') to port
    }

    private suspend fun trust(session: Session, error: String?, extra: Any?): Html {
        val admin = session.can(Permission.ADMINISTER)
        val entries = core.listTrust().map {
            h(
                "<tr><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td>{}</tr>",
                fingerprint(it.fingerprint), badge(it.kind.lowercase(), if (it.kind == "ROUTER") Tone.INFO else Tone.NEUTRAL), it.name, if (it.address.isEmpty()) h("<span class=\"muted\">-</span>") else h("<code>{}</code>", it.address),
                if (it.origin.isEmpty()) h("<span class=\"muted\">direct</span>") else fingerprint(it.origin),
                if (!admin || it.origin.isNotEmpty()) {
                    Html("")
                } else {
                    actionsCell(h("<form hx-post=\"/trust/revoke\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\" hx-confirm=\"Revoke the trust in {}?\"><input type=\"hidden\" name=\"fingerprint\" value=\"{}\"><button>Revoke</button></form>", it.name, it.fingerprint))
                },
            )
        }
        val routers: List<Html> = try {
            core.router().listRemoteRouters(ListRemoteRoutersRequest.getDefaultInstance()).routersList.map {
                h(
                    "<tr><td><code>{}</code></td><td class=\"num\">{}</td><td class=\"error\">{}</td>{}</tr>",
                    it.address, it.cachedEngines, it.lastError,
                    if (!admin) Html("") else actionsCell(h("<form hx-post=\"/trust/routers/remove\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\" hx-confirm=\"Disconnect {}?\"><input type=\"hidden\" name=\"address\" value=\"{}\"><button>Disconnect</button></form>", it.address, it.address)),
                )
            }
        } catch (e: ManagementException) {
            if (e.code == io.grpc.Status.Code.FAILED_PRECONDITION) emptyList() else listOf(h("<tr><td colspan=\"4\" class=\"error\">{}</td></tr>", describe(e)))
        } catch (e: Exception) {
            listOf(h("<tr><td colspan=\"4\" class=\"error\">{}</td></tr>", describe(e)))
        }
        val routerForm = if (!admin) {
            Html("")
        } else {
            h(
                "<form class=\"form-row\" hx-post=\"/trust/probe\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}<button class=\"btn primary\">Show its key</button></form>",
                field("Router address", raw("<input name=\"address\" placeholder=\"host:port\" required>")),
            )
        }
        val componentForm = if (!admin) {
            Html("")
        } else {
            h(
                "<form class=\"form-row\" hx-post=\"/trust/components\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}{}{}{}<button class=\"btn primary\">Trust</button></form>",
                field("Fingerprint", raw("<input name=\"fingerprint\" placeholder=\"64 hexadecimal characters\" size=\"36\" required>")),
                field("Name", raw("<input name=\"name\" required>")),
                field("Kind", raw("<select name=\"kind\"><option>COMPONENT</option><option>SERVER</option></select>")),
                field("Address", raw("<input name=\"address\" placeholder=\"optional\">"), "host:port"),
            )
        }
        return html(
            flash(error, form = (extra as? Html)?.takeIf { error == null && it.value.startsWith("<form") }, detail = (extra as? Html)?.takeIf { !(error == null && it.value.startsWith("<form")) }),
            dataTable(listOf("Fingerprint", "Kind", "Name", "Address", "Via router", ""), entries, raw("Nothing is trusted yet.")),
            raw("<h2>Connected routers</h2>"),
            dataTable(listOf("Address", "Engines", "Last error", ""), routers, raw("No other router is connected (or this server has no router of its own).")),
            formPanel("Trust a router", "Shows the key the router presents; you confirm it in the next step. The engines of that router become reachable.", routerForm),
            formPanel("Trust a component", "A daemon, a repository or a server, by the fingerprint of its key.", componentForm),
        )
    }

    private suspend fun packages(session: Session, error: String?, done: String?): Html {
        val packages = core.repository().listPackages(cringle.repository.v1.ListPackagesRequest.getDefaultInstance()).packagesList.sortedWith(compareBy({ it.kind.number }, { it.name }, { it.version }))
        val rows = packages.map { p ->
            val plugin = p.kind == PackageKind.PACKAGE_KIND_PLUGIN
            val trust = p.trust.pretty().removePrefix("plugin_trust_").removePrefix("trust_")
            h(
                "<tr><td>{}</td><td><strong>{}</strong></td><td>{}</td><td class=\"num\">{}</td><td>{}</td>{}</tr>",
                badge(if (plugin) "plugin" else "project", if (plugin) Tone.INFO else Tone.NEUTRAL), p.name, p.version, p.sizeBytes, if (plugin) stateBadge(trust) else Html(""),
                if (plugin && session.can(Permission.ADMINISTER)) {
                    actionsCell(
                        h(
                            "<form hx-post=\"/packages/{}/trust\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\"><input type=\"hidden\" name=\"trust\" value=\"{}\"><button title=\"All versions of the plugin\">{}</button></form>",
                            p.name, if (trust == "trusted") "untrusted" else "trusted", if (trust == "trusted") "Distrust" else "Trust",
                        ),
                    )
                } else {
                    Html("")
                },
            )
        }
        val upload = if (!session.can(Permission.OPERATE)) {
            Html("")
        } else {
            h(
                "<form class=\"form-row\" hx-post=\"/packages/upload\" hx-encoding=\"multipart/form-data\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">{}<button class=\"btn primary\">Publish</button></form>",
                field("Package file", raw("<input type=\"file\" name=\"file\" required>"), "a project or plugin package, up to 64 MB"),
            )
        }
        return html(
            flash(error, done),
            dataTable(listOf("Kind", "Name", "Version", "Bytes", "Trust", ""), rows, raw("The repository is empty. Publish a package below.")),
            formPanel("Publish a package", "The repository checks the package and its hash.", upload),
        )
    }

    private companion object {
        const val UPLOAD_LIMIT = 64 * 1024 * 1024
    }
}
