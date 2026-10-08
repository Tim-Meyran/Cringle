// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.UserRole
import cringle.management.ManagementStore
import cringle.management.test.ManagementTls
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The server side of #242: the lists refresh by morphing and wait while the user works in them. What the browser does with that is checked by hand (see
 * `docs/webui.md`); here is what the pages have to say for it to work.
 */
@Tag("integration")
class WebUiRefreshTest {
    @TempDir
    lateinit var dir: Path

    private val closeables = ArrayList<AutoCloseable>()
    private lateinit var ui: WebTestClient

    @BeforeEach
    fun start() {
        val users = UserManager(FileUserStore(dir.resolve("users.json")))
        val token = users.bootstrap()!!
        users.createToken(users.createUser("vera", setOf(UserRole.VIEWER)).user.id, "web", null)
        val tls = ManagementTls(dir.resolve("tls"))
        val core = tls.core(ManagementStore(dir.resolve("state.json")))
        closeables += core
        val server = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        closeables += server
        ui = WebTestClient(server.port, tls.identity.publicKeyFingerprint).login(token)
    }

    @AfterEach
    fun stop() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    private val polled = listOf("machines", "engines", "fabrics", "users", "groups", "trust", "dwh")

    @Test
    fun everyPolledListWaitsForTheUserAndMorphs() {
        for (name in polled) {
            val page = ui.get("/$name").body()
            assertTrue(page.contains("hx-get=\"/$name/list\" hx-trigger=\"every 5s [cringleIdle()]\" hx-swap=\"morph:innerHTML\""), "$name: $page")
            assertTrue(page.contains("id=\"paused\"") && page.contains("Refresh now"), name)
        }
    }

    @Test
    fun noListIsSwappedByReplacingItsContent() {
        for (name in polled + listOf("metrics", "logs", "drafts")) {
            val page = ui.get("/$name").body()
            assertFalse(page.contains("hx-swap=\"innerHTML\""), "$name still replaces: $page")
            assertFalse(ui.get("/$name/list").body().contains("hx-swap=\"innerHTML\""), "$name/list still replaces")
        }
        assertTrue(ui.get("/logs").body().contains("hx-swap=\"morph:innerHTML\""))
    }

    @Test
    fun theLayoutLoadsTheMorphExtensionAndTheFileIsServed() {
        val page = ui.get("/machines").body()
        assertTrue(page.contains("<body hx-ext=\"morph\""), page)
        assertTrue(page.indexOf("/static/vendor/idiomorph-ext.min.js") in 1 until page.indexOf("/static/app.js"), "loaded before app.js")
        val file = ui.get("/static/vendor/idiomorph-ext.min.js")
        assertEquals(200, file.statusCode())
        assertTrue(file.headers().firstValue("Content-Type").get().startsWith("text/javascript"))
        assertTrue(file.body().contains("Idiomorph"))
        assertTrue(ui.get("/static/app.js").body().contains("window.cringleIdle"))
    }

    @Test
    fun theMetricsPageKeepsNoClientStateInTheSwappedFragment() {
        val fragment = ui.get("/metrics/list").body()
        assertFalse(fragment.contains("x-show") || fragment.contains("x-data"), fragment)
    }

    @Test
    fun anEmptyListSaysWhatToDoNext() {
        val machines = ui.get("/machines/list").body()
        assertTrue(machines.contains("class=\"empty\"") && machines.contains("Add the first one"), machines)
        assertFalse(machines.contains("<table"), "no empty table")
        assertTrue(ui.get("/engines/list").body().contains("No engine yet"))
        assertTrue(ui.get("/groups/list").body().contains("No group yet"))
        assertTrue(ui.get("/trust/list").body().contains("class=\"empty\""))
    }
}
