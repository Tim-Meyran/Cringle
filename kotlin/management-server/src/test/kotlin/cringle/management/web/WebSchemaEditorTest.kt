// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.UserRole
import cringle.management.ManagementStore
import cringle.management.test.ManagementTls
import cringle.repository.PackageRepository
import cringle.repository.v1.ListPackagesRequest
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

@Tag("integration")
class WebSchemaEditorTest {
    @TempDir
    lateinit var dir: Path

    private val closeables = ArrayList<AutoCloseable>()
    private lateinit var core: cringle.management.ManagementCore
    private lateinit var admin: WebTestClient
    private lateinit var viewer: WebTestClient

    @BeforeEach
    fun start() {
        val users = UserManager(FileUserStore(dir.resolve("users.json")))
        val token = users.bootstrap()!!
        val viewerToken = users.createToken(users.createUser("vera", setOf(UserRole.VIEWER)).user.id, "web", null).secret
        val tls = ManagementTls(dir.resolve("tls"))
        val repository = tls.startRepository(PackageRepository(dir.resolve("repo")))
        closeables += AutoCloseable { repository.stop() }
        core = tls.core(ManagementStore(dir.resolve("state.json")), "127.0.0.1:${repository.port}")
        closeables += core
        val server = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        closeables += server
        admin = WebTestClient(server.port, tls.identity.publicKeyFingerprint).login(token)
        viewer = WebTestClient(server.port, tls.identity.publicKeyFingerprint).login(viewerToken)
    }

    @AfterEach
    fun stop() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    private val model = """{"namespace": "acme.orders", "types": [{"name": "Order", "kind": "record", "values": "", "fields": [{"name": "id", "type": "cringle.std/String", "wrap": ""}, {"name": "note", "type": "cringle.std/String", "wrap": "optional"}]}]}"""

    @Test
    fun aSchemaIsCreatedEditedSavedAndPublished() {
        assertTrue(admin.post("/drafts", mapOf("kind" to "schema", "name" to "acme-orders")).body().contains("<td>schema</td><td>acme-orders</td>"))
        assertTrue(admin.post("/drafts", mapOf("kind" to "schema", "name" to "acme-orders")).body().contains("ALREADY_EXISTS"))
        assertTrue(admin.post("/drafts", mapOf("kind" to "schema", "name" to "Bad Name")).body().contains("class=\"error\""))

        // the editor page carries the form data of the draft
        val page = admin.get("/schemas/acme-orders").body()
        assertTrue(page.contains("x-data") && page.contains("standard-types"), page)

        // an invalid document is shown with its path and can be saved as work in progress, but not published
        val badModel = model.replace("\"Order\"", "\"order\"")
        val check = admin.post("/schemas/acme-orders/check", mapOf("model" to badModel)).body()
        assertTrue(check.contains("class=\"error\"") && check.contains("$.types.order"), check)
        assertTrue(admin.post("/schemas/acme-orders/save", mapOf("model" to badModel, "version" to "1.0.0", "revision" to "1")).body().contains("Saved as revision 2"))
        assertTrue(admin.post("/drafts/schema/acme-orders/publish").body().contains("INVALID_ARGUMENT"))

        val saved = admin.post("/schemas/acme-orders/save", mapOf("model" to model, "version" to "1.2.0", "revision" to "2")).body()
        assertTrue(saved.contains("Saved as revision 3") && saved.contains("The document is valid") && saved.contains("acme.orders"), saved)
        // a stale revision is refused
        assertTrue(admin.post("/schemas/acme-orders/save", mapOf("model" to model, "version" to "1.2.0", "revision" to "2")).body().contains("changed meanwhile"))
        // the saved draft comes back into the form
        assertTrue(admin.get("/schemas/acme-orders").body().contains("Order"))

        val published = admin.post("/drafts/schema/acme-orders/publish").body()
        assertTrue(published.contains("Published acme-orders 1.2.0"), published)
        val packages = runBlocking { core.repository().listPackages(ListPackagesRequest.getDefaultInstance()).packagesList }
        assertEquals(listOf("acme-orders 1.2.0"), packages.map { "${it.name} ${it.version}" })

        assertFalse(admin.post("/drafts/schema/acme-orders/delete").body().contains("<td>acme-orders</td>"))
    }

    @Test
    fun aViewerCanLookButNotChange() {
        admin.post("/drafts", mapOf("kind" to "schema", "name" to "acme-orders"))
        assertTrue(viewer.get("/drafts").body().contains("acme-orders"))
        assertFalse(viewer.get("/drafts").body().contains("hx-post=\"/drafts"))
        assertEquals(200, viewer.get("/schemas/acme-orders").statusCode())
        assertEquals(403, viewer.post("/drafts", mapOf("kind" to "schema", "name" to "x")).statusCode())
        assertEquals(403, viewer.post("/schemas/acme-orders/save", mapOf("model" to model)).statusCode())
        assertEquals(403, viewer.post("/drafts/schema/acme-orders/publish").statusCode())
        assertEquals(404, admin.get("/schemas/nothing").statusCode())
        assertEquals(404, admin.get("/schemas/..").statusCode())
    }
}
