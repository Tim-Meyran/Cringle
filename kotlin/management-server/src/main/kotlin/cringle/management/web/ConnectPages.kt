// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.common.TlsHelper
import cringle.management.MachineView
import cringle.management.ManagementCore
import cringle.router.users.Permission
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The page *Connect*: the address and the key of every Cringle component that other machines or the command line connect to. The address is
 * `<cringle.host>:<port>`: `cringle.host` is the name under which other Cringle machines reach a machine (in a Tailscale network its Tailscale name
 * or address), a setting of the machine (`cringle config set <machine> cringle.host <name>`). Without it the page takes the address the management
 * server uses for the machine, and for the machine of this server the address of this page, and says so. The keys are the ones the components
 * show in the handshake (what `cringle login --fingerprint`, `trust add` and `router add --fingerprint` ask for).
 */
internal class ConnectPages(private val core: ManagementCore, private val grpcPort: () -> Int?) {
    fun register(web: WebServer) {
        web.navigation += NavItem("Connect", "/connect", Permission.READ, "Overview")
        web.router.get("/connect", Permission.READ) { req -> web.render("Connect", req, content(web, req)) }
    }

    private class Row(val component: String, val address: String, val fingerprint: String?, val note: String, val command: String?)

    private class Block(val machine: String, val reachable: Boolean, val host: String, val hostSource: String, val hostSet: Boolean, val loopbackOnly: Boolean, val rows: List<Row>, val problem: String?)

    private val loopbacks = setOf("127.0.0.1", "localhost", "::1", "[::1]")

    private suspend fun fingerprint(host: String, port: Int): String? =
        withContext(Dispatchers.IO) { runCatching { TlsHelper.probeServerFingerprint(host, port, Duration.ofSeconds(2)) }.getOrNull() }

    private suspend fun content(web: WebServer, req: WebRequest): Html {
        val session = req.session!!
        val pageHost = runCatching { java.net.URI(web.baseUrl(req)).host }.getOrNull().orEmpty().ifEmpty { "localhost" }
        val machines = core.listMachines().filter { session.canFor(Permission.READ, core.access.machine(it.record.id)) }
        val blocks = coroutineScope { machines.map { async { block(it, pageHost) } }.awaitAll() }
        // the machine on which this server runs: the daemon it reaches over the loopback interface
        val here = blocks.firstOrNull { b -> machines.first { it.record.id == b.machine }.record.daemonAddress.substringBeforeLast(':') in loopbacks }
        val serverHost = here?.takeIf { it.hostSet }?.host ?: pageHost
        val grpc = grpcPort()
        val own = if (grpc == null) {
            Html("")
        } else {
            val address = "$serverHost:$grpc"
            val key = core.identity.publicKeyFingerprint
            h(
                "<section class=\"panel\"><h2>This server</h2><p class=\"hint\">The management server of this page: what the command line and other servers connect to.</p>{}</section>",
                table(listOf(Row("Management server", address, key, "gRPC: the command line, daemons, other servers", "cringle login --server $address --fingerprint $key"))),
            )
        }
        return html(
            pageHeader("Connect", "The addresses and keys of the Cringle components, to copy into another machine, the command line or another server."),
            own,
            if (blocks.isEmpty()) h("<p class=\"empty\">No machine yet. Add the first one under {}.</p>", raw("<a href=\"/machines\">Machines</a>")) else html(blocks.map(::render)),
        )
    }

