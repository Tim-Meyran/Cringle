// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.common.config.ConfigStore
import cringle.contract.UserRole
import cringle.daemon.Daemon
import cringle.management.ManagementStore
import cringle.management.test.ManagementTls
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The page *Connect*: the addresses and keys of the components, built from `cringle.host` (a Tailscale name, say). */
@Tag("integration")
class WebConnectTest {
    @TempDir
    lateinit var dir: Path

    private val closeables = ArrayList<AutoCloseable>()
    private lateinit var store: ConfigStore
    private lateinit var daemon: Daemon
    private lateinit var admin: WebTestClient
    private lateinit var viewer: WebTestClient
    private lateinit var managementKey: String
    private var routerPort = 0

    @BeforeEach
    fun start() {
        val home = dir.resolve("home")
        store = ConfigStore(home.resolve("config").resolve("cringle.conf"))
        routerPort = java.net.ServerSocket(0).use { it.localPort }
        store.set("router.port", routerPort.toString())
        daemon = Daemon(home, 0, combined = true, config = store).start()
        closeables += daemon
        val tls = ManagementTls(dir.resolve("tls"))
        tls.trust(daemon)
        val users = UserManager(FileUserStore(dir.resolve("users.json")))
        val adminToken = users.bootstrap()!!
        val viewerToken = users.createToken(users.createUser("vera", setOf(UserRole.VIEWER)).user.id, "web", null).secret
        val core = tls.core(ManagementStore(dir.resolve("state.json")))
        closeables += core
        runBlocking { core.addMachine("m1", "127.0.0.1:${daemon.port}", null, null) }
        val server = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO, grpcPort = { 7599 }).start()
        closeables += server
        managementKey = tls.identity.publicKeyFingerprint
        admin = WebTestClient(server.port, managementKey).login(adminToken)
        viewer = WebTestClient(server.port, managementKey).login(viewerToken)
    }

    @AfterEach
    fun stop() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    @Test
    fun theAddressOfThisServerAndTheKeyOfItAreShown() {
        val page = admin.get("/connect").body()
        assertTrue(page.contains("This server") && page.contains("localhost:7599"), page)
        assertTrue(page.contains("data-copy=\"cringle login --server localhost:7599 --fingerprint $managementKey\""), page)
    }

    @Test
    fun withoutACringleHostThePageSaysWhichNameItTookAndHowToSetOne() {
        val page = admin.get("/connect").body()
        assertTrue(page.contains("Machine m1") && page.contains("the address of this page"), page)
        assertTrue(page.contains("cringle config set m1 cringle.host"), page)
        assertTrue(page.contains("localhost:7400") && page.contains("localhost:$routerPort"), page)
    }

    @Test
    fun theRouterAddressAndKeyAreThereToAddItAsARemoteRouter() {
        val router = daemon.router!!.identity!!.publicKeyFingerprint
        val page = admin.get("/connect").body()
        assertTrue(page.contains("cringle router add localhost:$routerPort --fingerprint $router"), page)
        assertTrue(page.contains("data-copy=\"${daemon.identityFingerprint}\""), "the key of the daemon: $page")
    }

    @Test
    fun theCringleHostIsTheNameInEveryAddress() {
        store.set("cringle.host", "node1.tail.example")
        store.set("components", "repository")
        val page = admin.get("/connect").body()
        assertTrue(page.contains("node1.tail.example:7400") && page.contains("node1.tail.example:$routerPort"), page)
        assertTrue(page.contains("node1.tail.example:7600"), "the repository: $page")
        assertFalse(page.contains("the address of this page"), page)
        assertTrue(page.contains("cringle config set m1 bind all"), "the servers listen on the loopback interface only: $page")
        assertTrue(page.contains("cringle router add node1.tail.example:$routerPort --fingerprint"), page)
    }

    @Test
    fun aViewerSeesTheAddressesAndAnUnknownPathIsNotThere() {
        assertTrue(viewer.get("/connect").body().contains("Machine m1"))
        assertTrue(admin.get("/connect").body().contains("href=\"/connect\""))
    }
}
