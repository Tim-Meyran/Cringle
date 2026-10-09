// SPDX-License-Identifier: Apache-2.0

package cringle.router.users

import cringle.contract.UserRole
import cringle.router.MutableClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class InviteTest {
    @TempDir
    lateinit var dir: Path

    private val clock = MutableClock()
    private val file get() = dir.resolve("users.json")
    private fun manager() = UserManager(FileUserStore(file), clock)
    private val machine = RoleAssignment(UserRole.OPERATOR, Scope(ScopeKind.MACHINE, "m1"))

    private fun kind(block: () -> Unit) = assertThrows<UserException>(block).kind

    @Test
    fun redeemingCreatesTheUserWithThePresetAndOneToken() {
        val users = manager()
        users.createGroup("ops", setOf(UserRole.VIEWER))
        val invite = users.createInvite("admin", setOf(UserRole.END_USER), setOf("ops"), setOf(machine), Duration.ofHours(2))
        assertTrue(invite.secret.startsWith("inv_"))
        val redeemed = users.redeemInvite(invite.secret, "carol", "phone", Duration.ofDays(1))
        assertEquals("carol", redeemed.user.user.name)
        assertEquals(setOf(UserRole.END_USER), redeemed.user.user.roles)
        assertEquals(setOf("ops"), redeemed.user.user.groups)
        assertEquals(setOf(machine), redeemed.user.user.scoped)
        assertTrue(UserRole.VIEWER in redeemed.user.effectiveRoles)
        val auth = users.authenticate(redeemed.token.secret)
        assertNotNull(auth)
        assertEquals("carol", auth!!.name)
        assertEquals(1, users.listTokens(redeemed.user.user.id).size)
        val info = users.listInvites().single()
        assertEquals(InviteState.USED, info.state(clock.instant()))
        assertEquals("carol", info.usedBy)
    }

    @Test
    fun aLocalNameMustNotLookLikeAUserOfAnotherRegistry() {
        val users = manager()
        val invite = users.createInvite("admin", setOf(UserRole.VIEWER))
        assertEquals(UserException.Kind.INVALID, kind { users.redeemInvite(invite.secret, "alice@site-b") })
        assertEquals(UserException.Kind.INVALID, kind { users.createUser("alice@site-b", setOf(UserRole.VIEWER)) })
        // the invite is still valid after the refused name
        assertEquals("alice", users.redeemInvite(invite.secret, "alice").user.user.name)
    }

    @Test
    fun anUsedExpiredRevokedOrUnknownSecretFailsTheSameWay() {
        val users = manager()
        val used = users.createInvite("admin", setOf(UserRole.VIEWER))
        users.redeemInvite(used.secret, "u1")
        val expired = users.createInvite("admin", setOf(UserRole.VIEWER), ttl = Duration.ofMinutes(5))
        clock.advance(Duration.ofMinutes(6))
        val revoked = users.createInvite("admin", setOf(UserRole.VIEWER))
        users.revokeInvite(revoked.info.id)
        val messages = listOf(used.secret, expired.secret, revoked.secret, "inv_unknown").map {
            val e = assertThrows<UserException> { users.redeemInvite(it, "u2") }
            assertEquals(UserException.Kind.NOT_FOUND, e.kind)
            assertNull(users.peekInvite(it))
            e.message
        }
        assertEquals(1, messages.toSet().size, "one answer for all")
        assertEquals(setOf("u1"), users.listUsers().map { it.user.name }.toSet())
    }

    @Test
    fun aTakenNameIsRefusedAndTheInviteStaysValid() {
        val users = manager()
        users.createUser("dave", setOf(UserRole.VIEWER))
        val invite = users.createInvite("admin", setOf(UserRole.VIEWER))
        assertEquals(UserException.Kind.CONFLICT, kind { users.redeemInvite(invite.secret, "dave") })
        assertEquals(UserException.Kind.INVALID, kind { users.redeemInvite(invite.secret, " ") })
        assertNotNull(users.peekInvite(invite.secret))
        assertEquals("erin", users.redeemInvite(invite.secret, "erin").user.user.name)
    }

    @Test
    fun theLifetimeIsLimitedAndTheGroupMustExist() {
        val users = manager()
        assertEquals(Duration.ofHours(24), users.createInvite("a", setOf(UserRole.VIEWER)).let { Duration.between(it.info.createdAt, it.info.expiresAt) })
        assertEquals(UserException.Kind.INVALID, kind { users.createInvite("a", setOf(UserRole.VIEWER), ttl = Duration.ofDays(31)) })
        assertEquals(UserException.Kind.INVALID, kind { users.createInvite("a", setOf(UserRole.VIEWER), ttl = Duration.ZERO) })
        assertEquals(UserException.Kind.NOT_FOUND, kind { users.createInvite("a", setOf(UserRole.VIEWER), setOf("nogroup")) })
        assertNotNull(users.createInvite("a", setOf(UserRole.ADMIN), ttl = Duration.ofDays(30)))
    }

    @Test
    fun aUsedInviteCannotBeRevoked() {
        val users = manager()
        val invite = users.createInvite("a", setOf(UserRole.VIEWER))
        users.redeemInvite(invite.secret, "frank")
        assertEquals(UserException.Kind.CONFLICT, kind { users.revokeInvite(invite.info.id) })
        assertEquals(UserException.Kind.NOT_FOUND, kind { users.revokeInvite("nope") })
    }

    @Test
    fun theFileKeepsOnlyTheHashAndSurvivesARestart() {
        val users = manager()
        val invite = users.createInvite("admin", setOf(UserRole.VIEWER), scoped = setOf(machine))
        val text = Files.readString(file)
        assertFalse(text.contains(invite.secret), "the secret is not stored")
        assertTrue(text.contains("\"invites\""))
        val again = manager()
        assertEquals(setOf(machine), again.listInvites().single().scoped)
        assertEquals("gina", again.redeemInvite(invite.secret, "gina").user.user.name)
    }

    @Test
    fun anOldFileWithoutInvitesLoads() {
        Files.writeString(file, """{"format":"1","bootstrapped":false,"users":[],"groups":[],"tokens":[]}""")
        assertTrue(manager().listInvites().isEmpty())
    }

    @Test
    fun concurrentRedeemsOfOneInviteMakeOneUser() {
        val users = manager()
        val invite = users.createInvite("admin", setOf(UserRole.VIEWER))
        val start = CountDownLatch(1)
        val success = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 8).map { i ->
                pool.submit {
                    start.await()
                    try {
                        users.redeemInvite(invite.secret, "racer$i")
                        success.incrementAndGet()
                    } catch (_: UserException) {
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, success.get())
        assertEquals(1, users.listUsers().size)
    }
}
