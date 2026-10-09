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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The page for the settings of a machine (#317): through the management server to the daemon of the machine. */
@Tag("integration")
class WebConfigTest {
    @TempDir
    lateinit var dir: Path

    private val closeables = ArrayList<AutoCloseable>()
    private lateinit var store: ConfigStore
    private lateinit var admin: WebTestClient
    private lateinit var viewer: WebTestClient

    @BeforeEach
    fun start() {
        val home = dir.resolve("home")
        store = ConfigStore(home.resolve("config").resolve("cringle.conf"))
        val daemon = Daemon(home, config = store).start()
        closeables += daemon
        val tls = ManagementTls(dir.resolve("tls"))
        tls.trust(daemon)
        val users = UserManager(FileUserStore(dir.resolve("users.json")))
        val adminToken = users.bootstrap()!!
        val viewerToken = users.createToken(users.createUser("vera", setOf(UserRole.VIEWER)).user.id, "web", null).secret
        val core = tls.core(ManagementStore(dir.resolve("state.json")))
        closeables += core
        runBlocking { core.addMachine("m1", "127.0.0.1:${daemon.port}", null, null) }
        val server = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        closeables += server
        val key = tls.identity.publicKeyFingerprint
        admin = WebTestClient(server.port, key).login(adminToken)
        viewer = WebTestClient(server.port, key).login(viewerToken)
    }

    @AfterEach
    fun stop() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    @Test
    fun theKeysAreListedWithTheirValuesAndADefaultBadge() {
        val page = admin.get("/config/m1").body()
        for (key in listOf("bind", "components", "daemon.port", "management.port", "management.web.port", "management.web.url", "repository.port")) {
            assertTrue(page.contains("<code>$key</code>"), "$key in $page")
        }
        assertTrue(page.contains("value=\"8443\"") && page.contains("hx-post=\"/config/m1/management.web.port\""), page)
        assertTrue(admin.get("/config/m1/list").body().contains("Changing it restarts: management"))
    }

    @Test
    fun aChangeIsStoredTheDaemonAnswersAndAResetBringsTheDefaultBack() {
        val changed = admin.post("/config/m1/management.web.port", mapOf("value" to "9443")).body()
        assertTrue(changed.contains("management.web.port is 9443"), changed)
        assertEquals("9443", store.get("management.web.port"))
        assertTrue(admin.get("/config/m1/list").body().contains("value=\"9443\"") && admin.get("/config/m1/list").body().contains("/config/m1/management.web.port/unset"))
        val reset = admin.post("/config/m1/management.web.port/unset").body()
        assertTrue(reset.contains("back to its default"), reset)
        assertFalse(store.isSet("management.web.port"))
    }

    @Test
    fun aChangeOfTheDaemonItselfSaysThatItHasToBeRestarted() {
        val changed = admin.post("/config/m1/daemon.port", mapOf("value" to "7411")).body()
        assertTrue(changed.contains("daemon has to be restarted"), changed)
        assertEquals("7411", store.get("daemon.port"))
    }

    @Test
    fun aBadValueShowsTheErrorAndKeepsTheOldOne() {
        val body = admin.post("/config/m1/daemon.port", mapOf("value" to "70000")).body()
        assertTrue(body.contains("INVALID_ARGUMENT"), body)
        assertFalse(store.isSet("daemon.port"))
        assertTrue(admin.post("/config/m1/no.such.key", mapOf("value" to "1")).body().contains("NOT_FOUND"))
    }

    @Test
    fun aViewerSeesTheValuesWithoutTheFormsAndCannotChangeThem() {
        val page = viewer.get("/config/m1").body()
        assertTrue(page.contains("<code>8443</code>") && !page.contains("hx-post=\"/config/m1/"), page)
        assertEquals(403, viewer.post("/config/m1/bind", mapOf("value" to "all")).statusCode())
        assertFalse(store.isSet("bind"))
    }

    @Test
    fun aMachineThatCannotBeReadIsSaidSo() {
        assertTrue(admin.get("/config/zz").body().contains("cannot be read"))
    }
}
