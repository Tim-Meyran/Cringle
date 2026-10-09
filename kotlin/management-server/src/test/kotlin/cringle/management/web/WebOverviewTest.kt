// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.UserRole
import cringle.management.ServiceTestBase
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class WebOverviewTest : ServiceTestBase() {
    private fun web(): Pair<WebTestClient, WebTestClient> {
        val users = UserManager(FileUserStore(dir.resolve("users.json")))
        val admin = users.bootstrap()!!
        val viewer = users.createToken(users.createUser("vera", setOf(UserRole.VIEWER)).user.id, "web", null).secret
        val web = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        closeables += web
        val key = core.identity.publicKeyFingerprint
        return WebTestClient(web.port, key).login(admin) to WebTestClient(web.port, key).login(viewer)
    }

    @Test
    fun listsShowMachineEngineAndFabricAndActionsChangeTheState() {
        val (admin, _) = web()
        deploy("orders-service")

        val machines = admin.get("/machines/list").body()
        assertTrue(machines.contains("m1") && machines.contains("reachable"), machines)
        val engines = admin.get("/engines").body()
        assertTrue(engines.contains("e-svc") && engines.contains("e-a"), engines)
        assertTrue(admin.get("/fabrics/list").body().contains("orders-service-service-1"))

        val stopped = admin.post("/engines/m1/e-b/stop").body()
        assertTrue(Regex("e-b</strong></td><td><span class=\"badge neutral\">stopped").containsMatchIn(stopped), stopped)
        val started = admin.post("/engines/m1/e-b/start").body()
        assertTrue(Regex("e-b</strong></td><td><span class=\"badge ok\">running").containsMatchIn(started), started)

        val fabric = "/fabrics/m1/e-svc/orders-service-service-1"
        assertTrue(Regex("badge neutral\">stopped</span></td><td><span class=\"badge neutral\">stopped").containsMatchIn(admin.post("$fabric/stop").body()))
        assertTrue(Regex("badge ok\">running</span></td><td><span class=\"badge ok\">running").containsMatchIn(admin.post("$fabric/start").body()))
        assertTrue(admin.get(fabric).body().contains("s1"))

        assertTrue(admin.post("$fabric/remove").body().let { !it.contains("orders-service-service-1") })
        assertEquals(emptySet<String>(), fabrics().keys)
    }

    @Test
    fun machinesCanBeAddedAndRemoved() {
        val (admin, _) = web()
        assertTrue(admin.post("/machines", mapOf("id" to "m2", "address" to "127.0.0.1:1")).body().contains("m2"))
        assertTrue(admin.post("/machines", mapOf("id" to "m2", "address" to "127.0.0.1:1")).body().contains("ALREADY_EXISTS"))
        assertFalse(admin.post("/machines/m2/remove").body().contains("m2"))
    }

    @Test
    fun tagsAreEditedAndErrorsAreShownInline() {
        val (admin, _) = web()
        val tagged = admin.post("/engines/m1/e-a/tags", mapOf("roles" to "a, extra", "labels" to "zone=north")).body()
        assertTrue(tagged.contains("zone=north") && tagged.contains("a, extra"), tagged)
        val bad = admin.post("/engines/m1/e-a/tags", mapOf("roles" to "a", "labels" to "broken")).body()
        assertTrue(bad.contains("class=\"notice error\"") && bad.contains("INVALID_ARGUMENT"), bad)
        val unknown = admin.post("/engines/m1/nope/stop").body()
        assertTrue(unknown.contains("class=\"notice error\""), unknown)
        runBlocking { assertTrue(core.listEngines("m1").any { it.process.engineId.value == "e-a" }) }
    }

    @Test
    fun aViewerSeesNoActionButtonsAndCannotPost() {
        val (_, viewer) = web()
        val page = viewer.get("/engines").body()
        assertTrue(page.contains("e-a"))
        assertFalse(page.contains("hx-post=\"/engines"), page)
        assertEquals(403, viewer.post("/engines/m1/e-a/stop").statusCode())
        assertEquals(403, viewer.post("/machines", mapOf("id" to "m3", "address" to "127.0.0.1:1")).statusCode())
        assertEquals(403, viewer.post("/fabrics/m1/e-a/x/stop").statusCode())
        assertTrue(viewer.get("/machines").body().let { it.contains("m1") && !it.contains("<form") })
    }
}
