// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.management.test.ManagementTls
import cringle.repository.PackageRepository
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** `--bind` (#299): a server listens on the loopback interface unless it is told to listen on all interfaces or on an address. */
@Tag("integration")
class ServerBindTest {
    private lateinit var dir: Path
    private lateinit var tls: ManagementTls
    private val closeables = ArrayList<AutoCloseable>()

    /** An IPv4 address of this machine that is not the loopback address, `null` if there is none (a machine without a network). */
    private val outside: InetAddress? = NetworkInterface.networkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }

    @BeforeEach
    fun setUp() {
        assumeTrue(outside != null, "this machine has no network address besides the loopback address")
        dir = Files.createTempDirectory("cringle-bind-test")
        tls = ManagementTls(dir.resolve("tls"))
    }

    @AfterEach
    fun tearDown() {
        closeables.reversed().forEach { runCatching { it.close() } }
        runCatching { dir.toFile().deleteRecursively() }
    }

    private fun reachable(address: InetAddress, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(address, port), 2000) }
        true
    } catch (_: IOException) {
        false
    }

    private fun start(bindHost: String?, webHost: String? = null): ManagementServer {
        val core = tls.core(ManagementStore(dir.resolve("state-${closeables.size}.json")))
        return ManagementServer(core, bindHost = bindHost, webPort = 0, webHost = webHost, recoverOnStart = false).start().also { closeables += it }
    }

    @Test
    fun byDefaultOnlyTheLoopbackInterfaceIsReachable() {
        val server = start(null)
        val loopback = InetAddress.getLoopbackAddress()
        assertTrue(reachable(loopback, server.port) && reachable(loopback, server.web!!.port))
        assertFalse(reachable(outside!!, server.port), "gRPC on ${outside.hostAddress}")
        assertFalse(reachable(outside, server.web!!.port), "web on ${outside.hostAddress}")
    }

    @Test
    fun allMakesTheGrpcServerAndTheWebInterfaceReachableOnTheNetwork() {
        val server = start("all")
        assertTrue(reachable(outside!!, server.port), "gRPC on ${outside.hostAddress}")
        assertTrue(reachable(outside, server.web!!.port), "web on ${outside.hostAddress}")
        assertTrue(reachable(InetAddress.getLoopbackAddress(), server.port))
    }

    @Test
    fun anAddressMakesTheServerReachableThroughThatAddressOnly() {
        val server = start(outside!!.hostAddress)
        assertTrue(reachable(outside, server.port))
        assertFalse(reachable(InetAddress.getLoopbackAddress(), server.port))
    }

    @Test
    fun theWebHostWinsForTheWebInterface() {
        val server = start("all", webHost = "loopback")
        assertTrue(reachable(outside!!, server.port))
        assertFalse(reachable(outside, server.web!!.port), "the web interface stays on the loopback interface")
        assertTrue(reachable(InetAddress.getLoopbackAddress(), server.web!!.port))
    }

    @Test
    fun theRepositoryFollowsTheSameRule() {
        val defaults = tls.startRepository(PackageRepository(dir.resolve("repo-a")))
        closeables += AutoCloseable { defaults.stop() }
        assertTrue(reachable(InetAddress.getLoopbackAddress(), defaults.port))
        assertFalse(reachable(outside!!, defaults.port))
        val open = tls.startRepository(PackageRepository(dir.resolve("repo-b")), bindHost = "all")
        closeables += AutoCloseable { open.stop() }
        assertTrue(reachable(outside, open.port))
    }
}
