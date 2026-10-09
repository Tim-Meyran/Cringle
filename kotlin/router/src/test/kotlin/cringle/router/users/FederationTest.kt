// SPDX-License-Identifier: Apache-2.0

package cringle.router.users

import cringle.contract.UserRole
import cringle.router.MutableClock
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** Manual federation (#231, Architecture 6.2): registry B signs a token, registry A trusts B's key. */
class FederationTest {
    @TempDir
    lateinit var dir: Path

    private val clock = MutableClock()
    private val keyB: KeyPair = newKey()
    private val keyOther: KeyPair = newKey()

    private fun newKey(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun registryA() = UserManager(FileUserStore(dir.resolve("a.json")), clock)

    private fun tokenOfB(user: String = "alice", ttl: Duration = Duration.ofHours(1), key: KeyPair = keyB, registry: String = "b") =
        FederatedToken.issue(registry, user, key, ttl, clock)

    private val m1 = Scope(ScopeKind.MACHINE, "m1")

    @Test
    fun aTokenOfATrustedRegistryGivesTheUserOfThatRegistry() {
        val a = registryA()
        assertNull(a.authenticate(tokenOfB()), "not trusted yet")
        a.trustRegistry("b", keyB.public, setOf(UserRole.VIEWER))
        val user = a.authenticate(tokenOfB())!!
        assertEquals("alice@b", user.id)
        assertEquals("alice@b", user.name)
        assertEquals(setOf(UserRole.VIEWER), user.roles)
        assertTrue(a.permissions(user).contains(Permission.READ))
        assertFalse(a.permissions(user).contains(Permission.OPERATE))
    }

    @Test
    fun aRegistryWithoutRightsAuthenticatesButMayDoNothing() {
        val a = registryA()
        a.trustRegistry("b", keyB.public)
        val user = a.authenticate(tokenOfB())!!
        assertEquals(emptySet<UserRole>(), user.roles)
        assertFalse(a.allowedAnywhere(user, Permission.READ))
        assertTrue(a.allowedAnywhere(user, Permission.AUTHENTICATED))
    }

    @Test
    fun noLongerTrustedMeansNoLongerAccepted() {
        val a = registryA()
        a.trustRegistry("b", keyB.public, setOf(UserRole.VIEWER))
        val token = tokenOfB()
        assertNotNull(a.authenticate(token))
        a.createFederatedUser("b", "alice", setOf(UserRole.OPERATOR))
        a.untrustRegistry("b")
        assertNull(a.authenticate(token))
        assertEquals(emptyList<String>(), a.listUsers().map { it.user.id })
        assertThrows<UserException> { a.untrustRegistry("b") }
    }

    @Test
    fun aTokenSignedWithAnotherKeyIsRefusedEvenUnderTheNameOfTheRegistry() {
        val a = registryA()
        a.trustRegistry("b", keyB.public, setOf(UserRole.ADMIN))
        // the key in the token is the attacker's, the signature is valid for it, but it is not the trusted key
        assertNull(a.authenticate(tokenOfB(key = keyOther)))
        // the key in the token is the trusted one, the signature is not by it
        val forged = FederatedToken.parse(tokenOfB())!!
        val payloadOfB = tokenOfB().removePrefix("fed1.").substringBefore('.')
        val signatureOfOther = tokenOfB(key = keyOther).substringAfterLast('.')
        assertNull(a.authenticate("fed1.$payloadOfB.$signatureOfOther"))
        assertEquals("alice", forged.claims.subject)
    }

    @Test
    fun aChangedPayloadOrSignatureAndGarbageAreRefused() {
        val a = registryA()
        a.trustRegistry("b", keyB.public, setOf(UserRole.ADMIN))
        val token = tokenOfB()
        val (payload, signature) = token.removePrefix("fed1.").split('.')
        val text = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8).replace("alice", "carol")
        val changed = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))
        assertNull(a.authenticate("fed1.$changed.$signature"))
        assertNull(a.authenticate("fed1.$payload.${signature.reversed()}"))
        for (bad in listOf("fed1.", "fed1.x", "fed1.a.b", "fed1.a.b.c", "fed1..", "fed1.!!.!!")) assertNull(a.authenticate(bad), bad)
        assertNotNull(a.authenticate(token))
    }

    @Test
    fun anExpiredFutureOrLongLivedTokenIsRefused() {
        val a = registryA()
        a.trustRegistry("b", keyB.public, setOf(UserRole.VIEWER))
        val token = tokenOfB(ttl = Duration.ofMinutes(10))
        assertNotNull(a.authenticate(token))
        clock.advance(Duration.ofMinutes(10))
        assertNull(a.authenticate(token), "expired")
        // issued by a registry whose clock is an hour ahead
        val future = FederatedToken.issue("b", "alice", keyB, Duration.ofHours(2), object : java.time.Clock() {
            override fun getZone() = clock.zone

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant() = clock.instant().plus(Duration.ofHours(1))
        })
        assertNull(a.authenticate(future), "issued in the future")
        assertThrows<IllegalArgumentException> { FederatedToken.issue("b", "alice", keyB, Duration.ofDays(31), clock) }
        assertThrows<IllegalArgumentException> { FederatedToken.issue("b", "alice", keyB, Duration.ZERO, clock) }
    }

    @Test
    fun aStoredFederatedUserAddsRightsToThoseOfTheRegistry() {
        val a = registryA()
        a.trustRegistry("b", keyB.public, setOf(UserRole.VIEWER))
        a.createFederatedUser("b", "alice", setOf(UserRole.OPERATOR))
        assertEquals(setOf(UserRole.VIEWER, UserRole.OPERATOR), a.authenticate(tokenOfB("alice"))!!.roles)
        assertEquals(setOf(UserRole.VIEWER), a.authenticate(tokenOfB("bob"))!!.roles)
        assertThrows<UserException> { a.createFederatedUser("b", "alice") }
        assertThrows<UserException> { a.createFederatedUser("nowhere", "alice") }
        assertThrows<UserException> { a.createFederatedUser("b", "a@b") }
    }

    @Test
    fun scopedRolesOfTheRegistryAndOfTheUserLimitWhatIsAllowed() {
        val a = registryA()
        a.trustRegistry("b", keyB.public)
        a.grantRegistry("b", UserRole.OPERATOR, m1)
        val bob = a.authenticate(tokenOfB("bob"))!!
        assertTrue(a.allowed(bob, Permission.OPERATE, m1))
        assertFalse(a.allowed(bob, Permission.OPERATE, Scope(ScopeKind.MACHINE, "m2")))
        assertTrue(a.allowedAnywhere(bob, Permission.OPERATE))
        assertFalse(a.allowed(bob, Permission.ADMINISTER, m1))
        // a role of the stored user for another machine adds
        a.createFederatedUser("b", "bob")
        a.grantUser("bob@b", UserRole.OPERATOR, Scope(ScopeKind.MACHINE, "m2"))
        val again = a.authenticate(tokenOfB("bob"))!!
        assertTrue(a.allowed(again, Permission.OPERATE, Scope(ScopeKind.MACHINE, "m2")))
        assertTrue(a.allowed(again, Permission.OPERATE, m1))
        a.revokeRegistry("b", UserRole.OPERATOR, m1)
        assertFalse(a.allowed(again, Permission.OPERATE, m1))
        assertThrows<UserException> { a.revokeRegistry("b", UserRole.OPERATOR, m1) }
        assertThrows<UserException> { a.grantRegistry("b", UserRole.END_USER, m1) }
    }

    @Test
    fun theTrustSurvivesARestartAndAnOldFileWithoutRegistriesLoads() {
        registryA().trustRegistry("b", keyB.public, setOf(UserRole.VIEWER))
        val again = registryA()
        assertEquals(listOf("b"), again.listRegistries().map { it.name })
        assertNotNull(again.authenticate(tokenOfB()))
        Files.writeString(dir.resolve("old.json"), """{"format":"1","bootstrapped":true,"users":[],"groups":[],"tokens":[]}""")
        assertEquals(emptyList<TrustedRegistry>(), UserManager(FileUserStore(dir.resolve("old.json")), clock).listRegistries())
    }

    @Test
    fun ordinaryTokensAreUnchangedAndRegistryNamesAreChecked() {
        val a = registryA()
        val local = a.createUser("alice", setOf(UserRole.VIEWER))
        val token = a.createToken(local.user.id, "t", null).secret
        a.trustRegistry("b", keyB.public, setOf(UserRole.ADMIN))
        // a local user of the same name is not the federated one
        assertEquals(local.user.id, a.authenticate(token)!!.id)
        assertEquals("alice@b", a.authenticate(tokenOfB())!!.id)
        for (bad in listOf("", "a@b", "-x", "a b", "x".repeat(65))) assertThrows<UserException>(bad) { a.trustRegistry(bad, keyOther.public) }
        assertThrows<UserException> { a.trustRegistry("b", keyOther.public) }
    }
}
