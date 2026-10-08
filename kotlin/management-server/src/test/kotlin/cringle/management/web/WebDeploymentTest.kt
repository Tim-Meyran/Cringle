// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.UserRole
import cringle.management.ServiceTestBase
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class WebDeploymentTest : ServiceTestBase() {
    private lateinit var admin: WebTestClient
    private lateinit var viewer: WebTestClient

    private fun web() {
        val users = UserManager(FileUserStore(dir.resolve("users.json")))
        val token = users.bootstrap()!!
        val viewerToken = users.createToken(users.createUser("vera", setOf(UserRole.VIEWER)).user.id, "web", null).secret
        val server = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        closeables += server
        val key = core.identity.publicKeyFingerprint
        admin = WebTestClient(server.port, key).login(token)
        viewer = WebTestClient(server.port, key).login(viewerToken)
    }

    private fun await(what: String, page: () -> String, condition: (String) -> Boolean): String {
        val deadline = System.nanoTime() + 60_000_000_000L
        while (true) {
            val body = page()
            if (condition(body)) return body
            check(System.nanoTime() < deadline) { "$what did not happen:\n$body\n" + diagnostics() }
            Thread.sleep(200)
        }
    }

    @Test
    fun deployAndUndeployAndBindThroughThePages() {
        web()
        val deployed = admin.post("/deployments", mapOf("project" to "orders-service", "version" to "", "start" to "on")).body()
        assertTrue(deployed.contains("Deployed orders-service 1.0.0") && deployed.contains("orders-service-service-1"), deployed)
        assertTrue(admin.get("/deployments").body().contains("orders-service-service-1"))

        val bound = admin.post("/bindings", mapOf("project" to "shop", "service" to "orders", "fabrics" to "orders-service-service-1")).body()
        assertTrue(bound.contains("<td>shop</td><td>orders</td>"), bound)
        assertTrue(admin.post("/deployments", mapOf("project" to "shop", "start" to "on")).body().contains("Deployed shop"))
        awaitReceived(setOf("from-shop"))

        assertFalse(admin.post("/bindings/shop/orders/unbind").body().contains("<td>shop</td><td>orders</td>"))
        val removed = admin.post("/deployments/shop/undeploy").body()
        assertTrue(removed.contains("Removed"), removed)
        assertEquals(setOf("orders-service-service-1"), fabrics().keys)
    }

    @Test
    fun errorsAreShownInlineAndAViewerCannotPost() {
        web()
        val refused = admin.post("/deployments", mapOf("project" to "shop", "start" to "on")).body()
        assertTrue(refused.contains("class=\"error\"") && refused.contains("FAILED_PRECONDITION"), refused)
        assertTrue(admin.post("/bindings", mapOf("project" to "shop", "service" to "orders", "fabrics" to "ghost")).body().contains("NOT_FOUND"))
        assertEquals(403, viewer.post("/deployments", mapOf("project" to "shop")).statusCode())
        assertEquals(403, viewer.post("/dwh/retention", mapOf("fabric" to "x")).statusCode())
        val page = viewer.get("/deployments").body()
        assertFalse(page.contains("hx-post=\"/deployments") || page.contains("hx-post=\"/bindings"), page)
    }

    @Test
    fun logsMetricsAndWarehouseShowTheDataOfARunningFabric() {
        web()
        assertTrue(admin.post("/deployments", mapOf("project" to "recorded-app", "start" to "on")).body().contains("Deployed recorded-app"))
        val fabric = "recorded-app-app-1"

        // the recorded tether appears as a partition and its records can be read
        val partitions = await("partition of the recorded tether", { admin.get("/dwh/list").body() }) { it.contains("<td>$fabric</td><td>tether</td>") }
        val name = Regex("<td>$fabric</td><td>tether</td><td>([^<]+)</td>").find(partitions)!!.groupValues[1].replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&")
        val records = await("records", { admin.get("/dwh/records?fabric=$fabric&kind=tether&name=" + java.net.URLEncoder.encode(name, Charsets.UTF_8)).body() }) { it.contains("ping-c") }
        assertTrue(records.contains("<code>"), records)
        val retention = admin.post("/dwh/retention", mapOf("fabric" to fabric, "kind" to "tether", "name" to name, "maxAgeHours" to "2", "maxBytes" to "5000")).body()
        assertTrue(retention.contains("2.0 h") && retention.contains("5000 bytes"), retention)
        assertTrue(admin.post("/dwh/retention", mapOf("fabric" to fabric, "kind" to "tether", "name" to name, "maxAgeHours" to "x")).body().contains("INVALID_ARGUMENT"))
        assertTrue(admin.post("/dwh/recording", mapOf("fabric" to fabric, "mode" to "on")).statusCode() == 200)

        val metrics = await("metrics of the tether", { admin.get("/metrics/list").body() }) { it.contains("tether") && it.contains("messages") }
        assertTrue(metrics.contains("e-a") && metrics.contains(fabric), metrics)

        val logs = admin.get("/logs/list?fabric=$fabric&level=INFO&limit=50").body()
        assertTrue(logs.contains("<table") && !logs.contains("class=\"error\""), logs)
        assertTrue(admin.get("/logs").body().contains("x-data"))
        assertTrue(admin.get("/logs/list?machine=m1&engine=unknown").body().contains("class=\"error\""))
    }
}
