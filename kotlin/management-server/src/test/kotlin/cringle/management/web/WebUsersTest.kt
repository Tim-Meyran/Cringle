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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Tag("integration")
class WebUsersTest {
    @TempDir
    lateinit var dir: Path

    private val closeables = ArrayList<AutoCloseable>()
    private lateinit var users: UserManager
    private lateinit var server: WebServer
    private lateinit var key: String
    private lateinit var admin: WebTestClient
    private lateinit var viewer: WebTestClient

    @BeforeEach
    fun start() {
        users = UserManager(FileUserStore(dir.resolve("users.json")))
        val adminToken = users.bootstrap()!!
        val viewerToken = users.createToken(users.createUser("vera", setOf(UserRole.VIEWER)).user.id, "web", null).secret
        val tls = ManagementTls(dir.resolve("tls"))
        val core = tls.core(ManagementStore(dir.resolve("state.json")))
        closeables += core
        server = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        closeables += server
        key = tls.identity.publicKeyFingerprint
        admin = WebTestClient(server.port, key).login(adminToken)
        viewer = WebTestClient(server.port, key).login(viewerToken)
    }

    @AfterEach
    fun stop() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    private fun idOf(name: String) = users.listUsers().first { it.user.name == name }.user.id

    @Test
    fun aNewTokenComesWithALoginLinkAndAQrCode() {
        val answer = admin.post("/users/${idOf("vera")}/tokens", mapOf("label" to "phone", "hours" to "2")).body()
        val secret = Regex("<code data-copy=\"(crt_[^\"]+)\"").find(answer)!!.groupValues[1]
        val link = Regex("<code data-copy=\"(https://[^\"]+)\"").find(answer)!!.groupValues[1]
        assertEquals("https://localhost:${server.port}/login#token=$secret", link)
        assertTrue(answer.contains("<svg class=\"qr\"") && answer.contains("role=\"img\"") && answer.contains("aria-label=\"Login link for vera\""), answer)
        assertTrue(answer.contains("It expires"), "the expiry is said")
        // the polled list never holds the token, the link or the code
        val list = admin.get("/users/list").body()
        assertFalse(list.contains(secret) || list.contains("<svg class=\"qr\""))
    }

    @Test
    fun theWebUrlIsTheBaseOfTheLink() {
        val tls = ManagementTls(dir.resolve("tls2"))
        val core = tls.core(ManagementStore(dir.resolve("state2.json")))
        closeables += core
        val other = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO, webUrl = "https://cringle.example:8443/").start()
        closeables += other
        val client = WebTestClient(other.port, tls.identity.publicKeyFingerprint).login(users.createToken(idOf("admin"), "x", null).secret)
        val answer = client.post("/users/${idOf("vera")}/tokens", mapOf("label" to "phone")).body()
        assertTrue(Regex("<code data-copy=\"https://cringle\\.example:8443/login#token=crt_[^\"]+\"").containsMatchIn(answer), answer)
    }

    @Test
    fun aCreatedUserGetsATokenThatLogsIn() {
        assertTrue(admin.post("/groups", mapOf("name" to "ops", "role-OPERATOR" to "on")).body().contains("<strong>ops</strong></td><td><span class=\"tag\">operator</span>"))
        val created = admin.post("/users", mapOf("name" to "bob", "role-VIEWER" to "on", "groups" to "ops")).body()
        assertTrue(created.contains("<strong>bob</strong>") && created.contains("<span class=\"tag\">viewer</span>") && created.contains("<span class=\"tag\">operator</span>") && created.contains("<span class=\"tag\">ops</span>"), created)

        val answer = admin.post("/users/${idOf("bob")}/tokens", mapOf("label" to "laptop", "hours" to "1")).body()
        val token = Regex("<code data-copy=\"([^\"]+)\"[^>]*>[^<]+</code> \\(shown once").find(answer)!!.groupValues[1]
        assertNotNull(users.authenticate(token))
        // the value is not kept: a later listing does not show it
        assertFalse(admin.get("/users/list").body().contains(token))
        WebTestClient(server.port, key).login(token)

        val tokenId = users.listTokens(idOf("bob")).single().id
        admin.post("/tokens/$tokenId/revoke")
        assertNull(users.authenticate(token))
        assertTrue(admin.get("/users/list").body().contains("revoked"))
    }

    @Test
    fun theLastAdminCannotBeDeletedAndInputIsChecked() {
        val adminId = users.listUsers().first { UserRole.ADMIN in it.effectiveRoles }.user.id
        val refused = admin.post("/users/$adminId/delete").body()
        assertTrue(refused.contains("class=\"notice error\"") && refused.contains("the last admin cannot be deleted"), refused)
        assertTrue(admin.post("/users", mapOf("name" to "")).body().contains("class=\"notice error\""))
        assertTrue(admin.post("/users", mapOf("name" to "x", "groups" to "nope")).body().contains("unknown group"))
        assertTrue(admin.post("/users/${idOf("vera")}/tokens", mapOf("hours" to "abc")).body().contains("number of hours"))
        assertFalse(admin.post("/users/${idOf("vera")}/delete").body().contains("<strong>vera</strong>"))
    }

    @Test
    fun valuesAreEscaped() {
        val page = admin.post("/users", mapOf("name" to "<b>x</b>", "role-VIEWER" to "on")).body()
        assertTrue(page.contains("&lt;b&gt;x&lt;/b&gt;") && !page.contains("<b>x</b>"), page)
    }

    @Test
    fun aUserWithoutManageUsersGetsNoPageAndNoNavigationEntry() {
        assertEquals(403, viewer.get("/users").statusCode())
        assertEquals(403, viewer.get("/groups").statusCode())
        assertEquals(403, viewer.post("/users", mapOf("name" to "evil")).statusCode())
        assertEquals(403, viewer.post("/tokens/x/revoke").statusCode())
        val home = viewer.get("/").body()
        assertFalse(home.contains("href=\"/users\""), home)
        assertTrue(admin.get("/").body().contains("href=\"/users\""))
    }

    @Test
    fun scopedRolesAreGrantedAndRevokedThroughThePage() {
        val id = users.createUser("sam", emptySet()).user.id
        users.createGroup("ops", emptySet())
        val granted = admin.post("/users/$id/grant", mapOf("role" to "OPERATOR", "kind" to "machine", "name" to "m1")).body()
        assertTrue(granted.contains("<code>machine:m1</code>") && granted.contains("1 scoped"), granted)
        assertEquals(1, users.listUsers().single { it.user.id == id }.user.scoped.size)
        val revoked = admin.post("/users/$id/revoke", mapOf("role" to "OPERATOR", "scope" to "machine:m1")).body()
        assertFalse(revoked.contains("machine:m1"), revoked)
        // a group, and errors are flashes
        assertTrue(admin.post("/groups/ops/grant", mapOf("role" to "VIEWER", "kind" to "project", "name" to "shop")).body().contains("<code>project:shop</code>"))
        val bad = admin.post("/users/$id/grant", mapOf("role" to "OPERATOR", "kind" to "function", "name" to "everything")).body()
        assertTrue(bad.contains("hx-swap-oob=\"beforeend:#flash\"") && bad.contains("class=\"notice error\"") && bad.contains("unknown function"), bad)
        assertEquals(403, viewer.post("/users/$id/grant", mapOf("role" to "OPERATOR", "kind" to "machine", "name" to "m1")).statusCode())
        assertEquals(403, viewer.post("/groups/ops/revoke", mapOf("role" to "VIEWER", "scope" to "project:shop")).statusCode())
    }
}
