// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** `--bind` of the daemon (#300): the daemon and its router listen on the loopback interface unless they are told to listen on all. */
@Tag("integration")
class DaemonBindTest {
    @TempDir
    lateinit var home: Path

    /** An IPv4 address of this machine that is not the loopback address, `null` on a machine without a network. */
    private val outside: InetAddress? = NetworkInterface.networkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }

    private fun reachable(address: InetAddress, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(address, port), 2000) }
        true
    } catch (_: IOException) {
        false
    }

    @Test
    fun byDefaultOnlyTheLoopbackInterfaceIsReachable() {
        assumeTrue(outside != null, "this machine has no network address besides the loopback address")
        Daemon(home, combined = true).use { daemon ->
            daemon.start()
            assertTrue(reachable(InetAddress.getLoopbackAddress(), daemon.port) && reachable(InetAddress.getLoopbackAddress(), daemon.router!!.port))
            assertFalse(reachable(outside!!, daemon.port), "daemon on ${outside.hostAddress}")
            assertFalse(reachable(outside, daemon.router!!.port), "router on ${outside.hostAddress}")
        }
    }

    @Test
    fun allMakesTheDaemonAndItsRouterReachableOnTheNetwork() {
        assumeTrue(outside != null, "this machine has no network address besides the loopback address")
        Daemon(home, combined = true, bindHost = "all").use { daemon ->
            daemon.start()
            assertTrue(reachable(outside!!, daemon.port), "daemon on ${outside.hostAddress}")
            assertTrue(reachable(outside, daemon.router!!.port), "router on ${outside.hostAddress}")
            assertTrue(reachable(InetAddress.getLoopbackAddress(), daemon.port))
        }
    }

    @Test
    fun anAddressIsRefusedBecauseTheComponentsOfTheMachineConnectToLoopback() {
        assertThrows<IllegalArgumentException> { Daemon(home, bindHost = "192.0.2.7") }
    }
}
