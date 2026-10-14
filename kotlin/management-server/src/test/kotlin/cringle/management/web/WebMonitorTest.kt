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
class WebMonitorTest {
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
    fun theMonitorIsInTheNavigationAndTheListHoldsNoFeedback() {
        val page = admin.get("/monitor")
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("href=\"/monitor\""), page.body())
        val list = admin.get("/monitor/list").body()
        assertTrue(list.contains("No fabric is running."), list)
        assertFalse(list.contains("class=\"notice") || list.contains("hx-swap-oob"))
    }

    @Test
    fun anUnknownFabricGivesAnErrorAndNoGraph() {
        val graph = admin.get("/monitor/nope/graph")
        assertEquals(502, graph.statusCode())
        assertTrue(graph.body().contains("\"error\""), graph.body())
        val post = admin.post("/monitor/nope/breakpoint", mapOf("tether" to "a.out -> b.in", "mode" to "on")).body()
        assertTrue(post.contains("hx-swap-oob=\"beforeend:#flash\"") && post.contains("class=\"notice error\""), post)
    }

    @Test
    fun aViewerMayLookButNotSetABreakpoint() {
        val id = users.createUser("vera", setOf(UserRole.VIEWER)).user.id
        val viewer = WebTestClient(server.port, key).login(users.createToken(id, "t", null).secret)
        assertEquals(200, viewer.get("/monitor").statusCode())
        assertEquals(403, viewer.post("/monitor/f/breakpoint", mapOf("tether" to "t")).statusCode())
        assertEquals(403, viewer.post("/monitor/f/resume", mapOf("tether" to "t")).statusCode())
    }
}
