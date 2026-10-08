// SPDX-License-Identifier: Apache-2.0

package cringle.common

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalTrustTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun theFingerprintIsReadFromThePublicKeyFileAndMatchesTheIdentity() {
        val identity = Identity.loadOrCreate(dir.resolve("daemon"), ComponentKind.DAEMON.commonName("daemon"))
        assertEquals(identity.publicKeyFingerprint, LocalTrust.fingerprintOf(dir.resolve("daemon")))
    }

    @Test
    fun aComponentWithoutAnIdentityHasNoFingerprintAndNothingIsCreated() {
        assertNull(LocalTrust.fingerprintOf(dir.resolve("management")))
        assertFalse(Files.exists(dir.resolve("management")), "reading creates nothing")
        Files.createDirectories(dir.resolve("broken/certs"))
        Files.writeString(dir.resolve("broken/certs/identity.pub"), "not a key")
        assertNull(LocalTrust.fingerprintOf(dir.resolve("broken")))
    }

    @Test
    fun ensureCreatesTheIdentityOnceAndTheComponentLoadsTheSameKey() {
        val first = LocalTrust.ensure(dir.resolve("management"), ComponentKind.MANAGEMENT.commonName("management"))
        assertEquals(first, LocalTrust.ensure(dir.resolve("management"), ComponentKind.MANAGEMENT.commonName("management")))
        assertEquals(first, Identity.loadOrCreate(dir.resolve("management"), ComponentKind.MANAGEMENT.commonName("management")).publicKeyFingerprint)
        assertEquals("CN=management:management", Identity.loadOrCreate(dir.resolve("management"), "ignored").certificate.subjectX500Principal.name)
    }

    @Test
    fun trustAddsAnEntryOnceAndLeavesAnExistingOneAlone() {
        val store = TrustStore(dir.resolve("trust.json"))
        val fingerprint = "ab".repeat(32)
        assertTrue(LocalTrust.trust(store, fingerprint, "management", TrustKind.COMPONENT))
        assertFalse(LocalTrust.trust(store, fingerprint, "other name", TrustKind.SERVER))
        assertEquals(listOf("management"), store.list().map { it.name })
    }

    @Test
    fun theSwitchIsTheOptionOrTheEnvironment() {
        assertFalse(LocalTrust.enabled(false, emptyMap()))
        assertTrue(LocalTrust.enabled(true, emptyMap()))
        assertTrue(LocalTrust.enabled(false, mapOf(LocalTrust.ENV to "1")))
        assertFalse(LocalTrust.enabled(false, mapOf(LocalTrust.ENV to "0")))
    }
}
