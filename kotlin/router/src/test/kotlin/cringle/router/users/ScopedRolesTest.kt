// SPDX-License-Identifier: Apache-2.0

package cringle.router.users

import cringle.contract.UserRole
import cringle.router.MutableClock
import cringle.user.v1.RoleScopeRequest
import io.grpc.StatusException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** Roles limited to a machine, project, fabric or framework function (#229). */
class ScopedRolesTest {
    @TempDir
    lateinit var dir: Path

    private val file get() = dir.resolve("users.json")
    private fun manager() = UserManager(FileUserStore(file), MutableClock())
    private fun asUser(m: UserManager, id: String) = m.authenticate(m.createToken(id, "t", null).secret)!!

    private val m1 = Scope(ScopeKind.MACHINE, "m1")
    private val shop = Scope(ScopeKind.PROJECT, "shop")

    @Test
    fun scopeTextRoundTripsAndIsValidated() {
        for (s in listOf("global", "machine:m1", "project:shop", "fabric:shop-app-1", "function:trust", "function:plugin-trust", "function:users")) {
            assertEquals(s, Scope.parse(s).encode())
        }
        for (bad in listOf("", "machine", "machine:", "machine:../x", "function:everything", "global:x", "galaxy:m1", "project:a b")) {
            assertThrows<IllegalArgumentException>(bad) { Scope.parse(bad) }
        }
    }

    @Test
    fun aScopedRoleAppliesOnlyToItsObject() {
        val m = manager()
        val vera = m.createUser("vera", setOf(UserRole.VIEWER))
        m.grantUser(vera.user.id, UserRole.OPERATOR, m1)
        val u = asUser(m, vera.user.id)
        assertTrue(m.allowed(u, Permission.READ, Scope.GLOBAL)) // the global viewer role
        assertFalse(m.allowed(u, Permission.OPERATE, Scope.GLOBAL))
        assertTrue(m.allowed(u, Permission.OPERATE, m1))
        assertFalse(m.allowed(u, Permission.OPERATE, Scope(ScopeKind.MACHINE, "m2")))
        assertFalse(m.allowed(u, Permission.ADMINISTER, m1))
        // an object lies in several scopes: a fabric on m1 of the project shop
        assertTrue(m.allowed(u, Permission.OPERATE, listOf(Scope(ScopeKind.FABRIC, "shop-app-1"), m1, shop)))
        assertFalse(m.allowed(u, Permission.OPERATE, listOf(Scope(ScopeKind.FABRIC, "x-app-1"), Scope(ScopeKind.MACHINE, "m2"), Scope(ScopeKind.PROJECT, "x"))))
        // permissions() is still global only
        assertFalse(Permission.OPERATE in m.permissions(u))
    }

    @Test
    fun aUserWithoutGlobalRoleOnlyHasWhatIsScoped() {
        val m = manager()
        val id = m.createUser("olaf", emptySet()).user.id
        val trust = Scope(ScopeKind.FUNCTION, "plugin-trust")
        m.grantUser(id, UserRole.ADMIN, trust)
        val u = asUser(m, id)
        assertTrue(m.allowed(u, Permission.ADMINISTER, trust))
        assertFalse(m.allowed(u, Permission.ADMINISTER, Scope(ScopeKind.FUNCTION, "trust")))
        assertFalse(m.allowed(u, Permission.READ, shop))
        assertTrue(m.allowed(u, Permission.AUTHENTICATED, Scope.GLOBAL))
    }

    @Test
    fun aGroupGivesItsMembersTheScopedRole() {
        val m = manager()
        m.createGroup("shop-ops", emptySet())
        m.grantGroup("shop-ops", UserRole.OPERATOR, shop)
        val id = m.createUser("gina", emptySet(), setOf("shop-ops")).user.id
        val u = asUser(m, id)
        assertTrue(m.allowed(u, Permission.OPERATE, shop))
        assertEquals(setOf(RoleAssignment(UserRole.OPERATOR, shop)), m.listUsers().single { it.user.id == id }.effectiveScoped)
        m.revokeGroup("shop-ops", UserRole.OPERATOR, shop)
        assertFalse(m.allowed(u, Permission.OPERATE, shop))
    }

    @Test
    fun grantingIsIdempotentAndRevokingNeedsTheRole() {
        val m = manager()
        val id = m.createUser("ivo", setOf(UserRole.VIEWER)).user.id
        m.grantUser(id, UserRole.OPERATOR, m1)
        m.grantUser(id, UserRole.OPERATOR, m1)
        assertEquals(1, m.listUsers().single().user.scoped.size)
        m.revokeUser(id, UserRole.OPERATOR, m1)
        assertEquals(UserException.Kind.NOT_FOUND, assertThrows<UserException> { m.revokeUser(id, UserRole.OPERATOR, m1) }.kind)
        assertEquals(UserException.Kind.NOT_FOUND, assertThrows<UserException> { m.grantUser("nobody", UserRole.OPERATOR, m1) }.kind)
        assertEquals(UserException.Kind.NOT_FOUND, assertThrows<UserException> { m.grantGroup("nogroup", UserRole.OPERATOR, m1) }.kind)
        assertEquals(UserException.Kind.INVALID, assertThrows<UserException> { m.grantUser(id, UserRole.END_USER, m1) }.kind)
        assertEquals(UserException.Kind.INVALID, assertThrows<UserException> { m.grantUser(id, UserRole.OPERATOR, Scope.GLOBAL) }.kind)
    }

    @Test
    fun aDeletedUserHasNothingAndScopedRolesSurviveARestart() {
        val first = manager()
        val id = first.createUser("kim", emptySet()).user.id
        first.grantUser(id, UserRole.OPERATOR, shop)
        val u = asUser(first, id)
        val second = manager()
        assertTrue(second.allowed(u, Permission.OPERATE, shop))
        assertEquals(setOf(RoleAssignment(UserRole.OPERATOR, shop)), second.listUsers().single().user.scoped)
        second.deleteUser(id)
        assertFalse(second.allowed(u, Permission.OPERATE, shop))
    }

    @Test
    fun anOldUserFileWithoutScopesStillLoads() {
        Files.writeString(
            file,
            """{"format":"1","bootstrapped":true,"users":[{"id":"u1","name":"old","roles":["VIEWER"],"groups":["g"]}],"groups":[{"name":"g","roles":["OPERATOR"]}],"tokens":[]}""",
        )
        val m = manager()
        val v = m.listUsers().single()
        assertEquals(setOf(UserRole.VIEWER, UserRole.OPERATOR), v.effectiveRoles)
        assertTrue(v.user.scoped.isEmpty() && v.effectiveScoped.isEmpty())
        // saving writes no "scoped" key for a user without scoped roles
        m.createUser("new", setOf(UserRole.VIEWER))
        assertFalse(Files.readString(file).contains("scoped"))
    }

    @Test
    fun theGrpcMethodsGrantAndRevoke() = runBlocking {
        val m = manager()
        val id = m.createUser("lea", setOf(UserRole.VIEWER)).user.id
        m.createGroup("g", emptySet())
        val service = UserGrpcService(m)
        fun request(subjectUser: Boolean, kind: cringle.user.v1.ScopeKind, name: String) = RoleScopeRequest.newBuilder()
            .also { if (subjectUser) it.userId = id else it.group = "g" }
            .setRole(cringle.user.v1.Role.ROLE_OPERATOR).setScope(cringle.user.v1.Scope.newBuilder().setKind(kind).setName(name)).build()
        val granted = service.grantRole(request(true, cringle.user.v1.ScopeKind.SCOPE_KIND_MACHINE, "m1"))
        assertEquals("m1", granted.user.scopedRolesList.single().scope.name)
        assertEquals(1, granted.user.effectiveScopedRolesCount)
        assertEquals(1, service.grantRole(request(false, cringle.user.v1.ScopeKind.SCOPE_KIND_PROJECT, "shop")).group.scopedRolesCount)
        assertEquals(0, service.revokeRole(request(true, cringle.user.v1.ScopeKind.SCOPE_KIND_MACHINE, "m1")).user.scopedRolesCount)
        // invalid input is INVALID_ARGUMENT, not an exception of the server
        assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT, assertThrows<StatusException> { runBlocking { service.grantRole(request(true, cringle.user.v1.ScopeKind.SCOPE_KIND_MACHINE, "../x")) } }.status.code)
        assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT, assertThrows<StatusException> { runBlocking { service.grantRole(request(true, cringle.user.v1.ScopeKind.SCOPE_KIND_UNSPECIFIED, "")) } }.status.code)
        val both = request(true, cringle.user.v1.ScopeKind.SCOPE_KIND_MACHINE, "m1").toBuilder().setGroup("g").build()
        assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT, assertThrows<StatusException> { runBlocking { service.grantRole(both) } }.status.code)
    }

    @Test
    fun allowedAnywhereIsTheQuestionOfTheInterceptor() {
        val m = manager()
        val none = asUser(m, m.createUser("none", emptySet()).user.id)
        val scoped = m.createUser("sco", emptySet()).user.id.also { m.grantUser(it, UserRole.OPERATOR, shop) }
        val u = asUser(m, scoped)
        assertFalse(m.allowedAnywhere(none, Permission.READ))
        assertTrue(m.allowedAnywhere(none, Permission.AUTHENTICATED))
        assertTrue(m.allowedAnywhere(u, Permission.OPERATE) && m.allowedAnywhere(u, Permission.READ))
        assertFalse(m.allowedAnywhere(u, Permission.ADMINISTER))
        assertFalse(m.allowed(u, Permission.OPERATE, emptyList())) // not globally
    }
}
