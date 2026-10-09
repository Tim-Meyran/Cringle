// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.UserRole
import cringle.management.ServiceTestBase
import cringle.management.v1.AddMachineRequest
import cringle.management.v1.CreateEngineRequest
import cringle.router.users.FileUserStore
import cringle.router.users.Scope
import cringle.router.users.UserManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The pages allow what the scoped roles of the user allow, and show only what the user may read (#271). */
@Tag("integration")
class WebScopedAccessTest : ServiceTestBase() {
    private lateinit var users: UserManager
    private lateinit var server: WebServer
    private lateinit var admin: WebTestClient

    private fun web() {
        users = UserManager(FileUserStore(dir.resolve("users.json")))
        val token = users.bootstrap()!!
        server = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        closeables += server
        admin = client(token)
    }

    private fun client(token: String) = WebTestClient(server.port, core.identity.publicKeyFingerprint).login(token)

    private fun user(name: String, global: Set<UserRole> = emptySet(), vararg scoped: Pair<UserRole, String>): WebTestClient {
        val id = users.createUser(name, global).user.id
        for ((role, scope) in scoped) users.grantUser(id, role, Scope.parse(scope))
        return client(users.createToken(id, "t", null).secret)
    }

    private fun deployTwo() {
        deploy("recorded-app")
        runBlocking { s.deploy(cringle.management.v1.DeployProjectRequest.newBuilder().setProject("mig-app").setVersionRange("1.0.0").build()) }
    }

    @Test
    fun aRoleOnAMachineShowsAndAllowsThatMachineOnly() {
        web()
        runBlocking {
            s.addMachine(AddMachineRequest.newBuilder().setMachineId("m2").setDaemonAddress("127.0.0.1:${daemon.port}").build())
            s.createEngine(CreateEngineRequest.newBuilder().setMachineId("m2").setEngineId("x2").addRoles("x").setAutostart(false).build())
        }
        val ops = user("olga", emptySet(), UserRole.OPERATOR to "machine:m2")
        val machines = ops.get("/machines/list").body()
        assertTrue(machines.contains("<strong>m2</strong>") && !machines.contains("<strong>m1</strong>"), machines)
        val engines = ops.get("/engines/list").body()
        assertTrue(engines.contains("<td>m2</td>") && !engines.contains("<td>m1</td>"), engines)
        assertTrue(engines.contains("/engines/m2/x2/start"), engines) // a button for an engine of m2
        // an engine of m1: a flash with PERMISSION_DENIED, nothing changes
        val denied = ops.post("/engines/m1/e-a/stop").body()
        assertTrue(denied.contains("hx-swap-oob=\"beforeend:#flash\"") && denied.contains("PERMISSION_DENIED"), denied)
        assertTrue(runBlocking { core.listEngines("m1").first { it.process.engineId.value == "e-a" }.process.state } == cringle.daemon.v1.EngineProcessState.ENGINE_PROCESS_STATE_RUNNING)
        assertFalse(ops.post("/engines/m2/x2/start").body().contains("PERMISSION_DENIED"))
        // global-only functions: an operator has no ADMINISTER anywhere, so the route is closed
        assertEquals(403, ops.post("/machines", mapOf("id" to "m9", "address" to "127.0.0.1:1")).statusCode())
    }

    @Test
    fun aRoleOnAProjectShowsAndAllowsItsFabricsOnly() {
        web()
        deployTwo()
        val viewer = user("vera", emptySet(), UserRole.VIEWER to "project:mig-app")
        val deployments = viewer.get("/deployments/list").body()
        assertTrue(deployments.contains("<strong>mig-app</strong>") && !deployments.contains("<strong>recorded-app</strong>"), deployments)
        assertFalse(deployments.contains("Undeploy"), "a viewer has no button") 
        assertTrue(viewer.get("/fabrics/list").body().let { it.contains("mig-app-app-1") && !it.contains("recorded-app-app-1") })

        val ops = user("pia", emptySet(), UserRole.OPERATOR to "project:mig-app")
        val own = ops.get("/deployments/list").body()
        assertTrue(own.contains("/deployments/mig-app/undeploy") && !own.contains("recorded-app"), own)
        assertTrue(ops.post("/deployments/recorded-app/undeploy").body().contains("PERMISSION_DENIED"))
        assertTrue(core.deployedFabrics().any { it.project == "recorded-app" })
        // the page of a fabric of another project is refused as well
        val other = core.deployedFabrics().first { it.project == "recorded-app" }
        assertTrue(ops.get("/fabrics/${other.machine}/${other.engineId}/${other.fabricId}").body().contains("PERMISSION_DENIED"))
        assertFalse(ops.post("/deployments/mig-app/undeploy").body().contains("PERMISSION_DENIED"))
    }

    @Test
    fun theDashboardCountsOnlyWhatTheUserMayRead() {
        web()
        deployTwo()
        assertTrue(Regex("""Fabrics</span><span class="card-value">2</span>""").containsMatchIn(admin.get("/").body()))
        val viewer = user("vera", emptySet(), UserRole.VIEWER to "project:mig-app")
        val page = viewer.get("/").body()
        assertTrue(Regex("""Fabrics</span><span class="card-value">1</span>""").containsMatchIn(page), page)
        assertTrue(Regex("""Machines</span><span class="card-value">0</span>""").containsMatchIn(page), page) // a role on a project does not show the machines
    }

    @Test
    fun aRoleOnAFunctionCoversThatFunctionOnly() {
        web()
        val usersAdmin = user("uma", emptySet(), UserRole.ADMIN to "function:users")
        assertEquals(200, usersAdmin.get("/users").statusCode())
        assertTrue(usersAdmin.get("/").body().contains("href=\"/users\""), "the navigation shows Users")
        assertEquals(200, usersAdmin.post("/users", mapOf("name" to "new", "role-VIEWER" to "on")).statusCode())
        // but no machine: the page is empty and the action refused
        assertFalse(usersAdmin.get("/machines/list").body().contains("<strong>m1</strong>"))
        assertTrue(usersAdmin.post("/machines", mapOf("id" to "m9", "address" to "127.0.0.1:1")).body().contains("PERMISSION_DENIED"))

        val trustAdmin = user("tina", emptySet(), UserRole.ADMIN to "function:trust")
        assertEquals(403, trustAdmin.get("/users").statusCode())
        assertFalse(trustAdmin.get("/").body().contains("href=\"/users\""), "no Users entry without function:users")
        assertTrue(trustAdmin.get("/trust/list").body().contains("<table") || trustAdmin.get("/trust/list").body().contains("Nothing is trusted"))
        val noPluginTrust = trustAdmin.post("/packages/some-plugin/trust", mapOf("trust" to "trusted")).body()
        assertTrue(noPluginTrust.contains("PERMISSION_DENIED"), noPluginTrust)
    }

    @Test
    fun aUserWithoutAnyRoleSeesNothingAndAGlobalRoleStillCoversEverything() {
        web()
        deployTwo()
        val nobody = user("nick")
        assertEquals(403, nobody.get("/").statusCode())
        assertEquals(403, nobody.get("/fabrics/list").statusCode())
        val viewer = user("gus", setOf(UserRole.VIEWER))
        assertTrue(viewer.get("/fabrics/list").body().let { it.contains("mig-app-app-1") && it.contains("recorded-app-app-1") })
        assertEquals(403, viewer.post("/deployments/mig-app/undeploy").statusCode())
    }
}
