// SPDX-License-Identifier: Apache-2.0

package cringle.common

import cringle.common.test.TestTls
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class TestTlsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun peersGetOwnIdentitiesInsideTheRootAndTrustOnlyWhatIsAdded() {
        val tls = TestTls(dir)
        val a = tls.identity("alpha", ComponentKind.ENGINE)
        val b = tls.identity("beta", ComponentKind.ROUTER)
        assertNotEquals(a.publicKeyFingerprint, b.publicKeyFingerprint)
        assertEquals("CN=engine:alpha", a.certificate.subjectX500Principal.name)
        assertTrue(Files.isRegularFile(dir.resolve("alpha/certs/identity.key")))
        assertTrue(tls.trustStore("alpha").list().isEmpty())

        tls.trust("alpha", "beta", TrustKind.ROUTER)
        assertTrue(tls.trustStore("alpha").isTrusted(tls.fingerprint("beta")))
        assertFalse(tls.trustStore("beta").isTrusted(tls.fingerprint("alpha")), "trust is one way")

        tls.trustAll()
        assertTrue(tls.trustStore("beta").isTrusted(tls.fingerprint("alpha")))
    }

    @Test
    fun theSameNameGivesTheSameIdentityAndAPathLikeNameIsRefused() {
        val tls = TestTls(dir)
        assertEquals(tls.fingerprint("one"), tls.fingerprint("one"))
        assertThrows<IllegalArgumentException> { tls.identity("../escape") }
    }
}
