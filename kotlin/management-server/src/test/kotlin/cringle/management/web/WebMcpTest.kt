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
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import cringle.testkit.TestPluginBuilder
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Tag("integration")
class WebMcpTest {
    @TempDir
    lateinit var dir: Path

    private val closeables = ArrayList<AutoCloseable>()
    private lateinit var admin: WebTestClient
    private lateinit var readOnly: WebTestClient
    private lateinit var off: WebTestClient
    private lateinit var adminToken: String
    private lateinit var viewerToken: String
    private var id = 0

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
        adminToken = users.bootstrap()!!
        viewerToken = users.createToken(users.createUser("vera", setOf(UserRole.VIEWER)).user.id, "mcp", null).secret
        val tls = ManagementTls(dir.resolve("tls"))
        val repository = tls.startRepository(repo)
        closeables += AutoCloseable { repository.stop() }
        val core = tls.core(ManagementStore(dir.resolve("state.json")), "127.0.0.1:${repository.port}")
        closeables += core
        fun server(mode: McpMode): WebTestClient {
            val s = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO, mcp = mode).start()
            closeables += s
            return WebTestClient(s.port, tls.identity.publicKeyFingerprint)
        }
        admin = server(McpMode.ON)
        readOnly = server(McpMode.READ_ONLY)
        off = server(McpMode.OFF)
    }

    @AfterEach
    fun stop() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    private fun rpc(client: WebTestClient, method: String, params: String = "{}", token: String? = adminToken): JsonObject =
        Json.parseToJsonElement(client.postJson("/mcp", """{"jsonrpc":"2.0","id":${++id},"method":"$method","params":$params}""", token).body()).jsonObject

    private fun call(client: WebTestClient, tool: String, arguments: String = "{}", token: String? = adminToken): Pair<Boolean, String> {
        val result = rpc(client, "tools/call", """{"name":"$tool","arguments":$arguments}""", token)["result"]!!.jsonObject
        return result["isError"]!!.jsonPrimitive.boolean to result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    }

    private fun names(response: JsonObject) = response["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    private fun code(response: JsonObject) = response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt()

    @Test
    fun theEndpointNeedsAValidTokenAndRefusesBrowsers() {
        val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        assertEquals(401, admin.postJson("/mcp", ping).statusCode())
        assertEquals(401, admin.postJson("/mcp", ping, "crt_nonsense").statusCode())
        assertEquals(200, admin.postJson("/mcp", ping, viewerToken).statusCode())
        assertEquals(403, admin.postJson("/mcp", ping, adminToken, mapOf("Origin" to "https://evil.example")).statusCode(), "a page of the browser may not send it")
        assertEquals(415, admin.postJson("/mcp", ping, adminToken, contentType = "text/plain").statusCode(), "text/plain would need no preflight")
        assertEquals(405, admin.get("/mcp").statusCode())
        assertEquals(404, off.postJson("/mcp", ping, adminToken).statusCode(), "--mcp off has no endpoint")
    }

    @Test
    fun theProtocolIsAnswered() {
        val init = rpc(admin, "initialize", """{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}""")["result"]!!.jsonObject
        assertEquals("2025-03-26", init["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("cringle-management-server", init["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("2025-03-26", rpc(admin, "initialize", """{"protocolVersion":"1999-01-01"}""")["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content, "an unknown revision gets the newest one")
        assertEquals(202, admin.postJson("/mcp", """{"jsonrpc":"2.0","method":"notifications/initialized"}""", adminToken).statusCode())
        assertEquals(-32601, code(rpc(admin, "nothing/here")))
        assertEquals(-32700, code(Json.parseToJsonElement(admin.postJson("/mcp", "{nope", adminToken).body()).jsonObject))
        val batch = Json.parseToJsonElement(
            admin.postJson("/mcp", """[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/initialized"},{"jsonrpc":"2.0","id":2,"method":"ping"}]""", adminToken).body(),
        )
        assertEquals(2, (batch as JsonArray).size)
        assertEquals(-32602, code(rpc(admin, "tools/call", """{"name":"nothing"}""")))
    }

    @Test
    fun theToolsFollowTheModeAndTheRightsOfTheUser() {
        val all = names(rpc(admin, "tools/list"))
        assertTrue(all.containsAll(listOf("list_fabrics", "query_logs", "deploy", "undeploy", "save_blueprint", "publish_project", "add_machine", "set_config")), all.toString())
        val viewer = names(rpc(admin, "tools/list", token = viewerToken))
        assertTrue("query_logs" in viewer && "deploy" !in viewer && "save_blueprint" !in viewer && "set_config" !in viewer, viewer.toString())
        val ro = names(rpc(readOnly, "tools/list"))
        assertTrue("query_logs" in ro && ro.none { it in setOf("deploy", "undeploy", "start_fabric", "save_blueprint", "add_machine") }, ro.toString())
        assertEquals(-32602, code(rpc(readOnly, "tools/call", """{"name":"deploy","arguments":{"project":"x"}}""")), "a write tool is unknown in read-only mode")
        val (denied, why) = call(admin, "deploy", """{"project":"x"}""", viewerToken)
        assertTrue(denied && why.contains("PERMISSION_DENIED"), why)
        assertFalse(call(admin, "list_projects", token = viewerToken).first, "a viewer reads")
    }

    @Test
    fun aProjectIsBuiltCheckedAndPublishedThroughTheTools() {
        val (whoError, who) = call(admin, "whoami")
        assertFalse(whoError)
        assertTrue(who.contains("\"operate\":true"), who)
        assertTrue(call(admin, "list_blocks").second.contains("acme-flow/sink"))
        assertFalse(call(admin, "create_project", """{"name":"shop"}""").first)
        assertTrue(call(admin, "create_project", """{"name":"shop"}""").first, "a name is used once")

        val blueprint = """{"blocks":[{"id":"a","block":"acme-flow/src"},{"id":"b","block":"acme-flow/sink"}],"tethers":[{"type":"MESSAGE","from":{"block":"a","port":"out"},"to":{"block":"b","port":"in"}}]}"""
        val (saveError, saved) = call(admin, "save_blueprint", """{"project":"shop","blueprint":$blueprint,"version":"1.2.0"}""")
        assertFalse(saveError, saved)
        assertTrue(saved.contains("\"valid\":true") && saved.contains("\"version\":\"1.2.0\""), saved)
        assertTrue(call(admin, "get_blueprint", """{"project":"shop"}""").second.contains("acme-flow/sink"))
        assertTrue(call(admin, "list_projects").second.contains("\"blocks\":2"))
        assertEquals("[]", call(admin, "check_blueprint", """{"project":"shop"}""").second)

        // a wrong blueprint is stored as work in progress and its problems are returned
        val broken = """{"blocks":[{"id":"a","block":"acme-flow/nothing"}],"tethers":[]}"""
        val (_, problems) = call(admin, "save_blueprint", """{"project":"shop","blueprint":$broken}""")
        assertTrue(problems.contains("\"valid\":false") && problems.contains("unknown block"), problems)
        assertTrue(call(admin, "save_blueprint", """{"project":"shop","blueprint":{"blocks":3}}""").first, "a blueprint of the wrong shape is an error")
        assertTrue(call(admin, "save_blueprint", """{"project":"nothing","blueprint":$blueprint}""").first, "an unknown project")

        assertFalse(call(admin, "save_blueprint", """{"project":"shop","blueprint":$blueprint}""").first)
        val (publishError, published) = call(admin, "publish_project", """{"project":"shop"}""")
        assertFalse(publishError, published)
        assertTrue(published.contains("Published shop 1.2.0"), published)
        assertTrue(call(admin, "list_packages", """{"kind":"project"}""").second.contains("\"name\":\"shop\""))
        // a viewer cannot change it
        assertTrue(call(admin, "save_blueprint", """{"project":"shop","blueprint":$blueprint}""", viewerToken).first)
    }

    @Test
    fun theStateOfTheInstallationIsReadThroughTheTools() {
        for (tool in listOf("list_machines", "list_engines", "list_fabrics", "list_deployments", "list_bindings", "list_packages", "list_projects", "dwh_list", "get_metrics", "list_trust")) {
            val (error, text) = call(admin, tool)
            assertFalse(error, "$tool: $text")
            Json.parseToJsonElement(text)
        }
        val (error, text) = call(admin, "query_logs", """{"limit":5,"level":"warn"}""")
        assertFalse(error, text)
        assertTrue(text.contains("\"entries\":[]"), text)
        assertTrue(call(admin, "query_logs", """{"level":"loud"}""").first, "a wrong level is an error")
        assertTrue(call(admin, "get_fabric", "{}").first, "required arguments are checked")
    }
}