    private suspend fun block(m: MachineView, pageHost: String): Block {
        val id = m.record.id
        val internalHost = m.record.daemonAddress.substringBeforeLast(':')
        val internalPort = m.record.daemonAddress.substringAfterLast(':').toIntOrNull() ?: 0
        val config: Map<String, String>? = if (!m.reachable) {
            null
        } else {
            try {
                core.listConfig(id).entriesList.associate { it.key to it.value }
            } catch (e: Exception) {
                null
            }
        }
        val configured = config?.get("cringle.host").orEmpty()
        val (host, source) = when {
            configured.isNotEmpty() -> configured to "cringle.host of the machine"
            internalHost !in loopbacks -> internalHost to "the address that the management server uses for the machine"
            else -> pageHost to "the address of this page"
        }
        val rows = ArrayList<Row>()
        val problem = when {
            !m.reachable -> "The machine is not reachable${if (m.lastError.isNotEmpty()) ": " + m.lastError else ""}."
            config == null -> "The settings of the machine cannot be read (its daemon has no settings store)."
            else -> null
        }
        val daemonPort = config?.get("daemon.port")?.toIntOrNull() ?: internalPort
        rows += Row(
            "Daemon", "$host:$daemonPort", if (m.reachable) fingerprint(internalHost, internalPort) else null,
            "gRPC: a management server adds the machine", "cringle machine add $id $host:$daemonPort",
        )
        if (config != null) {
            when (config["router.mode"]) {
                "local" -> {
                    val port = config["router.port"]?.toIntOrNull()
                    if (port != null) {
                        val key = fingerprint(internalHost, port)
                        rows += Row("Router", "$host:$port", key, "other machines add it as a remote router", "cringle router add $host:$port" + (key?.let { " --fingerprint $it" } ?: ""))
                    }
                }
                "remote" -> rows += Row("Router", config["router.address"].orEmpty(), null, "the daemon uses this router (router.mode remote)", null)
                else -> rows += Row("Router", "none", null, "router.mode is none: engines of this machine do not register anywhere", null)
            }
            val components = config["components"].orEmpty().split(',')
            if ("management" in components) {
                val port = config["management.port"]?.toIntOrNull()
                if (port != null) {
                    val key = fingerprint(internalHost, port)
                    rows += Row("Management server", "$host:$port", key, "gRPC: the command line and other servers", "cringle login --server $host:$port" + (key?.let { " --fingerprint $it" } ?: ""))
                }
                val webUrl = config["management.web.url"].orEmpty().ifEmpty { "https://$host:${config["management.web.port"].orEmpty()}" }
                rows += Row("Web interface", webUrl, null, "the browser (HTTPS)", null)
            }
            if ("repository" in components) {
                val port = config["repository.port"]?.toIntOrNull()
                if (port != null) rows += Row("Repository", "$host:$port", fingerprint(internalHost, port), "packages: the management server and the engines fetch them", null)
            }
        }
        return Block(id, m.reachable, host, source, configured.isNotEmpty(), config?.get("bind") == "loopback", rows, problem)
    }

    private fun render(b: Block): Html = h(
        "<section class=\"panel\"><h2>Machine {} {}</h2>{}{}{}{}</section>",
        b.machine, if (b.reachable) badge("reachable", Tone.OK) else badge("not reachable", Tone.BAD),
        b.problem?.let { h("<p class=\"notice error\" role=\"alert\">{}</p>", it) } ?: Html(""),
        if (b.hostSet) {
            Html("")
        } else {
            h(
                "<p class=\"hint\">The addresses use <strong>{}</strong> ({}). Set the name under which other Cringle machines reach this one, in a Tailscale network its Tailscale name or address: {} or <a href=\"/config/{}\">Configuration</a>.</p>",
                b.host, b.hostSource, h("<code data-copy=\"{}\" title=\"Click to copy\">cringle config set {} cringle.host &lt;name&gt;</code>", "cringle config set ${b.machine} cringle.host <name>", b.machine),
                b.machine,
            )
        },
        if (b.loopbackOnly) {
            h(
                "<p class=\"notice\">The servers of this machine listen on the loopback interface only (<code>bind</code>): other machines cannot connect to these addresses. Open them with {}.</p>",
                h("<code data-copy=\"{}\" title=\"Click to copy\">cringle config set {} bind all</code>", "cringle config set ${b.machine} bind all", b.machine),
            )
        } else {
            Html("")
        },
        table(b.rows),
    )

    private fun table(rows: List<Row>): Html = dataTable(
        listOf("Component", "Address", "Key (SHA-256 of the public key)", "Use"),
        rows.map { r ->
            h(
                "<tr><td><strong>{}</strong></td><td>{}</td><td>{}</td><td>{}{}</td></tr>",
                r.component, copy(r.address),
                r.fingerprint?.let { h("<code class=\"fingerprint\" data-copy=\"{}\" title=\"Click to copy\">{}</code>", it, it.take(16) + "…") } ?: raw("<span class=\"muted\">not read</span>"),
                r.note, r.command?.let { h("<br>{}", copy(it)) } ?: Html(""),
            )
        },
        raw("No address."),
    )

    private fun copy(text: String): Html = h("<code data-copy=\"{}\" title=\"Click to copy\">{}</code>", text, text)
}
