// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.contract.UserRole
import cringle.management.test.ManagementTls
import cringle.management.v1.AddMachineRequest
import cringle.management.v1.CreateEngineRequest
import cringle.management.v1.DeployProjectRequest
import cringle.management.v1.FabricRef
import cringle.management.v1.ListEnginesRequest
import cringle.management.v1.ListFabricsRequest
import cringle.management.v1.ListMachinesRequest
import cringle.router.v1.ListTrustRequest
import cringle.management.v1.ManagementServiceGrpcKt.ManagementServiceCoroutineStub
import cringle.management.v1.UndeployRequest
import cringle.common.v1.FabricId
import cringle.router.users.AuthInterceptor
import cringle.router.users.FileUserStore
import cringle.router.users.Scope
import cringle.router.users.UserManager
import cringle.user.v1.ListUsersRequest
import cringle.user.v1.UserServiceGrpcKt.UserServiceCoroutineStub
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Roles limited to a machine, project, fabric or framework function are checked against the object of every call (#269). */
@Tag("integration")
class ScopedAccessTest : ServiceTestBase() {
    private lateinit var users: UserManager
    private lateinit var channel: io.grpc.ManagedChannel

    private fun auth() {
        users = UserManager(FileUserStore(dir.resolve("users.json")))
        users.bootstrap()
        val server = ManagementServer(core, users = users, recoverOnStart = false).start()
        closeables += server
        channel = ManagementTls.channelTo(server)
        closeables += AutoCloseable { channel.shutdownNow() }
    }

    private fun headers(token: String) = MetadataUtils.newAttachHeadersInterceptor(Metadata().apply { put(AuthInterceptor.AUTHORIZATION, "Bearer $token") })

    /** A user with [global] roles and the scoped ones; the stub of the management service and of the user service with its token. */
    private fun user(name: String, global: Set<UserRole> = emptySet(), vararg scoped: Triple<UserRole, String, Boolean>): Pair<ManagementServiceCoroutineStub, UserServiceCoroutineStub> {
        val id = users.createUser(name, global).user.id
        for ((role, scope, _) in scoped) users.grantUser(id, role, Scope.parse(scope))
        val token = users.createToken(id, "t", null).secret
        return ManagementServiceCoroutineStub(channel).withInterceptors(headers(token)) to UserServiceCoroutineStub(channel).withInterceptors(headers(token))
    }

    private fun scoped(role: UserRole, scope: String) = Triple(role, scope, true)

    private fun code(body: suspend () -> Unit): Status.Code = try {
        runBlocking { body() }
        Status.Code.OK
    } catch (e: StatusException) {
        e.status.code
    }

    private fun fabricRef(id: String, engine: String, machine: String = "m1") = FabricRef.newBuilder().setEngine(ref(engine, machine)).setFabricId(FabricId.newBuilder().setValue(id)).build()

    @Test
    fun aRoleOnAMachineCoversTheEnginesOfThatMachineOnly() {
        auth()
        runBlocking {
            s.addMachine(AddMachineRequest.newBuilder().setMachineId("m2").setDaemonAddress("127.0.0.1:${daemon.port}").build())
            s.createEngine(CreateEngineRequest.newBuilder().setMachineId("m2").setEngineId("x2").addRoles("x").setAutostart(false).build())
        }
        val (ops, _) = user("olga", emptySet(), scoped(UserRole.OPERATOR, "machine:m2"))
        runBlocking {
            assertEquals(listOf("m2"), ops.listMachines(ListMachinesRequest.getDefaultInstance()).machinesList.map { it.machineId })
            // both machines are the same daemon here, so the engines come under both names: only the ones of m2 are shown
            val engines = ops.listEngines(ListEnginesRequest.getDefaultInstance()).enginesList
            assertTrue(engines.isNotEmpty() && engines.all { it.machineId == "m2" } && "x2" in engines.map { it.process.engineId.value })
            ops.startEngine(ref("x2", "m2"))
            ops.stopEngine(ref("x2", "m2"))
        }
        assertEquals(Status.Code.PERMISSION_DENIED, code { ops.stopEngine(ref("e-a", "m1")) })
        assertEquals(Status.Code.PERMISSION_DENIED, code { ops.listEngines(ListEnginesRequest.newBuilder().setMachineId("m1").build()) })
        assertEquals(Status.Code.PERMISSION_DENIED, code { ops.addMachine(AddMachineRequest.newBuilder().setMachineId("m3").setDaemonAddress("127.0.0.1:1").build()) })
    }

    @Test
    fun aRoleOnAProjectCoversItsFabricsAndItsDeployments() {
        auth()
        deploy("recorded-app")
        runBlocking { s.deploy(DeployProjectRequest.newBuilder().setProject("mig-app").setVersionRange("1.0.0").build()) }
        val (ops, _) = user("pia", emptySet(), scoped(UserRole.OPERATOR, "project:mig-app"))
        runBlocking {
            assertEquals(listOf("mig-app-app-1"), ops.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList.map { it.info.fabricId.value })
            val engine = ops.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList.single().engineId.value
            ops.stopFabric(fabricRef("mig-app-app-1", engine))
            ops.startFabric(fabricRef("mig-app-app-1", engine))
        }
        val other = runBlocking { s.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList.first { it.info.fabricId.value == "recorded-app-app-1" } }
        assertEquals(Status.Code.PERMISSION_DENIED, code { ops.stopFabric(fabricRef("recorded-app-app-1", other.engineId.value)) })
        assertEquals(Status.Code.PERMISSION_DENIED, code { ops.undeploy(UndeployRequest.newBuilder().setProject("recorded-app").build()) })
        assertEquals(Status.Code.OK, code { ops.undeploy(UndeployRequest.newBuilder().setProject("mig-app").build()) })
        assertEquals(listOf("recorded-app-app-1"), runBlocking { s.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList.map { it.info.fabricId.value } })
    }

    @Test
    fun aRoleOnAFabricCoversThatFabricOnly() {
        auth()
        deploy("recorded-app")
        runBlocking { s.deploy(DeployProjectRequest.newBuilder().setProject("mig-app").setVersionRange("1.0.0").build()) }
        val all = runBlocking { s.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList }
        val mine = all.first { it.info.fabricId.value == "mig-app-app-1" }
        val other = all.first { it.info.fabricId.value == "recorded-app-app-1" }
        val (viewer, _) = user("vic", emptySet(), scoped(UserRole.VIEWER, "fabric:mig-app-app-1"))
        runBlocking {
            assertEquals("mig-app-app-1", viewer.getFabric(fabricRef("mig-app-app-1", mine.engineId.value)).info.fabricId.value)
            assertEquals(listOf("mig-app-app-1"), viewer.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList.map { it.info.fabricId.value })
        }
        assertEquals(Status.Code.PERMISSION_DENIED, code { viewer.getFabric(fabricRef("recorded-app-app-1", other.engineId.value)) })
        assertEquals(Status.Code.PERMISSION_DENIED, code { viewer.stopFabric(fabricRef("mig-app-app-1", mine.engineId.value)) }) // a viewer does not operate
    }

    @Test
    fun aRoleOnAFrameworkFunctionCoversThatFunctionOnly() {
        auth()
        val (trust, _) = user("tina", emptySet(), scoped(UserRole.ADMIN, "function:trust"))
        runBlocking { trust.listTrust(ListTrustRequest.getDefaultInstance()) }
        assertNotEquals(Status.Code.PERMISSION_DENIED, code { trust.addTrustedComponent(cringle.management.v1.AddTrustedComponentRequest.newBuilder().setFingerprint("zz").setName("x").setKind("COMPONENT").build()) })
        assertEquals(Status.Code.PERMISSION_DENIED, code { trust.addMachine(AddMachineRequest.newBuilder().setMachineId("m9").setDaemonAddress("127.0.0.1:1").build()) })
        assertEquals(Status.Code.PERMISSION_DENIED, code { trust.setPluginTrust(cringle.repository.v1.SetPluginTrustRequest.getDefaultInstance()) })

        val (_, usersAdmin) = user("uma", emptySet(), scoped(UserRole.ADMIN, "function:users"))
        runBlocking { assertTrue(usersAdmin.listUsers(ListUsersRequest.getDefaultInstance()).usersCount >= 1) }
        val (_, machineAdmin) = user("mia", emptySet(), scoped(UserRole.ADMIN, "machine:m1"))
        // the interceptor lets the call in (the role is there for some object), the method refuses it
        assertEquals(Status.Code.PERMISSION_DENIED, code { machineAdmin.listUsers(ListUsersRequest.getDefaultInstance()) })
    }

    @Test
    fun noRoleAnywhereIsRefusedAndGlobalRolesStillCoverEverything() {
        auth()
        deploy("recorded-app")
        val (nobody, _) = user("nick")
        assertEquals(Status.Code.PERMISSION_DENIED, code { nobody.listMachines(ListMachinesRequest.getDefaultInstance()) })
        val (viewer, _) = user("gus", setOf(UserRole.VIEWER))
        runBlocking {
            assertEquals(1, viewer.listMachines(ListMachinesRequest.getDefaultInstance()).machinesCount)
            assertTrue(viewer.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsCount >= 1)
        }
        // a global viewer does not operate
        assertEquals(Status.Code.PERMISSION_DENIED, code { viewer.undeploy(UndeployRequest.newBuilder().setProject("recorded-app").build()) })
    }
}
