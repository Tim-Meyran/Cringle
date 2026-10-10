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

@Tag("integration")
class WebDebugTest {
    @TempDir
    lateinit var dir: Path

    private val closeables = ArrayList<AutoCloseable>()
    private lateinit var users: UserManager
    private lateinit var server: WebServer
    private lateinit var key: String
    private lateinit var admin: WebTestClient

    @BeforeEach
    fun start() {
        users = UserManager(FileUserStore(dir.resolve("users.json")))
        val adminToken = users.bootstrap()!!
        val tls = ManagementTls(dir.resolve("tls"))
        val core = tls.core(ManagementStore(dir.resolve("state.json")))
        closeables += core
        server = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        closeables += server
        key = tls.identity.publicKeyFingerprint
        admin = WebTestClient(server.port, key).login(adminToken)
    }

    @AfterEach
    fun stop() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    @Test
    fun thePageAndTheListAreThereAndTheListHoldsNoFeedback() {
        val page = admin.get("/debug")
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("Debugger") && page.body().contains("href=\"/debug\""), page.body())
        val list = admin.get("/debug/list").body()
        assertTrue(list.contains("No breakpoint is set.") && list.contains("/debug/break"))
        assertFalse(list.contains("class=\"notice") || list.contains("hx-swap-oob"))
    }

    @Test
    fun aBreakpointOnAnUnknownFabricShowsTheErrorInTheFlash() {
        val answer = admin.post("/debug/break", mapOf("fabric" to "nope", "tether" to "a.out -> b.in", "mode" to "on")).body()
        assertTrue(answer.contains("hx-swap-oob=\"beforeend:#flash\"") && answer.contains("class=\"notice error\""), answer)
        val resume = admin.post("/debug/resume", mapOf("fabric" to "nope")).body()
        assertTrue(resume.contains("class=\"notice error\""), resume)
    }

    @Test
    fun aViewerMayNotUseTheDebugger() {
        val id = users.createUser("vera", setOf(UserRole.VIEWER)).user.id
        val viewer = WebTestClient(server.port, key).login(users.createToken(id, "t", null).secret)
        assertEquals(403, viewer.get("/debug").statusCode())
        assertEquals(403, viewer.post("/debug/break", mapOf("fabric" to "f", "tether" to "t")).statusCode())
        assertFalse(viewer.get("/").body().contains("href=\"/debug\""), "no entry in the navigation")
    }
}
