// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.Signature
import java.time.Instant

class EngineConfigAndIdentityTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun configIsCreatedThenReusedAndNotOverwrittenByArguments() {
        val created = EngineConfig.loadOrCreate(dir, "e1", "First")
        assertEquals(EngineConfig("e1", "First", null), created)
        assertEquals(created, EngineConfig.loadOrCreate(dir, "e1", "Other name"))
    }

    @Test
    fun nameDefaultsToId() {
        assertEquals("e1", EngineConfig.loadOrCreate(dir, "e1", null).name)
    }

    @Test
    fun configRoundTripsAndKeepsRouterAddress() {
        EngineConfig.loadOrCreate(dir, "e1", null).copy(routerAddress = "localhost:9000").save(dir)
        assertEquals("localhost:9000", EngineConfig.loadOrCreate(dir, "e1", null).routerAddress)
        EngineConfig("e1", "e1", null).save(dir)
        assertNull(EngineConfig.loadOrCreate(dir, "e1", null).routerAddress)
    }

    @Test
    fun configOfAnotherEngineIsRejected() {
        EngineConfig.loadOrCreate(dir, "e1", null)
        assertThrows<IllegalStateException> { EngineConfig.loadOrCreate(dir, "e2", null) }
    }

    @Test
    fun brokenConfigIsRejected() {
        Files.writeString(dir.resolve(EngineConfig.FILE), "{\"name\":\"x\"}")
        assertTrue(assertThrows<IllegalStateException> { EngineConfig.loadOrCreate(dir, "e1", null) }.message!!.contains("missing 'id'"))
    }

    @Test
    fun identityIsCreatedOnceAndReused() {
        val first = EngineIdentity.loadOrCreate(dir, "e1")
        val second = EngineIdentity.loadOrCreate(dir, "e1")
        assertEquals(first.fingerprint, second.fingerprint)
        assertArrayEquals(first.keyPair.private.encoded, second.keyPair.private.encoded)
        assertArrayEquals(first.keyPair.public.encoded, second.keyPair.public.encoded)
    }

    @Test
    fun certificateIsSelfSignedForTheEngineAndMatchesTheKey() {
        val id = EngineIdentity.loadOrCreate(dir, "e1")
        val cert = id.certificate
        assertEquals("CN=e1", cert.subjectX500Principal.name)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        cert.verify(cert.publicKey)
        cert.checkValidity()
        assertTrue(cert.notAfter.toInstant().isAfter(Instant.now().plus(EngineIdentity.VALIDITY).minusSeconds(3600)))
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(id.keyPair.private)
        sig.update(byteArrayOf(1, 2, 3))
        val bytes = sig.sign()
        sig.initVerify(cert.publicKey)
        sig.update(byteArrayOf(1, 2, 3))
        assertTrue(sig.verify(bytes))
    }

    @Test
    fun renewKeepsKeyPairButIssuesNewCertificateAndPersistsIt() {
        val id = EngineIdentity.loadOrCreate(dir, "e1")
        val oldFingerprint = id.fingerprint
        val publicKey = id.keyPair.public.encoded
        id.renew()
        assertNotEquals(oldFingerprint, id.fingerprint)
        assertArrayEquals(publicKey, id.certificate.publicKey.encoded)
        assertEquals(id.fingerprint, EngineIdentity.loadOrCreate(dir, "e1").fingerprint)
    }

    @Test
    fun differentEnginesGetDifferentIdentities() {
        val a = EngineIdentity.loadOrCreate(dir.resolve("a"), "a")
        val b = EngineIdentity.loadOrCreate(dir.resolve("b"), "b")
        assertNotEquals(a.fingerprint, b.fingerprint)
    }

    @Test
    fun privateKeyFileIsOwnerOnlyOnPosixFileSystems() {
        assumeTrue(dir.fileSystem.supportedFileAttributeViews().contains("posix"))
        EngineIdentity.loadOrCreate(dir, "e1")
        val key = dir.resolve("certs/identity.key")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(key)))
        EngineIdentity.loadOrCreate(dir, "e1").renew()
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(key)))
    }
}
