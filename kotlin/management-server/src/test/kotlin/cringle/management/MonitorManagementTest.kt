// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.management.v1.GetFabricGraphRequest
import cringle.management.v1.GetTrafficRequest
import cringle.management.web.WebServer
import cringle.management.web.WebTestClient
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The monitor (#399) through the ManagementServer: the graph of a running fabric, the last values of a tether, and the pages that show them. */
@Tag("integration")
class MonitorManagementTest : ServiceTestBase() {
    private val fabric = "recorded-app-app-1"
    private val tether = "c2.out -> s2.in"

    private fun graph() = runBlocking { s.getFabricGraph(GetFabricGraphRequest.newBuilder().setFabric(fabric).build()) }

    private fun traffic(name: String, limit: Int = 10) = runBlocking { s.getTraffic(GetTrafficRequest.newBuilder().setFabric(fabric).setTether(name).setLimit(limit).build()) }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 60_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out: $what\n" + diagnostics() }
            Thread.sleep(100)
        }
    }

    @Test
    fun theGraphShowsBlocksTethersAndTheirNumbers() {
        deploy("recorded-app")
        await("values on the tether") { graph().tethersList.any { it.id == tether && it.messages > 0 } }
        val g = graph()
        assertEquals(fabric, g.fabricId)
        assertTrue(g.blocksList.map { it.id }.containsAll(listOf("c2", "s2")), g.toString())
        val t = g.tethersList.first { it.id == tether }
        assertEquals("c2", t.fromBlock)
        assertEquals("s2", t.toBlock)
        assertTrue(t.capturable && t.lastActivityEpochMillis > 0)
        assertTrue(g.blocksList.first { it.id == "c2" }.portsList.any { it.name == "out" && it.out })
    }

    @Test
    fun theLastValuesAreCapturedOnceSomebodyLooks() {
        deploy("recorded-app")
        graph() // the first read starts the capture
        await("a captured value") { traffic(tether).recordsCount > 0 }
        val answer = traffic(tether, 3)
        assertTrue(answer.capturable && answer.recordsCount in 1..3)
        assertTrue(answer.recordsList.zipWithNext().all { (a, b) -> a.seq > b.seq }, "newest first")
        assertTrue(answer.recordsList.all { it.payload.isNotEmpty() && it.kind.isNotEmpty() })
    }

    @Test
    fun anUnknownTetherAndAnUnknownFabricAreRefused() {
        deploy("recorded-app")
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows<StatusException> { traffic("x.out -> y.in") }.status.code)
        val unknown = assertThrows<StatusException> { runBlocking { s.getFabricGraph(GetFabricGraphRequest.newBuilder().setFabric("nope").build()) } }
        assertTrue(unknown.status.code == Status.Code.NOT_FOUND || unknown.status.code == Status.Code.INVALID_ARGUMENT, unknown.status.toString())
    }

    @Test
    fun thePagesShowTheGraphTheBlockAndTheValuesOfATether() {
        deploy("recorded-app")
        val users = UserManager(FileUserStore(dir.resolve("web-users.json")))
        val token = users.bootstrap()!!
        val web = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        try {
            val client = WebTestClient(web.port, tls.identity.publicKeyFingerprint).login(token)
            assertTrue(client.get("/monitor").body().contains("/monitor/$fabric"))
            val page = client.get("/monitor/$fabric").body()
            assertTrue(page.contains("id=\"monitor\"") && page.contains("/static/monitor.js"), page)
            val json = kotlinx.serialization.json.Json.parseToJsonElement(client.get("/monitor/$fabric/graph").body()) as kotlinx.serialization.json.JsonObject
            assertTrue(json.getValue("blocks").toString().contains("\"c2\"") && json.getValue("tethers").toString().contains(tether))
            val block = client.get("/monitor/$fabric/block?id=c2").body()
            assertTrue(block.contains("Received") && block.contains("Break on inputs") || block.contains("Sent"), block)
            val port = client.get("/monitor/$fabric/port?block=c2&port=out").body()
            assertTrue(port.contains("data-tether=\"c2.out -&gt; s2.in\""), port)
            await("a value in the table") { client.get("/monitor/$fabric/tether?id=" + java.net.URLEncoder.encode(tether, "UTF-8")).body().contains("class=\"payload\"") }

            // a breakpoint on the block holds the values of its inputs; removing it lets them go
            val set = client.post("/monitor/$fabric/breakpoint", mapOf("block" to "s2", "mode" to "on")).body()
            assertTrue(set.contains("Breakpoint on 1 tether(s) set"), set)
            await("a held value") { client.get("/monitor/$fabric/tether?id=" + java.net.URLEncoder.encode(tether, "UTF-8")).body().contains("Held back") }
            val resume = client.post("/monitor/$fabric/resume", mapOf("tether" to tether)).body()
            assertTrue(resume.contains("Released"), resume)
            assertTrue(client.post("/monitor/$fabric/breakpoint", mapOf("block" to "s2", "mode" to "off")).body().contains("removed"))
            assertTrue(client.post("/monitor/$fabric/breakpoint", mapOf("block" to "nope", "mode" to "on")).body().contains("class=\"notice error\""))
        } finally {
            web.close()
        }
    }
}
