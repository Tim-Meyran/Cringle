// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.UserRole
import cringle.management.ManagementStore
import cringle.management.test.ManagementTls
import cringle.router.users.FileUserStore
import cringle.router.users.Scope
import cringle.router.users.UserManager
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Tag("integration")
class WebInviteTest {
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

    private fun anonymous() = WebTestClient(server.port, key)

    private fun createInvite(client: WebTestClient = admin, extra: Map<String, String> = emptyMap()): String {
        val answer = client.post("/invites", mapOf("role-VIEWER" to "on", "scoped" to "operator@machine:m1", "hours" to "2") + extra).body()
        assertTrue(answer.contains("<svg class=\"qr\"") && answer.contains("role=\"img\"") && answer.contains("aria-label=\"Invite link\""), answer)
        return Regex("data-copy=\"(https://[^\"]+/invite/inv_[^\"]+)\"").find(answer)!!.groupValues[1].substringAfter("https://localhost:${server.port}")
    }

    @Test
    fun anInviteLinkCreatesAUserThatSignsIn() {
        val path = createInvite()
        // the polled list never holds the link or the code
        val list = admin.get("/invites/list").body()
        assertFalse(list.contains(path.substringAfterLast('/')) || list.contains("<svg"))
        assertTrue(list.contains("open") && list.contains("operator@machine:m1"), list)

        val visitor = anonymous()
        val form = visitor.get(path)
        assertEquals(200, form.statusCode())
        assertEquals("no-store", form.headers().firstValue("Cache-Control").get())
        assertEquals("no-referrer", form.headers().firstValue("Referrer-Policy").get())
        assertTrue(form.body().contains("viewer") && form.body().contains("operator for machine:m1"), form.body())

        val done = visitor.post(path, mapOf("name" to "newbie"), withCsrf = false)
        assertEquals(200, done.statusCode())
        assertEquals("no-referrer", done.headers().firstValue("Referrer-Policy").get())
        val token = Regex("<code data-copy=\"(crt_[^\"]+)\"").find(done.body())!!.groupValues[1]
        assertTrue(done.body().contains("/login#token=$token") && done.body().contains("<svg class=\"qr\""), done.body())
        val user = users.listUsers().first { it.user.name == "newbie" }
        assertEquals(setOf(UserRole.VIEWER), user.user.roles)
        assertEquals(1, user.user.scoped.size)
        assertNotNull(users.authenticate(token))
        WebTestClient(server.port, key).login(token)

        // the second visit, and a second redeem, show the neutral page
        for (response in listOf(anonymous().get(path), anonymous().post(path, mapOf("name" to "other"), withCsrf = false))) {
            assertEquals(404, response.statusCode())
            assertTrue(response.body().contains("not valid"), response.body())
            assertFalse(response.body().contains("viewer"))
        }
        assertTrue(admin.get("/invites/list").body().contains("used by newbie"))
    }

    @Test
    fun aTakenNameKeepsTheInviteValid() {
        users.createUser("taken", setOf(UserRole.VIEWER))
        val path = createInvite()
        val visitor = anonymous()
        val refused = visitor.post(path, mapOf("name" to "taken"), withCsrf = false)
        assertTrue(refused.body().contains("already exists") && refused.body().contains("<form"), refused.body())
        assertEquals(200, visitor.post(path, mapOf("name" to "fresh"), withCsrf = false).statusCode())
        assertTrue(users.listUsers().any { it.user.name == "fresh" })
    }

    @Test
    fun aRevokedInviteIsNeutral() {
        val path = createInvite()
        val id = Regex("/invites/([^/\"]+)/revoke").find(admin.get("/invites/list").body())!!.groupValues[1]
        admin.post("/invites/$id/revoke")
        assertEquals(404, anonymous().get(path).statusCode())
        assertEquals(404, anonymous().get("/invite/inv_nothing").statusCode())
        assertTrue(admin.get("/invites/list").body().contains("revoked"))
    }

    @Test
    fun badInputIsReportedInTheFlash() {
        assertTrue(admin.post("/invites", mapOf("role-VIEWER" to "on", "scoped" to "nonsense")).body().contains("unknown role"))
        assertTrue(admin.post("/invites", mapOf("role-VIEWER" to "on", "hours" to "1000")).body().contains("lifetime"))
        assertTrue(admin.post("/invites", mapOf("role-VIEWER" to "on", "groups" to "nogroup")).body().contains("unknown group"))
        assertTrue(users.listInvites().isEmpty())
    }

    @Test
    fun onlyUsersWithTheUsersFunctionMayInvite() {
        val viewerId = users.createUser("vera", setOf(UserRole.VIEWER)).user.id
        val viewer = WebTestClient(server.port, key).login(users.createToken(viewerId, "t", null).secret)
        assertEquals(403, viewer.get("/invites").statusCode())
        assertEquals(403, viewer.post("/invites", mapOf("role-VIEWER" to "on")).statusCode())

        val umaId = users.createUser("uma", emptySet()).user.id
        users.grantUser(umaId, UserRole.ADMIN, Scope.parse("function:users"))
        val uma = WebTestClient(server.port, key).login(users.createToken(umaId, "t", null).secret)
        assertEquals(200, uma.get("/invites").statusCode())
        assertTrue(uma.get("/").body().contains("href=\"/invites\""))
        createInvite(uma)
        assertEquals("uma", users.listInvites().single().createdBy)
    }
}
