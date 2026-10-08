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
        web.navigation += listOf(NavItem("Trust", "/trust"), NavItem("Packages", "/packages"))

        r.get("/trust", Permission.READ) { web.render("Trust", it, section("Trust", "trust", trust(it.session!!, null, null))) }
        r.get("/trust/list", Permission.READ) { fragment(trust(it.session!!, null, null)) }
        // step one: connect to the router and show its key; nothing is trusted yet
        r.post("/trust/probe", Permission.ADMINISTER) { req ->
            val address = req.form["address"].orEmpty().trim()
            var confirm: Html? = null
            val error = attempt {
                val (host, port) = hostPort(address)
                val actual = TlsHelper.probeServerFingerprint(host, port)
                confirm = h(
                    "<form hx-post=\"/trust/routers\" hx-target=\"#list\" hx-swap=\"innerHTML\"><p>The router at <strong>{}</strong> presents the key <code>{}</code>. Check it with the operator of that router.</p><input type=\"hidden\" name=\"address\" value=\"{}\"><input type=\"hidden\" name=\"fingerprint\" value=\"{}\"><button>Trust this router</button></form>",
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

        r.get("/packages", Permission.READ) { web.render("Packages", it, section("Packages", "packages", packages(it.session!!, null, null))) }
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
                "<tr><td><code>{}</code></td><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td></tr>",
                it.fingerprint, it.kind, it.name, it.address, it.origin,
                if (!admin || it.origin.isNotEmpty()) {
                    Html("")
                } else {
                    h("<form hx-post=\"/trust/revoke\" hx-target=\"#list\" hx-swap=\"innerHTML\" hx-confirm=\"Revoke the trust in {}?\"><input type=\"hidden\" name=\"fingerprint\" value=\"{}\"><button>Revoke</button></form>", it.name, it.fingerprint)
                },
            )
        }
        val routers = try {
            core.router().listRemoteRouters(ListRemoteRoutersRequest.getDefaultInstance()).routersList.map {
                h(
                    "<tr><td>{}</td><td>{}</td><td>{}</td><td>{}</td></tr>",
                    it.address, it.cachedEngines, it.lastError,
                    if (!admin) Html("") else h("<form hx-post=\"/trust/routers/remove\" hx-target=\"#list\" hx-swap=\"innerHTML\" hx-confirm=\"Disconnect {}?\"><input type=\"hidden\" name=\"address\" value=\"{}\"><button>Disconnect</button></form>", it.address, it.address),
                )
            }
        } catch (e: Exception) {
            listOf(h("<tr><td colspan=\"4\" class=\"error\">{}</td></tr>", describe(e)))
        }
        val forms = if (!admin) {
            Html("")
        } else {
            raw(
                "<h2>Trust a router</h2><form hx-post=\"/trust/probe\" hx-target=\"#list\" hx-swap=\"innerHTML\"><input name=\"address\" placeholder=\"router host:port\" required> <button>Show its key</button></form>" +
                    "<h2>Trust a component</h2><form hx-post=\"/trust/components\" hx-target=\"#list\" hx-swap=\"innerHTML\"><input name=\"fingerprint\" placeholder=\"fingerprint (64 hex characters)\" size=\"40\" required> <input name=\"name\" placeholder=\"name\" required> <select name=\"kind\"><option>COMPONENT</option><option>SERVER</option></select> <input name=\"address\" placeholder=\"host:port (optional)\"> <button>Trust</button></form>",
            )
        }
        return html(
            notice(error), if (extra is Html && error == null && extra.value.startsWith("<form")) extra else if (extra is Html) html(raw("<p class=\"info\" role=\"status\">"), extra, raw("</p>")) else Html(""),
            raw("<table><tr><th>Fingerprint</th><th>Kind</th><th>Name</th><th>Address</th><th>Via router</th><th></th></tr>"), entries, raw("</table>"),
            raw("<h2>Connected routers</h2><table><tr><th>Address</th><th>Engines</th><th>Last error</th><th></th></tr>"), routers, raw("</table>"), forms,
        )
    }

    private suspend fun packages(session: Session, error: String?, done: String?): Html {
        val packages = core.repository().listPackages(cringle.repository.v1.ListPackagesRequest.getDefaultInstance()).packagesList.sortedWith(compareBy({ it.kind.number }, { it.name }, { it.version }))
        val rows = packages.map { p ->
            val plugin = p.kind == PackageKind.PACKAGE_KIND_PLUGIN
            h(
                "<tr><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td></tr>",
                if (plugin) "plugin" else "project", p.name, p.version, p.sizeBytes, if (plugin) p.trust.pretty().removePrefix("plugin_trust_").removePrefix("trust_") else "",
                if (plugin && session.can(Permission.ADMINISTER)) {
                    h(
                        "<form hx-post=\"/packages/{}/trust\" hx-target=\"#list\" hx-swap=\"innerHTML\"><select name=\"trust\"><option>trusted</option><option>untrusted</option></select> <button>Set trust (all versions)</button></form>",
                        p.name,
                    )
                } else {
                    Html("")
                },
            )
        }
        val upload = if (!session.can(Permission.OPERATE)) {
            Html("")
        } else {
            raw("<h2>Publish a package</h2><form hx-post=\"/packages/upload\" hx-encoding=\"multipart/form-data\" hx-target=\"#list\" hx-swap=\"innerHTML\"><input type=\"file\" name=\"file\" required> <button>Publish</button></form>")
        }
        return html(notice(error), info(done), raw("<table><tr><th>Kind</th><th>Name</th><th>Version</th><th>Bytes</th><th>Trust</th><th></th></tr>"), rows, raw("</table>"), upload)
    }

    private companion object {
        const val UPLOAD_LIMIT = 64 * 1024 * 1024
    }
}
