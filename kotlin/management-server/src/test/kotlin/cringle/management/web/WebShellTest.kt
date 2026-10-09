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
import org.junit.jupiter.api.assertThrows

@Tag("integration")
class WebShellTest : ServiceTestBase() {
    private lateinit var admin: WebTestClient
    private lateinit var viewer: WebTestClient
    private lateinit var anonymous: WebTestClient

    private fun web() {
        val users = UserManager(FileUserStore(dir.resolve("users.json")))
        val token = users.bootstrap()!!
        val viewerToken = users.createToken(users.createUser("vera", setOf(UserRole.VIEWER)).user.id, "web", null).secret
        val server = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        closeables += server
        val key = core.identity.publicKeyFingerprint
        admin = WebTestClient(server.port, key).login(token)
        viewer = WebTestClient(server.port, key).login(viewerToken)
        anonymous = WebTestClient(server.port, key)
    }

    private fun groupsOf(page: String): List<String> = Regex("""<p class="nav-title">([^<]+)</p>""").findAll(page).map { it.groupValues[1] }.toList()

    private fun entriesOf(page: String, group: String): List<String> {
        val block = page.substringAfter("<p class=\"nav-title\">$group</p>").substringBefore("</div>")
        return Regex("""<a href="[^"]+"[^>]*>([^<]+)</a>""").findAll(block).map { it.groupValues[1] }.toList()
    }

    @Test
    fun theSidebarGroupsTheEntriesAndMarksTheCurrentPage() {
        web()
        val page = admin.get("/machines").body()
        assertEquals(listOf("Overview", "Operate", "Observe", "Build", "Administer"), groupsOf(page))
        assertEquals(listOf("Dashboard"), entriesOf(page, "Overview"))
        assertEquals(listOf("Machines", "Engines", "Fabrics", "Deployments"), entriesOf(page, "Operate"))
        assertEquals(listOf("Logs", "Metrics", "Data warehouse"), entriesOf(page, "Observe"))
        assertEquals(listOf("Drafts"), entriesOf(page, "Build"))
        assertEquals(setOf("Users", "Groups", "Invites", "Trust", "Packages"), entriesOf(page, "Administer").toSet())
        assertEquals(1, Regex("aria-current=\"page\"").findAll(page).count())
        assertTrue(page.contains("<a href=\"/machines\" aria-current=\"page\">Machines</a>"), page)
        // the editors belong to the drafts, a detail page to its list
        assertTrue(admin.get("/fabrics").body().contains("<a href=\"/fabrics\" aria-current=\"page\">Fabrics</a>"))
        assertTrue(admin.get("/").body().contains("<a href=\"/\" aria-current=\"page\">Dashboard</a>"))
        assertTrue(page.contains("administrator") && page.contains("Sign out"))
    }

    @Test
    fun aViewerSeesOnlyWhatHeMayOpen() {
        web()
        val page = viewer.get("/").body()
        assertEquals(setOf("Trust", "Packages"), entriesOf(page, "Administer").toSet(), "no user management for a viewer")
        assertTrue(page.contains("viewer"))
        val forbidden = viewer.get("/users")
        assertEquals(403, forbidden.statusCode())
        assertTrue(forbidden.body().contains("Forbidden") && forbidden.body().contains("class=\"sidebar\""), "the refusal is a page in the shell")
    }

    @Test
    fun theSignInPageHasNoNavigationAndAMissingPageIsAPage() {
        web()
        val login = anonymous.get("/login").body()
        assertFalse(login.contains("class=\"sidebar\"") || login.contains("nav-title"), login)
        assertTrue(login.contains("Sign in") && login.contains(core.identity.publicKeyFingerprint))
        val missing = admin.get("/does-not-exist")
        assertEquals(404, missing.statusCode())
        assertTrue(missing.body().contains("Not found") && missing.body().contains("class=\"sidebar\""), missing.body())
        val withoutSession = anonymous.get("/does-not-exist")
        assertEquals(404, withoutSession.statusCode())
        assertTrue(withoutSession.body().contains("Not found") && !withoutSession.body().contains("class=\"sidebar\""))
        assertEquals(200, anonymous.get("/static/favicon.svg").statusCode())
        assertTrue(anonymous.get("/login").body().contains("/static/favicon.svg"))
    }

    @Test
    fun theDashboardCountsTheInstallationAndSaysWhatNeedsAttention() {
        web()
        deploy("orders-service")
        var page = admin.get("/").body()
        assertTrue(Regex("""Machines</span><span class="card-value">1</span><span class="card-detail">1 reachable""").containsMatchIn(page), page)
        assertTrue(Regex("""Engines</span><span class="card-value">5</span><span class="card-detail">5 running""").containsMatchIn(page), page)
        assertTrue(Regex("""Fabrics</span><span class="card-value">1</span><span class="card-detail">1 running""").containsMatchIn(page), page)
        assertTrue(page.contains("Everything runs as it should."), page)
        assertTrue(page.contains("<td>m1</td>") && page.contains("badge ok"), page)

        // an engine that should run (autostart) and does not
        runBlocking {
            core.createEngine("m1", "e-auto", null, true)
            core.startEngine("m1", "e-auto")
            core.stopEngine("m1", "e-auto")
        }
        page = admin.get("/").body()
        assertFalse(page.contains("Everything runs as it should."), page)
        assertTrue(page.contains("<strong>e-auto</strong>") && page.contains("href=\"/engines\""), page)
    }

    @Test
    fun aFailedMigrationNeedsAttentionAndTheFabricCanBeRetried() {
        web()
        runBlocking { core.deploy("mig-app", "1.0.0", true, false, true) }
        assertThrows<cringle.management.MigrationFailedException> { runBlocking { core.deploy("mig-app", "2.0.0", true, false, true) } }
        val f = runBlocking { core.listFabrics(null, null).single { it.info.fabricId.value.startsWith("mig-app") } }
        val base = "/fabrics/${f.machine}/${f.engineId}/${f.info.fabricId.value}"
        val dashboard = admin.get("/").body()
        assertTrue(dashboard.contains("migration failed") && dashboard.contains("cannot convert 1.0.0 to 2.0.0") && dashboard.contains("href=\"$base\""), dashboard)
        val detail = admin.get(base).body()
        assertTrue(detail.contains("badge bad") && detail.contains("the migration is retried"), detail)
        assertTrue(admin.get("/fabrics/list").body().contains("Retry"))
        java.nio.file.Files.writeString(dir.resolve("home/data/mig-app/app/1/c1/fixed"), "")
        admin.post("$base/start", emptyMap())
        assertTrue(admin.get("/fabrics/list").body().contains("badge ok"))
    }
}
