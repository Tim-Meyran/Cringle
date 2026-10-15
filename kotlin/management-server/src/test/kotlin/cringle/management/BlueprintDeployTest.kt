// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.contract.UserRole
import cringle.management.web.WebServer
import cringle.management.web.WebTestClient
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The develop loop (#405): a project draft is published and deployed in one step, and deployed again with a new version. */
@Tag("integration")
class BlueprintDeployTest : ServiceTestBase() {
    private var web: WebServer? = null

    @AfterEach
    fun stopWeb() {
        web?.close()
    }

    private fun node(number: Int, id: String, block: String, config: String, outputs: Map<String, List<String>> = emptyMap(), inputs: Map<String, List<String>> = emptyMap()) =
        """"$number": {"id": $number, "name": "$block", "data": {"id": "$id", "block": "$block", "config": $config}, "class": "cringle-block", "html": "", "typenode": false,
            "inputs": {${inputs.entries.joinToString(",") { (k, v) -> "\"$k\": {\"connections\": [${v.joinToString(",") { """{"node": "$it", "input": "output_1"}""" }}]}" }}},
            "outputs": {${outputs.entries.joinToString(",") { (k, v) -> "\"$k\": {\"connections\": [${v.joinToString(",") { """{"node": "$it", "output": "input_1"}""" }}]}" }}},
            "pos_x": ${number * 100}, "pos_y": 40}"""

    private fun graph(file: String) = """{"drawflow": {"Home": {"data": {${
        listOf(
            node(1, "c", "acme-svc/caller", """{"message":"dev"}""", outputs = mapOf("output_1" to listOf("2"))),
            node(2, "s", "acme-svc/sink", """{"file":"${file.replace("\\", "\\\\")}"}""", inputs = mapOf("input_1" to listOf("1"))),
        ).joinToString(",")
    }}}}}"""

    @Test
    fun aDraftIsPublishedAndDeployedAndAgainWithTheNextVersion() {
        val users = UserManager(FileUserStore(dir.resolve("web-users.json")))
        val token = users.bootstrap()!!
        val viewerToken = users.createToken(users.createUser("vera", setOf(UserRole.VIEWER)).user.id, "web", null).secret
        val server = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        web = server
        val admin = WebTestClient(server.port, tls.identity.publicKeyFingerprint).login(token)
        val viewer = WebTestClient(server.port, tls.identity.publicKeyFingerprint).login(viewerToken)

        assertTrue(admin.post("/drafts", mapOf("kind" to "project", "name" to "dev-app")).body().contains("<strong>dev-app</strong>"))
        val saved = admin.post("/blueprints/dev-app/save", mapOf("graph" to graph(dir.resolve("dev.txt").toString()), "options" to "{}", "version" to "1.0.0", "roles" to "a", "revision" to "1")).body()
        assertTrue(saved.contains("The blueprint is valid"), saved)

        val first = admin.post("/blueprints/dev-app/deploy").body()
        assertTrue(first.contains("Deployed dev-app 1.0.0") && first.contains("/monitor/"), first)
        assertEquals(setOf("1.0.0"), runBlocking { core.deployedFabrics() }.filter { it.project == "dev-app" }.map { it.version }.toSet())

        val second = admin.post("/blueprints/dev-app/deploy").body()
        assertTrue(second.contains("Deployed dev-app 1.0.1") && second.contains("id=\"version\" value=\"1.0.1\"") && second.contains("id=\"revision\""), second)
        assertTrue(runBlocking { core.deployedFabrics() }.filter { it.project == "dev-app" }.any { it.version == "1.0.1" })
        assertTrue(admin.get("/blueprints/dev-app").body().contains("value=\"1.0.1\""), "the draft keeps the version")

        assertTrue(admin.post("/drafts", mapOf("kind" to "project", "name" to "empty-app")).body().contains("empty-app"))
        val empty = admin.post("/blueprints/empty-app/deploy").body()
        assertTrue(empty.contains("class=\"error\"") && empty.contains("no block"), empty)
        assertTrue(runBlocking { core.deployedFabrics() }.none { it.project == "empty-app" })

        assertEquals(403, viewer.post("/blueprints/dev-app/deploy").statusCode())
        assertTrue(admin.post("/drafts/project/dev-app/deploy").body().contains("Deployed dev-app 1.0.2"))
        assertTrue(admin.get("/blueprints/dev-app").body().contains("id=\"deploy-blueprint\""))
        assertTrue(viewer.get("/blueprints/dev-app").body().contains("deploy-blueprint").not())
    }
}
