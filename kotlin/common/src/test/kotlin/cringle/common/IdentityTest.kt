// SPDX-License-Identifier: Apache-2.0

package cringle.common

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IdentityTest {
    @TempDir
    lateinit var dir: Path

    private val name = ComponentKind.ROUTER.commonName("r1")

    @Test
    fun secondLoadReturnsTheSameIdentity() {
        val first = Identity.loadOrCreate(dir, name)
        val second = Identity.loadOrCreate(dir, name)
        assertEquals(first.publicKeyFingerprint, second.publicKeyFingerprint)
        assertEquals(first.certificateFingerprint, second.certificateFingerprint)
        assertArrayEquals(first.keyPair.private.encoded, second.keyPair.private.encoded)
        for (file in listOf("identity.key", "identity.pub", "identity.crt")) assertTrue(Files.exists(dir.resolve("certs").resolve(file)), file)
    }

    @Test
    fun renewIssuesANewCertificateForTheSameKey() {
        val identity = Identity.loadOrCreate(dir, name)
        val before = identity.certificateFingerprint
        val key = identity.certificate.publicKey.encoded
        identity.renew()
        assertNotEquals(before, identity.certificateFingerprint)
        assertEquals(PublicKeyFingerprint.of(identity.keyPair.public), identity.publicKeyFingerprint)
        assertArrayEquals(key, identity.certificate.publicKey.encoded)
        assertEquals(identity.certificateFingerprint, Identity.loadOrCreate(dir, name).certificateFingerprint, "the renewed certificate is stored")
    }

    @Test
    fun thePrivateKeyIsOwnerOnlyAndNoTemporaryFileIsLeft() {
        val identity = Identity.loadOrCreate(dir, name)
        val key = dir.resolve("certs/identity.key")
        assertOwnerOnly(key)
        identity.renew()
        assertOwnerOnly(key)
        val leftovers = Files.list(dir.resolve("certs")).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(".tmp") }.toList() }
        assertEquals(emptyList<String>(), leftovers)
    }

    @Test
    fun theSubjectCarriesKindAndId() {
        assertEquals("CN=router:r1", Identity.loadOrCreate(dir, name).certificate.subjectX500Principal.name)
        assertEquals(
            listOf("engine:x", "router:x", "daemon:x", "management:x", "repository:x"),
            ComponentKind.entries.map { it.commonName("x") },
        )
    }

    @Test
    fun theFingerprintIsTheHashOfTheKeyAndDiffersBetweenIdentities() {
        val a = Identity.loadOrCreate(dir.resolve("a"), "a")
        val b = Identity.loadOrCreate(dir.resolve("b"), "b")
        assertTrue(PublicKeyFingerprint.pattern.matches(a.publicKeyFingerprint))
        assertEquals(a.publicKeyFingerprint, PublicKeyFingerprint.of(a.certificate))
        assertNotEquals(a.publicKeyFingerprint, b.publicKeyFingerprint)
    }

    @Test
    fun theCertificateIsSelfSignedAndValid() {
        val certificate = Identity.loadOrCreate(dir, name).certificate
        certificate.verify(certificate.publicKey)
        certificate.checkValidity()
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
    }

    @Test
    fun aNegativeValidityGivesAnExpiredCertificate() {
        val certificate = Identity.loadOrCreate(dir, name, Duration.ofSeconds(-120)).certificate
        assertTrue(certificate.notAfter.toInstant().isBefore(Instant.now()))
    }

    private fun assertOwnerOnly(file: Path) {
        val views = file.fileSystem.supportedFileAttributeViews()
        when {
            "posix" in views -> assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
            "acl" in views -> {
                val acl = Files.getFileAttributeView(file, AclFileAttributeView::class.java).acl
                assertEquals(1, acl.size, "access list has other entries: $acl")
                assertEquals(Files.getOwner(file), acl.single().principal())
            }
            else -> fail<Unit>("no POSIX permissions and no ACLs: $views")
        }
    }
}
