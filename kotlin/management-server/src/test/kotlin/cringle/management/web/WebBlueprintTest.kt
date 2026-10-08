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
import cringle.repository.PackageRepository
import cringle.repository.v1.ListPackagesRequest
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import cringle.testkit.TestPluginBuilder
import java.nio.file.Files
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
class WebBlueprintTest {
    @TempDir
    lateinit var dir: Path

    private val closeables = ArrayList<AutoCloseable>()
    private lateinit var core: cringle.management.ManagementCore
    private lateinit var admin: WebTestClient
    private lateinit var viewer: WebTestClient

    @BeforeEach
    fun start() {
        val string = SchemaRef("cringle.std", "String")
        val int = SchemaRef("cringle.std", "Int")
        fun block(name: String, vararg ports: PortDefinition) = BlockDefinition(name, emptyList(), ports.toList(), emptyList())
        val plugin = TestPluginBuilder("acme-flow", "1.0.0").provider("com.acme.FlowProvider")
            .block(block("src", PortDefinition("out", PortDirection.OUT, setOf(TetherType.MESSAGE, TetherType.STREAM), string)))
            .block(block("bytes", PortDefinition("out", PortDirection.OUT, setOf(TetherType.BYTE_STREAM), string)))
            .block(block("sink", PortDefinition("in", PortDirection.IN, setOf(TetherType.MESSAGE), string)))
            .block(block("numbers", PortDefinition("in", PortDirection.IN, setOf(TetherType.MESSAGE), int)))
            .build(Files.createDirectories(dir.resolve("plugins")))
        val repo = PackageRepository(dir.resolve("repo"))
        repo.publish(plugin.file)

        val users = UserManager(FileUserStore(dir.resolve("users.json")))
        val token = users.bootstrap()!!
        val viewerToken = users.createToken(users.createUser("vera", setOf(UserRole.VIEWER)).user.id, "web", null).secret
        val tls = ManagementTls(dir.resolve("tls"))
        val repository = tls.startRepository(repo)
        closeables += AutoCloseable { repository.stop() }
        core = tls.core(ManagementStore(dir.resolve("state.json")), "127.0.0.1:${repository.port}")
        closeables += core
        val server = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        closeables += server
        admin = WebTestClient(server.port, tls.identity.publicKeyFingerprint).login(token)
        viewer = WebTestClient(server.port, tls.identity.publicKeyFingerprint).login(viewerToken)
        assertTrue(admin.post("/drafts", mapOf("kind" to "project", "name" to "flow-app")).body().contains("<td>project</td><td>flow-app</td>"))
    }

    @AfterEach
    fun stop() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    private fun node(number: Int, id: String, block: String, outputs: Map<String, List<String>> = emptyMap(), inputs: Map<String, List<String>> = emptyMap(), config: String = "{}") =
        """"$number": {"id": $number, "name": "$block", "data": {"id": "$id", "block": "$block", "config": $config}, "class": "cringle-block", "html": "", "typenode": false,
            "inputs": {${inputs.entries.joinToString(",") { (k, v) -> "\"$k\": {\"connections\": [${v.joinToString(",") { """{"node": "$it", "input": "output_1"}""" }}]}" }}},
            "outputs": {${outputs.entries.joinToString(",") { (k, v) -> "\"$k\": {\"connections\": [${v.joinToString(",") { """{"node": "$it", "output": "input_1"}""" }}]}" }}},
            "pos_x": ${number * 100}, "pos_y": 40}"""

    private fun graph(vararg nodes: String) = """{"drawflow": {"Home": {"data": {${nodes.joinToString(",")}}}}}"""

    private fun check(from: String, out: Int, to: String, input: Int) =
        admin.post("/blueprints/flow-app/check", mapOf("fromBlock" to from, "fromOutput" to "$out", "toBlock" to to, "toInput" to "$input")).body()

    @Test
    fun theServerDecidesWhetherTwoPortsMayBeConnected() {
        assertEquals("""{"ok":true,"type":"MESSAGE"}""", check("acme-flow/src", 1, "acme-flow/sink", 1))
        val schema = check("acme-flow/src", 1, "acme-flow/numbers", 1)
        assertTrue(schema.contains("\"ok\":false") && schema.contains("String"), schema)
        val type = check("acme-flow/bytes", 1, "acme-flow/sink", 1)
        assertTrue(type.contains("\"ok\":false") && type.contains("share no tether type"), type)
        assertTrue(check("acme-flow/src", 2, "acme-flow/sink", 1).contains("has no such output"))
        assertTrue(check("acme-flow/sink", 1, "acme-flow/src", 1).contains("has no such output"), "a sink has no output to connect from")
        assertTrue(check("acme-flow/nothing", 1, "acme-flow/sink", 1).contains("unknown block"))
    }

