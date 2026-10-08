// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.UserRole
import cringle.management.ServiceTestBase
import cringle.repository.v1.ListPackagesRequest
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The proof of M8: a blueprint comes into being completely in the WebUI and is deployed from there. Everything goes over HTTPS with a session, as a browser
 * would do it (the editor's own JavaScript is not part of this test; it was run in a browser, see `docs/webui.md`): a user is created, a schema is made and
 * published, a blueprint is built from the blocks of the plugin in the repository (the connection is checked by the server), published as a project, deployed
 * on a running engine, seen running, its messages arrive, logs and metrics show it, and it is undeployed.
 */
@Tag("integration")
class WebUiEndToEndTest : ServiceTestBase() {
    private fun node(number: Int, id: String, block: String, config: JsonObject, outputs: Boolean = false, inputs: Boolean = false) =
        """"$number": {"id": $number, "name": "$block", "data": {"id": "$id", "block": "$block", "config": $config}, "class": "cringle-block", "html": "", "typenode": false,
            "inputs": {${if (inputs) """"input_1": {"connections": [{"node": "1", "input": "output_1"}]}""" else ""}},
            "outputs": {${if (outputs) """"output_1": {"connections": [{"node": "2", "output": "input_1"}]}""" else ""}}, "pos_x": ${number * 200}, "pos_y": 60}"""

    @Test
    fun aBlueprintIsBuiltInTheWebUiAndDeployedFromThere() {
        val users = UserManager(FileUserStore(dir.resolve("users.json")))
        val token = users.bootstrap()!!
        val server = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        closeables += server
        val ui = WebTestClient(server.port, core.identity.publicKeyFingerprint).login(token)

        // a user for the operations team, with a token that works
        assertTrue(ui.post("/users", mapOf("name" to "ops", "role-OPERATOR" to "on")).body().contains("<strong>ops</strong>"))
        val opsToken = Regex("<code>([^<]+)</code> \\(shown once").find(ui.post("/users/${users.listUsers().first { it.user.name == "ops" }.user.id}/tokens", mapOf("label" to "e2e")).body())!!.groupValues[1]
        WebTestClient(server.port, core.identity.publicKeyFingerprint).login(opsToken)

        // a schema: draft, form, save, publish
        assertTrue(ui.post("/drafts", mapOf("kind" to "schema", "name" to "acme-web-types")).body().contains("acme-web-types"))
        val model = """{"namespace": "acme.web", "types": [{"name": "Greeting", "kind": "record", "values": "", "fields": [{"name": "text", "type": "cringle.std/String", "wrap": ""}]}]}"""
        assertTrue(ui.post("/schemas/acme-web-types/save", mapOf("model" to model, "version" to "1.0.0", "revision" to "1")).body().contains("The document is valid"))
        assertTrue(ui.post("/drafts/schema/acme-web-types/publish").body().contains("Published acme-web-types 1.0.0"))

        // a blueprint: the connection is checked by the server, the graph is saved, the project published
        assertTrue(ui.post("/drafts", mapOf("kind" to "project", "name" to "web-app")).body().contains("web-app"))
        assertTrue(ui.get("/blueprints/web-app").body().contains("acme-svc/caller"))
        val check = ui.post("/blueprints/web-app/check", mapOf("fromBlock" to "acme-svc/caller", "fromOutput" to "1", "toBlock" to "acme-svc/sink", "toInput" to "1")).body()
        assertEquals("""{"ok":true,"type":"MESSAGE"}""", check)
        val out = dir.resolve("web-out.txt")
        val graph = """{"drawflow": {"Home": {"data": {
            ${node(1, "greeter", "acme-svc/caller", JsonObject(mapOf("message" to JsonPrimitive("from-the-web-ui"))), outputs = true)},
            ${node(2, "writer", "acme-svc/sink", JsonObject(mapOf("file" to JsonPrimitive(out.toString()))), inputs = true)}}}}}"""
        val saved = ui.post("/blueprints/web-app/save", mapOf("graph" to graph, "options" to """{"greeter.out>writer.in": {"delivery": "BUFFER", "record": true}}""", "version" to "1.0.0", "roles" to "a", "revision" to "1")).body()
        assertTrue(saved.contains("Saved as revision 2") && saved.contains("The blueprint is valid"), saved)
        assertTrue(ui.post("/drafts/project/web-app/publish").body().contains("Published web-app 1.0.0"))
        val published = runBlocking { core.repository().listPackages(ListPackagesRequest.getDefaultInstance()).packagesList.map { it.name } }
        assertTrue("web-app" in published && "acme-web-types" in published, published.toString())

        // deployed from the web UI, running, and its messages arrive
        val deployed = ui.post("/deployments", mapOf("project" to "web-app", "start" to "on")).body()
        assertTrue(deployed.contains("Deployed web-app 1.0.0"), deployed)
        awaitReceived(setOf("from-the-web-ui"), out)
        assertTrue(ui.get("/fabrics/list").body().contains("web-app-web-app-1"))
        val fabric = "web-app-web-app-1"
        assertTrue(ui.get("/fabrics").body().contains("running"))

        // the data of the running fabric in the other pages
        val metrics = ui.get("/metrics/list").body()
        assertTrue(metrics.contains(fabric) && metrics.contains("e-a"), metrics)
        val logs = ui.get("/logs/list?fabric=$fabric&limit=50").body()
        assertTrue((logs.contains("<table") || logs.contains("No log lines")) && !logs.contains("class=\"error\""), logs)
        val partitions = ui.get("/dwh/list").body()
        assertTrue(partitions.contains("<td>$fabric</td><td>tether</td>"), partitions)

        // undeployed
        assertTrue(ui.post("/deployments/web-app/undeploy").body().contains("Removed"))
        assertTrue(fabrics().keys.none { it.startsWith("web-app") })
    }
}
