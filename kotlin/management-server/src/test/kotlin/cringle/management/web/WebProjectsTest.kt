// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.BlockDefinition
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import cringle.contract.UserRole
import cringle.management.ManagementStore
import cringle.management.test.ManagementTls
import cringle.packaging.PackageReader
import cringle.repository.PackageRepository
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import cringle.testkit.TestPluginBuilder
import java.nio.file.Files
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
class WebProjectsTest {
    @TempDir
    lateinit var dir: Path

    private val closeables = ArrayList<AutoCloseable>()
    private lateinit var admin: WebTestClient
    private lateinit var viewer: WebTestClient

    @BeforeEach
    fun start() {
        val string = SchemaRef("cringle.std", "String")
        val message = setOf(TetherType.MESSAGE)
        val plugin = TestPluginBuilder("acme-flow", "1.0.0").provider("com.acme.FlowProvider")
            .block(BlockDefinition("src", emptyList(), listOf(PortDefinition("out", PortDirection.OUT, message, string)), emptyList()))
            .block(BlockDefinition("sink", emptyList(), listOf(PortDefinition("in", PortDirection.IN, message, string)), emptyList()))
            .build(Files.createDirectories(dir.resolve("plugins")))
        val repo = PackageRepository(dir.resolve("repo"))
        repo.publish(plugin.file)
        val users = UserManager(FileUserStore(dir.resolve("users.json")))
        val token = users.bootstrap()!!
        val viewerToken = users.createToken(users.createUser("vera", setOf(UserRole.VIEWER)).user.id, "web", null).secret
        val tls = ManagementTls(dir.resolve("tls"))
        val repository = tls.startRepository(repo)
        closeables += AutoCloseable { repository.stop() }
        val core = tls.core(ManagementStore(dir.resolve("state.json")), "127.0.0.1:${repository.port}")
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

    @Test
    fun projectsAreCreatedListedAndDeleted() {
        assertTrue(admin.get("/projects").body().contains("No project yet"))
        val created = admin.post("/projects", mapOf("name" to "acme-shop")).body()
        assertTrue(created.contains("<strong>acme-shop</strong>") && created.contains("1.0.0"), created)
        assertTrue(admin.post("/projects", mapOf("name" to "acme-shop")).body().contains("exists"), "a name is used once")
        assertTrue(admin.post("/projects", mapOf("name" to "Not A Name")).body().contains("notice error"))
        assertTrue(admin.get("/projects/list").body().contains("acme-shop"))
        assertEquals(404, admin.get("/projects/nothing").statusCode())

        admin.postFile("/projects/acme-shop/files", "file", "logo.png", byteArrayOf(1, 2))
        val deleted = admin.post("/projects/acme-shop/delete").body()
        assertTrue(deleted.contains("Deleted the project acme-shop") && !deleted.contains("<strong>acme-shop</strong>"), deleted)
        assertFalse(Files.exists(dir.resolve("data")) && Files.walk(dir).use { s -> s.anyMatch { it.fileName.toString() == "logo.png" } }, "its files go with it")
    }

    @Test
    fun theTreeShowsBlueprintSchemasAndFiles() {
        admin.post("/projects", mapOf("name" to "acme-shop"))
        admin.post("/drafts", mapOf("kind" to "schema", "name" to "shop-types"))
        val tree = admin.get("/projects/acme-shop").body()
        assertTrue(tree.contains("class=\"tree\"") && tree.contains("project.json") && tree.contains("blueprints/") && tree.contains("schemas/") && tree.contains("binaries/"), tree)
        assertTrue(tree.contains("href=\"/blueprints/acme-shop\">Open in the editor"), tree)
        assertTrue(tree.contains("0 blocks, 0 tethers"), tree)
        assertTrue(tree.contains("<option>shop-types</option>"), "a schema draft can be added")

        // a schema is added to the project and removed again
        val added = admin.post("/projects/acme-shop/schemas/add", mapOf("schema" to "shop-types")).body()
        assertTrue(added.contains("shop-types.json") && added.contains("href=\"/schemas/shop-types\">Open") && !added.contains("<option>shop-types</option>"), added)
        assertTrue(admin.post("/projects/acme-shop/schemas/add", mapOf("schema" to "nothing")).body().contains("there is no schema draft"))
        val removed = admin.post("/projects/acme-shop/schemas/remove?schema=shop-types").body()
        assertFalse(removed.contains("shop-types.json"), removed)
        assertTrue(removed.contains("<option>shop-types</option>"), "the draft itself stays")

        // files: upload with a path, as a tree, download, delete
        val up = admin.postFile("/projects/acme-shop/files", "file", "index.html", "<p>hi</p>".toByteArray(), mapOf("path" to "web/index.html")).body()
        assertTrue(up.contains("Stored web/index.html (9 bytes)") && up.contains("web/") && up.contains("index.html") && up.contains("9 B"), up)
        val plain = admin.postFile("/projects/acme-shop/files", "file", "logo.png", byteArrayOf(1, 2, 3)).body()
        assertTrue(plain.contains("logo.png") && plain.contains("2 files"), plain)
        val download = admin.get("/projects/acme-shop/file?path=web/index.html")
        assertEquals(200, download.statusCode())
        assertEquals("<p>hi</p>", download.body())
        assertTrue(download.headers().firstValue("Content-Disposition").orElse("").contains("attachment; filename=\"index.html\""))
        assertEquals(404, admin.get("/projects/acme-shop/file?path=nothing.txt").statusCode())
        assertEquals(404, admin.get("/projects/acme-shop/file?path=../../state.json").statusCode())
        assertTrue(admin.postFile("/projects/acme-shop/files", "file", "x.bin", byteArrayOf(1), mapOf("path" to "../evil.bin")).body().contains("notice error"))
        assertTrue(admin.post("/projects/acme-shop/files/delete", mapOf("path" to "web")).body().contains("Deleted web."))
        assertFalse(admin.get("/projects/acme-shop").body().contains("<span class=\"file\">index.html</span>"))
    }

    @Test
    fun aViewerSeesTheProjectButChangesNothing() {
        admin.post("/projects", mapOf("name" to "acme-shop"))
        admin.postFile("/projects/acme-shop/files", "file", "logo.png", byteArrayOf(1))
        val page = viewer.get("/projects/acme-shop").body()
        assertTrue(page.contains("logo.png") && page.contains("Download"), page)
        assertFalse(page.contains("Upload") || page.contains("hx-post=\"/projects/acme-shop/files/delete\"") || page.contains("Publish"), "no buttons that change something")
        assertEquals(403, viewer.post("/projects", mapOf("name" to "other")).statusCode())
        assertEquals(403, viewer.postFile("/projects/acme-shop/files", "file", "x.bin", byteArrayOf(1)).statusCode())
        assertEquals(403, viewer.post("/projects/acme-shop/files/delete", mapOf("path" to "logo.png")).statusCode())
        assertEquals(403, viewer.post("/projects/acme-shop/delete").statusCode())
        assertEquals(1, Regex("logo.png").findAll(admin.get("/projects/acme-shop").body()).count().coerceAtMost(1))
    }

    @Test
    fun publishingCarriesTheFilesIntoThePackage() {
        admin.post("/projects", mapOf("name" to "acme-shop"))
        admin.postFile("/projects/acme-shop/files", "file", "index.html", "<p>hi</p>".toByteArray(), mapOf("path" to "web/index.html"))
        admin.postFile("/projects/acme-shop/files", "file", "logo.png", byteArrayOf(9, 8, 7))
        // a blueprint with two blocks, so that the project can be published
        val graph = """{"drawflow": {"Home": {"data": {
            "1": {"id": 1, "name": "acme-flow/src", "data": {"id": "a", "block": "acme-flow/src", "config": {}}, "class": "cringle-block", "html": "", "typenode": false,
                  "inputs": {}, "outputs": {"output_1": {"connections": [{"node": "2", "output": "input_1"}]}}, "pos_x": 10, "pos_y": 10},
            "2": {"id": 2, "name": "acme-flow/sink", "data": {"id": "b", "block": "acme-flow/sink", "config": {}}, "class": "cringle-block", "html": "", "typenode": false,
                  "inputs": {"input_1": {"connections": [{"node": "1", "input": "output_1"}]}}, "outputs": {}, "pos_x": 300, "pos_y": 10}}}}}"""
        assertTrue(admin.post("/blueprints/acme-shop/save", mapOf("graph" to graph, "options" to "{}", "version" to "1.2.0", "revision" to "1")).body().contains("The blueprint is valid"))
        val published = admin.post("/projects/acme-shop/publish").body()
        assertTrue(published.contains("Published acme-shop 1.2.0"), published)
        val file = Files.createTempFile("shop", ".zip")
        Files.copy(PackageRepository(dir.resolve("repo")).file("acme-shop", "1.2.0"), file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val pkg = PackageReader.readProject(file)
        assertTrue(pkg.files.containsAll(listOf("binaries/logo.png", "binaries/web/index.html")), pkg.files.toString())
        assertEquals(1, pkg.blueprints.size)
    }
}
