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
import kotlinx.serialization.json.jsonObject
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
            .block(block("fan", PortDefinition("in", PortDirection.IN, setOf(TetherType.MESSAGE), string, varArg = true)))
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
        assertTrue(admin.post("/drafts", mapOf("kind" to "project", "name" to "flow-app")).body().contains("<strong>flow-app</strong>"))
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
    fun anEmptyBlueprintIsNotReportedAsValid() {
        val saved = admin.post("/blueprints/flow-app/save", mapOf("graph" to graph(), "options" to "{}", "version" to "1.0.0", "roles" to "", "revision" to "1")).body()
        assertTrue(saved.contains("Saved as revision 2") && saved.contains("has no block yet"), saved)
        assertFalse(saved.contains("The blueprint is valid"), saved)
    }

    @Test
    fun thePaletteIsGroupedByPlugin() {
        val page = admin.get("/blueprints/flow-app").body()
        assertTrue(page.contains("<h3>acme-flow</h3>") && page.contains("id=\"palette-search\"") && page.contains("id=\"canvas-hint\"") && page.contains("id=\"editor-bar\""), page)
    }

    @Test
    fun thePaletteSaysSoWhenThereIsNoPlugin() {
        val repo = PackageRepository(dir.resolve("empty-repo"))
        val users = UserManager(FileUserStore(dir.resolve("empty-users.json")))
        val token = users.bootstrap()!!
        val tls = ManagementTls(dir.resolve("empty-tls"))
        val repository = tls.startRepository(repo)
        closeables += AutoCloseable { repository.stop() }
        val empty = tls.core(ManagementStore(dir.resolve("empty-state.json")), "127.0.0.1:${repository.port}")
        closeables += empty
        val server = WebServer(empty, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        closeables += server
        val client = WebTestClient(server.port, tls.identity.publicKeyFingerprint).login(token)
        client.post("/drafts", mapOf("kind" to "project", "name" to "none"))
        val page = client.get("/blueprints/none").body()
        assertTrue(page.contains("No plugin with blocks is in the repository yet") && page.contains("href=\"/packages\"") && !page.contains("palette-search"), page)
    }

    @Test
    fun retryOnADropTetherIsListedAsAProblem() {
        val connected = graph(node(1, "reader", "acme-flow/src", outputs = mapOf("output_1" to listOf("2"))), node(2, "writer", "acme-flow/sink", inputs = mapOf("input_1" to listOf("1"))))
        val options = """{"reader.out>writer.in": {"delivery": "DROP", "record": false, "retry": {"maxAttempts": 3}}}"""
        val saved = admin.post("/blueprints/flow-app/save", mapOf("graph" to connected, "options" to options, "version" to "1.0.0", "roles" to "", "revision" to "1")).body()
        assertTrue(saved.contains("Saved as revision 2") && saved.contains("has problems") && saved.contains("retry"), saved)
    }

    @Test
    fun theEditorHasTheOperationButtons() {
        val page = admin.get("/blueprints/flow-app").body()
        for (id in listOf("undo", "redo", "zoom-in", "zoom-out", "zoom-fit", "autosave", "publish-blueprint", "data-publish-url")) assertTrue(page.contains(id), id)
        val readOnly = viewer.get("/blueprints/flow-app").body()
        assertTrue(readOnly.contains("id=\"undo\"") && !readOnly.contains("publish-blueprint") && !readOnly.contains("id=\"autosave\""), "a viewer cannot save or publish")
    }

    @Test
    fun publishFromTheEditorAnswersWithAResultAndNeedsOperate() {
        val connected = graph(node(1, "reader", "acme-flow/src", outputs = mapOf("output_1" to listOf("2"))), node(2, "writer", "acme-flow/sink", inputs = mapOf("input_1" to listOf("1"))))
        assertTrue(admin.post("/blueprints/flow-app/save", mapOf("graph" to connected, "options" to "{}", "version" to "1.0.0", "roles" to "", "revision" to "1")).body().contains("The blueprint is valid"))
        val done = admin.post("/blueprints/flow-app/publish").body()
        assertTrue(done.contains("Published flow-app 1.0.0") && done.contains("/deployments") && !done.contains("<table"), done)
        assertEquals(403, viewer.post("/blueprints/flow-app/publish").statusCode())
    }

    @Test
    fun aVarArgSlotCanBeCheckedAndAnOutOfRangeSlotIsRefused() {
        fun check(slot: Int, counts: String) = admin.post(
            "/blueprints/flow-app/check",
            mapOf("fromBlock" to "acme-flow/src", "fromOutput" to "1", "toBlock" to "acme-flow/fan", "toInput" to "$slot", "toCounts" to counts),
        ).body()
        assertEquals("""{"ok":true,"type":"MESSAGE"}""", check(2, """{"in":2}"""))
        assertTrue(check(3, """{"in":2}""").contains("has no such input"))
        assertEquals("""{"ok":true,"type":"MESSAGE"}""", check(1, "{}"), "a VarArg port without a count has one slot")
        val page = admin.get("/blueprints/flow-app").body()
        assertTrue(page.contains("acme-flow/fan") && page.contains("varArg"), page)
    }

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
    fun savingInTheEditorKeepsTheAssertionsOfTheDraft() {
        val connected = graph(node(1, "reader", "acme-flow/src", outputs = mapOf("output_1" to listOf("2"))), node(2, "writer", "acme-flow/sink", inputs = mapOf("input_1" to listOf("1"))))
        assertTrue(admin.post("/blueprints/flow-app/save", mapOf("graph" to connected, "options" to "{}", "version" to "1.0.0", "roles" to "a", "revision" to "1")).body().contains("revision 2"))
        // the editor cannot edit assertions: put some into the draft the way a file would carry them
        val drafts = DraftStore(core.dataDirectory.resolve("drafts"))
        val draft = drafts.load("project", "flow-app")!!
        val content = draft.content.jsonObject
        val blueprint = kotlinx.serialization.json.JsonObject(
            content.getValue("blueprint").jsonObject + ("assertions" to kotlinx.serialization.json.Json.parseToJsonElement("""[{"type":"fabric-running"},{"type":"block-running","block":"reader"}]""")),
        )
        drafts.save("project", "flow-app", draft.version, kotlinx.serialization.json.JsonObject(content + ("blueprint" to blueprint)), draft.revision)
        // saved again from the graph: the assertions stay
        assertTrue(admin.post("/blueprints/flow-app/save", mapOf("graph" to connected, "options" to "{}", "version" to "1.0.0", "roles" to "a", "revision" to (draft.revision + 1).toString())).body().contains("The blueprint is valid"))
        val after = cringle.packaging.ManifestJson.parseBlueprint(drafts.load("project", "flow-app")!!.content.jsonObject.getValue("blueprint").toString(), "draft")
        assertEquals(listOf(cringle.packaging.FabricRunning(), cringle.packaging.BlockRunning("reader")), after.assertions)
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
