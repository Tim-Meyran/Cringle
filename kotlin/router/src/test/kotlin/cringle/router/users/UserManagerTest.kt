// SPDX-License-Identifier: Apache-2.0

package cringle.router.users

import cringle.contract.UserRole
import cringle.router.MutableClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class UserManagerTest {
    @TempDir
    lateinit var dir: Path

    private val clock = MutableClock()
    private val file get() = dir.resolve("users.json")
    private fun manager() = UserManager(FileUserStore(file), clock)

    @Test
    fun bootstrapAdminTokenIsCreatedOnceAndWorks() {
        val first = manager()
        val token = first.bootstrap()
        assertNotNull(token)
        val admin = first.authenticate(token!!)
        assertEquals("admin", admin?.name)
        assertEquals(setOf(UserRole.ADMIN), admin?.roles)
        // later starts create nothing and show nothing, but the token keeps working
        val second = manager()
        assertNull(second.bootstrap())
        assertEquals(1, second.listUsers().size)
        assertEquals("admin", second.authenticate(token)?.name)
    }

    @Test
    fun createdTokensCanBeRevokedAndExpire() {
        val m = manager()
        val user = m.createUser("olga", setOf(UserRole.OPERATOR))
        val forever = m.createToken(user.user.id, "forever", null)
        val short = m.createToken(user.user.id, "short", Duration.ofMinutes(10))
        val revoked = m.createToken(user.user.id, "revoked", null)
        m.revokeToken(revoked.info.id)
        assertNotNull(m.authenticate(forever.secret))
        assertNotNull(m.authenticate(short.secret))
        assertNull(m.authenticate(revoked.secret))
        clock.advance(Duration.ofMinutes(11))
        assertNull(m.authenticate(short.secret))
        assertNotNull(m.authenticate(forever.secret))
        assertTrue(m.listTokens(user.user.id).first { it.id == revoked.info.id }.revoked)
    }

    @Test
    fun failuresLookTheSameForUnknownRevokedExpiredAndDeletedUsers() {
        val m = manager()
        val u = m.createUser("u", setOf(UserRole.VIEWER))
        val t = m.createToken(u.user.id, "t", null)
        m.createUser("keep", setOf(UserRole.ADMIN))
        m.deleteUser(u.user.id)
        assertNull(m.authenticate(t.secret))
        assertNull(m.authenticate("crt_unknown"))
        assertNull(m.authenticate(""))
    }

    @Test
    fun tokensAreNotRecoverableFromStorage() {
        val m = manager()
        val u = m.createUser("u", setOf(UserRole.VIEWER))
        val secret = m.createToken(u.user.id, "t", null).secret
        val text = Files.readString(file)
        assertFalse(text.contains(secret))
        assertFalse(text.contains(secret.removePrefix("crt_")))
        assertTrue(text.contains("\"hash\""))
        // the store still authenticates after a restart, using only the hash
        assertEquals("u", manager().authenticate(secret)?.name)
    }

    @Test
    fun groupsAddRolesAndRolesMapToPermissions() {
        val m = manager()
        m.createGroup("ops", setOf(UserRole.OPERATOR))
        val u = m.createUser("u", setOf(UserRole.VIEWER), setOf("ops"))
        assertEquals(setOf(UserRole.VIEWER, UserRole.OPERATOR), u.effectiveRoles)
        val auth = m.authenticate(m.createToken(u.user.id, "t", null).secret)!!
        val perms = m.permissions(auth)
        assertTrue(Permission.OPERATE in perms && Permission.READ in perms)
        assertFalse(Permission.MANAGE_USERS in perms)
        assertEquals(setOf(Permission.AUTHENTICATED, Permission.APPLICATION), m.permissions(cringle.contract.AuthenticatedUser("x", "x", setOf(UserRole.END_USER))))
        assertThrows<UserException> { m.createUser("bad", setOf(UserRole.VIEWER), setOf("nope")) }
    }

    @Test
    fun invalidRequestsAreRejected() {
        val m = manager()
        m.createUser("u", setOf(UserRole.VIEWER))
        assertEquals(UserException.Kind.CONFLICT, assertThrows<UserException> { m.createUser("u", emptySet()) }.kind)
        assertEquals(UserException.Kind.INVALID, assertThrows<UserException> { m.createUser(" ", emptySet()) }.kind)
        assertEquals(UserException.Kind.NOT_FOUND, assertThrows<UserException> { m.createToken("nope", "t", null) }.kind)
        assertEquals(UserException.Kind.INVALID, assertThrows<UserException> { m.createToken(m.listUsers().single().user.id, "t", Duration.ZERO) }.kind)
        assertEquals(UserException.Kind.NOT_FOUND, assertThrows<UserException> { m.revokeToken("nope") }.kind)
    }

    @Test
    fun theLastAdminCannotBeDeleted() {
        val m = manager()
        val admin = m.createUser("root", setOf(UserRole.ADMIN))
        assertEquals(UserException.Kind.CONFLICT, assertThrows<UserException> { m.deleteUser(admin.user.id) }.kind)
        m.createUser("root2", setOf(UserRole.ADMIN))
        m.deleteUser(admin.user.id)
    }

    @Test
    fun corruptUserFileIsReportedNotReplaced() {
        Files.writeString(file, "{ nope")
        assertThrows<UserStoreException> { manager() }
        assertEquals("{ nope", Files.readString(file))
    }
}
