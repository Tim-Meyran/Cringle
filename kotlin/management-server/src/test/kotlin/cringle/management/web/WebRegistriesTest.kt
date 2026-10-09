// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.UserRole
import cringle.management.ManagementStore
import cringle.management.test.ManagementTls
import cringle.router.users.FileUserStore
import cringle.router.users.PublicKeyPem
import cringle.router.users.UserManager
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The page for the registries of other sites (#295). */
@Tag("integration")
class WebRegistriesTest {
    @TempDir
    lateinit var dir: Path

    private val closeables = ArrayList<AutoCloseable>()
    private lateinit var users: UserManager
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
        val server = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        closeables += server
        admin = WebTestClient(server.port, tls.identity.publicKeyFingerprint).login(adminToken)
        viewer = WebTestClient(server.port, tls.identity.publicKeyFingerprint).login(viewerToken)
    }

    @AfterEach
    fun stop() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    private val otherKey = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @Test
    fun aRegistryIsTrustedGrantedAndUntrusted() {
        val page = admin.get("/registries").body()
        assertTrue(page.contains("This registry&#39;s key") || page.contains("This registry's key"), "shows the key of this site")
        assertTrue(page.contains("BEGIN PUBLIC KEY") && page.contains("No trusted registry yet."), page)
        val trusted = admin.post("/registries", mapOf("name" to "site-b", "key" to PublicKeyPem.encode(otherKey.public), "role-VIEWER" to "on")).body()
        assertTrue(trusted.contains("<strong>site-b</strong>") && trusted.contains("<span class=\"tag\">viewer</span>"), trusted)
        assertEquals(listOf("site-b"), users.listRegistries().map { it.name })

        val granted = admin.post("/registries/site-b/grant", mapOf("role" to "OPERATOR", "kind" to "machine", "name" to "m1")).body()
        assertTrue(granted.contains("1 scoped") && granted.contains("machine:m1"), granted)
        assertTrue(admin.post("/registries/site-b/revoke", mapOf("role" to "OPERATOR", "scope" to "machine:m1")).body().contains("none"))

        val bad = admin.post("/registries", mapOf("name" to "site-c", "key" to "not a key")).body()
        assertTrue(bad.contains("no PEM block"), bad)
        assertTrue(admin.post("/registries", mapOf("name" to "site-b", "key" to PublicKeyPem.encode(otherKey.public))).body().contains("already trusted"))

        assertTrue(admin.post("/registries/site-b/delete").body().contains("No trusted registry yet."))
        assertTrue(users.listRegistries().isEmpty())
    }

    @Test
    fun aTokenIsIssuedOnceAndAcceptedByTheUserManagerThatTrustsTheKey() {
        val created = admin.post("/registries/token", mapOf("user" to "alice", "as" to "site-a", "hours" to "2")).body()
        val token = Regex("fed1\\.[A-Za-z0-9_.-]+").find(created)?.value
        assertNotNull(token, created)
        assertTrue(created.contains("alice@site-a") && created.contains("shown once"), created)
        // the key of this server is the one the other site would trust
        val key = PublicKeyPem.parse(admin.get("/registries").body().substringAfter("<pre class=\"key\">").substringBefore("</pre>"))
        val other = UserManager(FileUserStore(dir.resolve("other.json")))
        assertNull(other.authenticate(token!!))
        other.trustRegistry("site-a", key, setOf(UserRole.VIEWER))
        assertEquals("alice@site-a", other.authenticate(token)!!.name)
        // a bad lifetime and a bad user
        assertTrue(admin.post("/registries/token", mapOf("user" to "alice", "as" to "site-a", "hours" to "9999")).body().contains("at most 30 days"))
        assertTrue(admin.post("/registries/token", mapOf("user" to "a@b", "as" to "site-a")).body().contains("has an @"))
    }

    @Test
    fun onlyTheAdministratorsOfUsersMaySeeAndChangeIt() {
        assertEquals(403, viewer.get("/registries").statusCode())
        assertEquals(403, viewer.post("/registries", mapOf("name" to "x", "key" to PublicKeyPem.encode(otherKey.public))).statusCode())
        assertEquals(403, viewer.post("/registries/token", mapOf("user" to "a", "as" to "b")).statusCode())
        assertTrue(users.listRegistries().isEmpty())
    }
}
