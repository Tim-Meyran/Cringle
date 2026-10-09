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

    /** All settings, in the order they are shown. */
    public val keys: List<ConfigKey> = listOf(
        ConfigKey(
            "bind", "loopback", "Where the servers of this machine listen: loopback (only this machine) or all (every network interface).", setOf(RestartTarget.DAEMON), "loopback|all",
            { if (it.lowercase() in setOf("loopback", "all")) null else "'$it' is not loopback or all (the programs of one machine connect to each other through the loopback interface)" },
            { it.lowercase() },
        ),
        ConfigKey(
            "components", "none", "What the daemon runs besides itself: management, repository, both separated by a comma, or none.", setOf(RestartTarget.COMPONENTS), "list",
            { if (normalizeComponents(it) != null) null else "'$it' is not management, repository, both separated by a comma, or none" },
            { normalizeComponents(it) ?: it },
        ),
        ConfigKey("daemon.port", "7400", "Port of the daemon.", setOf(RestartTarget.DAEMON), "port", ::port),
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
