// SPDX-License-Identifier: Apache-2.0

package cringle.router.users

import cringle.router.MutableClock
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FederatedTokenTest {
    private val clock = MutableClock()
    private fun newKey() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @Test
    fun aTokenRoundTripsAndVerifiesWithTheKeyOfTheIssuerOnly() {
        val key = newKey()
        val text = FederatedToken.issue("b", "alice", key, Duration.ofHours(1), clock)
        assertTrue(text.startsWith("fed1."))
        assertFalse(text.contains('='), "base64url without padding")
        val parsed = FederatedToken.parse(text)!!
        assertEquals("b", parsed.claims.issuer)
        assertEquals("alice", parsed.claims.subject)
        assertEquals(clock.instant().epochSecond, parsed.claims.issuedAt.epochSecond)
        assertEquals(clock.instant().plus(Duration.ofHours(1)).epochSecond, parsed.claims.expiresAt.epochSecond)
        assertTrue(parsed.claims.publicKey.contentEquals(key.public.encoded))
        assertTrue(FederatedToken.verify(parsed, key.public))
        assertFalse(FederatedToken.verify(parsed, newKey().public))
    }

    @Test
    fun partsThatAreNotATokenAreNotParsed() {
        for (bad in listOf("", "crt_abc", "fed1.", "fed1.a", "fed1.a.b", "fed1.e30.AA", "fed1.%%.%%")) assertNull(FederatedToken.parse(bad), bad)
        assertNotNull(FederatedToken.parse(FederatedToken.issue("b", "alice", newKey(), Duration.ofMinutes(1), clock)))
    }
}
