// SPDX-License-Identifier: Apache-2.0

package cringle.common.config

/** What has to be restarted when a setting changes (#314). */
public enum class RestartTarget {
    /** The daemon itself (its address and port, and so everything that connects to it). */
    DAEMON,

    /** The management server the daemon runs. */
    MANAGEMENT,

    /** The package repository the daemon runs. */
    REPOSITORY,

    /** The set of programs the daemon runs besides itself: they are started and stopped as the list says. */
    COMPONENTS,

    /** Nothing: the value is only read when it is used (the name of the machine in the addresses that are shown). */
    NONE,
}

/** One setting: its [name], the [default] it has when it is not set, what [restarts] when it changes, and a check that returns the problem or `null`. */
public class ConfigKey(
    public val name: String,
    public val default: String,
    public val description: String,
    public val restarts: Set<RestartTarget>,
    public val type: String,
    private val check: (String) -> String?,
    private val normalizer: (String) -> String = { it },
) {
    /** The problem with [value], or `null` if it is valid. */
    public fun problem(value: String): String? = check(value)

    /** [value] in its one written form (the components in their order, `loopback` in lower case). */
    public fun normalize(value: String): String = normalizer(value.trim())
}

/**
 * The settings of a machine that the configuration manager knows (#314): the address the servers listen on, what the daemon runs, and the ports. The
 * catalog is the only place that lists them; a key that is not here is refused.
 */
public object ConfigCatalog {
    private fun port(value: String): String? = if (value.toIntOrNull()?.let { it in 1..65535 } == true) null else "'$value' is not a port (1 to 65535)"

    /** `management,repository`, `management`, `repository` or `none` for [text] (any order); `null` if it names anything else. */
    public fun normalizeComponents(text: String): String? {
        val parts = text.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() && it != "none" }
        if (parts.any { it != "management" && it != "repository" }) return null
        return listOf("management", "repository").filter { it in parts }.joinToString(",").ifEmpty { "none" }
    }

    private fun webUrl(value: String): String? {
        if (value.isEmpty()) return null
        return try {
            val uri = java.net.URI(value)
            when {
                uri.scheme != "https" || uri.host.isNullOrEmpty() -> "must be an https URL like https://host:port"
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || !(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") ->
                    "must not have user info, path, query or fragment"
                else -> null
            }
        } catch (e: java.net.URISyntaxException) {
            "is not a URL"
        }
    }

    private fun host(value: String): String? = when {
        value.isEmpty() -> null
        value.length > 253 -> "is too long"
        Regex("[A-Za-z0-9][A-Za-z0-9._-]*|\\[[0-9A-Fa-f:.]+]").matches(value) -> null
        else -> "'$value' is not a host name or an address (no scheme, port or path)"
    }

    private fun hostAndPort(value: String): String? {
        if (value.isEmpty()) return null
        val port = value.substringAfterLast(':', "")
        val host = value.substringBeforeLast(':', "")
        if (host.isEmpty() || port.toIntOrNull()?.let { it in 1..65535 } != true) return "'$value' is not host:port"
        return host(host)
    }

    /** All settings, in the order they are shown. */
    public val keys: List<ConfigKey> = listOf(
        ConfigKey(
            "bind", "loopback", "Where the servers of this machine listen: loopback (only this machine) or all (every network interface).", setOf(RestartTarget.DAEMON), "loopback|all",
            { if (it.lowercase() in setOf("loopback", "all")) null else "'$it' is not loopback or all (the programs of one machine connect to each other through the loopback interface)" },
            { it.lowercase() },
        ),
        ConfigKey(
            "cringle.host", "",
            "The name or address under which other Cringle machines reach this machine (in a Tailscale network its Tailscale name or address). The Connect page builds its addresses from it.",
            setOf(RestartTarget.NONE), "host", ::host,
        ),
        ConfigKey(
            "components", "none", "What the daemon runs besides itself: management, repository, both separated by a comma, or none.", setOf(RestartTarget.COMPONENTS), "list",
            { if (normalizeComponents(it) != null) null else "'$it' is not management, repository, both separated by a comma, or none" },
            { normalizeComponents(it) ?: it },
        ),
        ConfigKey("daemon.port", "7400", "Port of the daemon.", setOf(RestartTarget.DAEMON), "port", ::port),
        ConfigKey(
            "router.mode", "local", "The router of this machine: local (the daemon runs one), remote (it uses the router at router.address) or none.", setOf(RestartTarget.DAEMON), "local|remote|none",
            { if (it.lowercase() in setOf("local", "remote", "none")) null else "'$it' is not local, remote or none" },
            { it.lowercase() },
        ),
        ConfigKey("router.port", "7450", "Port of the router that the daemon runs (router.mode local). Other machines add this address as a remote router.", setOf(RestartTarget.DAEMON), "port", ::port),
        ConfigKey("router.address", "", "host:port of the router that this machine uses (router.mode remote).", setOf(RestartTarget.DAEMON), "host:port", ::hostAndPort),
        ConfigKey("management.port", "7500", "Port of the management server (gRPC: CLI and daemons).", setOf(RestartTarget.MANAGEMENT), "port", ::port),
        ConfigKey("management.web.port", "8443", "Port of the web interface of the management server.", setOf(RestartTarget.MANAGEMENT), "port", ::port),
        ConfigKey(
            "management.web.url", "", "Public address of the web interface for login links and QR codes (https://host:port); empty: the Host header of the request.",
            setOf(RestartTarget.MANAGEMENT), "url", ::webUrl,
        ),
        ConfigKey("repository.port", "7600", "Port of the package repository.", setOf(RestartTarget.REPOSITORY, RestartTarget.MANAGEMENT), "port", ::port),
    )

    private val byName: Map<String, ConfigKey> = keys.associateBy { it.name }

    /** The key [name]; `null` if the catalog does not have it. */
    public fun find(name: String): ConfigKey? = byName[name]

    /** The settings of the old environment variables (`CRINGLE_BIND` ...) and their keys: what the migration of the first start reads (#314). */
    public val environmentNames: Map<String, String> = linkedMapOf(
        "CRINGLE_BIND" to "bind",
        "CRINGLE_COMPONENTS" to "components",
        "CRINGLE_DAEMON_PORT" to "daemon.port",
        "CRINGLE_MANAGEMENT_PORT" to "management.port",
        "CRINGLE_WEB_PORT" to "management.web.port",
        "CRINGLE_REPOSITORY_PORT" to "repository.port",
    )
}
