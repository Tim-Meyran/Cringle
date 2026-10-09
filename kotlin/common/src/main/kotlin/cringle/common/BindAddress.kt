// SPDX-License-Identifier: Apache-2.0

package cringle.common

import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The address a server listens on. Every server of Cringle listens on the loopback interface unless it is told otherwise (`--bind`,
 * `CRINGLE_BIND`); the connections are mutual TLS or token protected either way, the address only decides who can reach the port.
 *
 * Values: `null`, an empty text or `loopback` is the loopback interface; `all` is every interface of the machine (the wildcard address); anything
 * else is that address, a literal one or a host name.
 */
public object BindAddress {
    /** The word for the loopback interface, the default. */
    public const val LOOPBACK: String = "loopback"

    /** The word for all interfaces. */
    public const val ALL: String = "all"

    /** The environment variable that holds the value when no argument is given. */
    public const val ENVIRONMENT: String = "CRINGLE_BIND"

    /** The value of [ENVIRONMENT] in [environment], `null` if it is not set or empty. */
    public fun fromEnvironment(environment: Map<String, String> = System.getenv()): String? = environment[ENVIRONMENT]?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * [text] for a server whose clients are the other components of the machine (daemon, router, engines): they connect to `127.0.0.1`, so only
     * `loopback` and `all` make sense. Returns the normalized word (`null` for the default); throws [IllegalArgumentException] for an address.
     */
    public fun interfaceChoice(text: String?): String? {
        val value = text?.trim().orEmpty()
        return when {
            value.isEmpty() || value.equals(LOOPBACK, ignoreCase = true) -> null
            value.equals(ALL, ignoreCase = true) -> ALL
            else -> throw IllegalArgumentException("the daemon, the router and the engines listen on loopback or all, not on '$value'")
        }
    }

    /** Whether [text] listens on more than the loopback interface. */
    public fun isOpen(text: String?): Boolean = !(text.isNullOrBlank() || text.trim().equals(LOOPBACK, ignoreCase = true))

    /** The socket address for [text] and [port]; throws [IllegalArgumentException] for a name that does not resolve. */
    public fun socketAddress(text: String?, port: Int): InetSocketAddress {
        val value = text?.trim().orEmpty()
        return when {
            value.isEmpty() || value.equals(LOOPBACK, ignoreCase = true) -> InetSocketAddress(InetAddress.getLoopbackAddress(), port)
            value.equals(ALL, ignoreCase = true) -> InetSocketAddress(port)
            else -> try {
                InetSocketAddress(InetAddress.getByName(value), port)
            } catch (e: java.net.UnknownHostException) {
                throw IllegalArgumentException("cannot bind to '$value': not an address or a known host name (use loopback, all, or an address)", e)
            }
        }
    }
}
