// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.contract.UserRole
import cringle.management.v1.ManagementServiceGrpc
import cringle.router.users.AuthInterceptor
import cringle.router.users.FileUserStore
import cringle.router.users.Permission
import cringle.router.users.UserGrpcService
import cringle.router.users.UserManager
import cringle.user.v1.UserServiceGrpc
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServiceDescriptor
import io.grpc.Status
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The role matrix of the ManagementServer (#73, criterion "rolesAreEnforced prüft nur fünf Methoden"): every RPC method
 * of the management service and of the user service is called with every kind of caller, and the answer has to be the
 * one the permission of that method asks for.
 *
 * The expectation is written down here independently of `Permission.of`: a viewer may read, an operator may read and
 * operate, an administrator may do everything, an end user only what needs no role and an application, and a caller
 * without a token nothing. A method that is added to a service without an entry in its permission map fails
 * [everyMethodOfTheServicesHasAPermission] before the matrix can quietly skip it.
 */
class RoleMatrixTest {
    @TempDir
    lateinit var dir: Path

    private enum class Caller { NOBODY, END_USER, VIEWER, OPERATOR, ADMIN }

    private val closeables = ArrayList<AutoCloseable>()
    private lateinit var channel: ManagedChannel
    private val tokens = HashMap<Caller, String?>()

    @BeforeEach
    fun server() {
        val users = UserManager(FileUserStore(dir.resolve("users.json")))
        tokens[Caller.NOBODY] = null
        tokens[Caller.ADMIN] = users.bootstrap()
        for ((caller, role) in listOf(Caller.VIEWER to UserRole.VIEWER, Caller.OPERATOR to UserRole.OPERATOR, Caller.END_USER to UserRole.END_USER)) {
            tokens[caller] = users.createToken(users.createUser(caller.name.lowercase(), setOf(role)).user.id, "matrix", null).secret
        }
        val core = ManagementCore(ManagementStore(dir.resolve("state.json")))
        closeables += core
        val server = ManagementServer(core, users = users, recoverOnStart = false).start()
        closeables += server
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
        closeables += AutoCloseable { channel.shutdownNow() }
    }

    @AfterEach
    fun stop() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    /** What a caller may do, by the rules of the roles and not by looking at `Permission.of`. */
    private fun allowed(caller: Caller, permission: Permission): Boolean = when (caller) {
        Caller.NOBODY -> false
        Caller.END_USER -> permission == Permission.AUTHENTICATED || permission == Permission.APPLICATION
        Caller.VIEWER -> permission == Permission.AUTHENTICATED || permission == Permission.READ
        Caller.OPERATOR -> permission in setOf(Permission.AUTHENTICATED, Permission.READ, Permission.OPERATE)
        Caller.ADMIN -> true
    }

    /** Calls [method] with an empty request (or an empty stream) and returns how the call ended. */
    @Suppress("UNCHECKED_CAST")
    private fun call(method: MethodDescriptor<*, *>, token: String?): Status {
        val typed = method as MethodDescriptor<Any, Any>
        val call = channel.newCall(typed, CallOptions.DEFAULT.withDeadlineAfter(20, TimeUnit.SECONDS))
        val done = CompletableFuture<Status>()
        val headers = Metadata()
        if (token != null) headers.put(AuthInterceptor.AUTHORIZATION, "Bearer $token")
        call.start(
            object : ClientCall.Listener<Any>() {
                override fun onClose(status: Status, trailers: Metadata) {
                    done.complete(status)
                }
            },
            headers,
        )
        call.request(Int.MAX_VALUE)
        if (typed.type.clientSendsOneMessage()) call.sendMessage(typed.parseRequest(ByteArrayInputStream(ByteArray(0))))
        call.halfClose()
        return done.get(30, TimeUnit.SECONDS)
    }

    private fun methods(service: ServiceDescriptor): Set<String> = service.methods.map { it.fullMethodName }.toSet()

    /**
     * The permission each method is meant to need, written out once more: the matrix below takes its expectations from
     * the map of the server, so it alone would not notice that a method was quietly made easier to call.
     */
    @Test
    fun thePermissionsAreTheIntendedOnes() {
        val management = mapOf(
            "AddMachine" to Permission.ADMINISTER, "RemoveMachine" to Permission.ADMINISTER, "ListMachines" to Permission.READ,
            "CreateEngine" to Permission.OPERATE, "StartEngine" to Permission.OPERATE, "StopEngine" to Permission.OPERATE,
            "DeleteEngine" to Permission.OPERATE, "ListEngines" to Permission.READ, "GetEngine" to Permission.READ,
            "SetEngineTags" to Permission.OPERATE, "Deploy" to Permission.OPERATE, "Undeploy" to Permission.OPERATE,
            "CleanupCache" to Permission.OPERATE, "DeployFabric" to Permission.OPERATE, "StartFabric" to Permission.OPERATE,
            "StopFabric" to Permission.OPERATE, "RemoveFabric" to Permission.OPERATE, "GetFabric" to Permission.READ,
            "ListFabrics" to Permission.READ, "QueryLogs" to Permission.READ, "AddRemoteRouter" to Permission.ADMINISTER,
            "RemoveRemoteRouter" to Permission.ADMINISTER, "ListRemoteRouters" to Permission.READ, "PublishPackage" to Permission.OPERATE,
            "ListPackages" to Permission.READ, "ListVersions" to Permission.READ, "GetPackage" to Permission.READ,
            "SetPluginTrust" to Permission.ADMINISTER, "DownloadPackage" to Permission.READ, "Recover" to Permission.OPERATE,
        ).mapKeys { "cringle.management.v1.ManagementService/" + it.key }
        assertEquals(management, ManagementServer.REQUIRED_PERMISSIONS)
        val users = listOf("CreateUser", "ListUsers", "DeleteUser", "CreateGroup", "ListGroups", "CreateToken", "ListTokens", "RevokeToken")
            .associate { "cringle.user.v1.UserService/$it" to Permission.MANAGE_USERS } +
            ("cringle.user.v1.UserService/WhoAmI" to Permission.AUTHENTICATED)
        assertEquals(users, UserGrpcService.REQUIRED_PERMISSIONS)
    }

    @Test
    fun everyMethodOfTheServicesHasAPermission() {
        for ((service, permissions) in listOf(
            ManagementServiceGrpc.getServiceDescriptor() to ManagementServer.REQUIRED_PERMISSIONS,
            UserServiceGrpc.getServiceDescriptor() to UserGrpcService.REQUIRED_PERMISSIONS,
        )) {
            val declared = methods(service)
            assertEquals(emptySet<String>(), declared - permissions.keys, "methods of ${service.name} without a permission: a new method is closed until someone decides who may call it")
            assertEquals(emptySet<String>(), permissions.keys - declared, "permissions for methods that ${service.name} does not have")
        }
    }

    @Test
    fun everyMethodAnswersEveryCallerAsItsPermissionDemands() {
        val mismatches = ArrayList<String>()
        var calls = 0
        for ((service, permissions) in listOf(
            ManagementServiceGrpc.getServiceDescriptor() to ManagementServer.REQUIRED_PERMISSIONS,
            UserServiceGrpc.getServiceDescriptor() to UserGrpcService.REQUIRED_PERMISSIONS,
        )) {
            for (method in service.methods) {
                val permission = permissions.getValue(method.fullMethodName)
                for (caller in Caller.entries) {
                    val status = call(method, tokens.getValue(caller))
                    calls++
                    val expected = when {
                        allowed(caller, permission) -> "reaches the method"
                        caller == Caller.NOBODY -> Status.Code.UNAUTHENTICATED.name
                        else -> Status.Code.PERMISSION_DENIED.name
                    }
                    val actual = when (status.code) {
                        Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED -> status.code.name
                        else -> "reaches the method"
                    }
                    if (expected != actual) mismatches += "${method.fullMethodName} as $caller (needs $permission): expected $expected but got ${status.code} ${status.description.orEmpty()}"
                }
            }
        }
        assertEquals(emptyList<String>(), mismatches)
        assertTrue(calls >= 5 * 30, "the matrix covers every method and every caller, $calls calls")
    }
}
