// SPDX-License-Identifier: Apache-2.0

package cringle.common

import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WebCertificateTest {
    @TempDir
    lateinit var dir: Path

    private val identity by lazy { Identity.loadOrCreate(dir, ComponentKind.MANAGEMENT.commonName("management")) }

    private fun names(certificate: X509Certificate): Map<Int, Set<String>> =
        certificate.subjectAlternativeNames.groupBy({ it[0] as Int }, { it[1] as String }).mapValues { it.value.toSet() }

    @Test
    fun theCertificateHasTheNamesOfTheServerAndTheKeyOfTheIdentity() {
        val cert = identity.webCertificate(listOf("localhost", "127.0.0.1", "[::1]", "cringle.example"))
        // 2 is a DNS name, 7 an IP address
        assertEquals(setOf("localhost", "cringle.example"), names(cert)[2])
        assertEquals(setOf("127.0.0.1", "0:0:0:0:0:0:0:1"), names(cert)[7])
        assertEquals(identity.publicKeyFingerprint, PublicKeyFingerprint.of(cert), "the pin of the peers and of the CLI stays valid")
        assertEquals(identity.certificate.subjectX500Principal, cert.subjectX500Principal)
        cert.verify(cert.publicKey)
        assertTrue(Files.exists(dir.resolve("certs").resolve("web.crt")))
    }

    @Test
    fun itIsUsedAgainForTheSameNamesAndIssuedAgainForOtherOnes() {
        val first = identity.webCertificate(listOf("localhost", "b.example"))
        val again = Identity.loadOrCreate(dir, ComponentKind.MANAGEMENT.commonName("management")).webCertificate(listOf("b.example", "LOCALHOST"))
        assertEquals(first, again, "the same names in another order and case")
        val other = identity.webCertificate(listOf("localhost", "c.example"))
        assertNotEquals(first, other)
        assertEquals(setOf("localhost", "c.example"), names(other)[2])
        // a certificate that nearly ends is replaced
        val later = Clock.fixed(Instant.now().plus(Duration.ofDays(3650 - 10)), ZoneOffset.UTC)
        assertNotEquals(other, identity.webCertificate(listOf("localhost", "c.example"), later))
    }
}