    @Test
    fun aBlueprintIsSavedReloadedPublishedAndTheStaleRevisionIsRefused() {
        val page = admin.get("/blueprints/flow-app").body()
        assertTrue(page.contains("blueprint-editor") && page.contains("acme-flow/src") && page.contains("/static/vendor/drawflow.min.js"), page)

        val connected = graph(node(1, "reader", "acme-flow/src", outputs = mapOf("output_1" to listOf("2"))), node(2, "writer", "acme-flow/sink", inputs = mapOf("input_1" to listOf("1"))))
        val saved = admin.post("/blueprints/flow-app/save", mapOf("graph" to connected, "options" to "{}", "version" to "1.3.0", "roles" to "a", "revision" to "1")).body()
        assertTrue(saved.contains("Saved as revision 2") && saved.contains("The blueprint is valid"), saved)
        assertTrue(admin.post("/blueprints/flow-app/save", mapOf("graph" to connected, "options" to "{}", "revision" to "1")).body().contains("changed meanwhile"))

        // the saved blueprint comes back into the editor with its tether
        val reloaded = admin.get("/blueprints/flow-app").body()
        assertTrue(reloaded.contains("reader") && reloaded.contains("writer") && reloaded.contains("connections"), reloaded)
        assertTrue(reloaded.contains("&quot;node&quot;:&quot;2&quot;"), reloaded)

        // a block with a configuration that its definition does not take: saved as work in progress, shown as a problem, not publishable
        val bad = graph(node(1, "reader", "acme-flow/src", config = """{"x": 1}"""))
        val problems = admin.post("/blueprints/flow-app/save", mapOf("graph" to bad, "options" to "{}", "version" to "1.3.0", "roles" to "a", "revision" to "2")).body()
        assertTrue(problems.contains("takes no configuration") && problems.contains("Saved as revision 3"), problems)
        assertTrue(admin.post("/drafts/project/flow-app/publish").body().contains("takes no configuration"))

        assertTrue(admin.post("/blueprints/flow-app/save", mapOf("graph" to connected, "options" to """{"reader.out>writer.in": {"delivery": "BUFFER", "record": true}}""", "version" to "1.3.0", "roles" to "a", "revision" to "3")).body().contains("revision 4"))
        val published = admin.post("/drafts/project/flow-app/publish").body()
        assertTrue(published.contains("Published flow-app 1.3.0"), published)
        val packages = runBlocking { core.repository().listPackages(ListPackagesRequest.getDefaultInstance()).packagesList }
        assertTrue(packages.any { it.name == "flow-app" && it.version == "1.3.0" })
        // the published project has the tether with its options
        val project = Files.createTempFile("flow", ".zip").also { f ->
            val repo = PackageRepository(dir.resolve("repo"))
            Files.copy(repo.file("flow-app", "1.3.0"), f, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        val read = cringle.packaging.PackageReader.readProject(project)
        val tether = read.blueprints.single().tethers.single()
        assertEquals("reader", tether.from!!.block)
        assertEquals(cringle.packaging.DeliveryPolicy.BUFFER, tether.delivery)
        assertTrue(tether.record != null)
        assertEquals(mapOf("acme-flow" to "^1.0.0"), read.manifest.dependencies)
    }

    @Test
    fun anImpossibleGraphIsRefusedInlineAndAViewerCannotSave() {
        val noCommonType = graph(node(1, "b", "acme-flow/bytes", outputs = mapOf("output_1" to listOf("2"))), node(2, "w", "acme-flow/sink", inputs = mapOf("input_1" to listOf("1"))))
        assertTrue(admin.post("/blueprints/flow-app/save", mapOf("graph" to noCommonType, "options" to "{}")).body().contains("share no tether type"))
        assertTrue(admin.post("/blueprints/flow-app/save", mapOf("graph" to "not json", "options" to "{}")).body().contains("not valid JSON"))
        assertEquals(200, viewer.get("/blueprints/flow-app").statusCode())
        assertFalse(viewer.get("/blueprints/flow-app").body().contains("save-blueprint"))
        assertEquals(403, viewer.post("/blueprints/flow-app/save", mapOf("graph" to "{}")).statusCode())
        assertEquals(404, admin.get("/blueprints/missing").statusCode())
    }
}
