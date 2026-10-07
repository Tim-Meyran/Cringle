// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** The TLS connection of a build to the repository (#37): the failure cases come before any connection is made. */
class RepositoryConnectionTest {
    @TempDir
    lateinit var home: Path

    private val fingerprint = "ab".repeat(32)

    @Test
    fun withoutAFingerprintTheConnectionIsNotOpenedAndNoIdentityIsMade() {
        val e = assertThrows<GradleException> { RepositoryConnection.open(PublishTarget("127.0.0.1:1", null, null), home, "cringlePublish") }
        assertEquals(PublishSettings.missingFingerprint("cringlePublish"), e.message)
        assertTrue(Files.notExists(home.resolve("certs")), "a build that cannot connect has no reason to create a key")
    }

    @Test
    fun aFingerprintThatIsNoSha256IsRefusedWithItsValue() {
        val e = assertThrows<GradleException> { RepositoryConnection.open(PublishTarget("127.0.0.1:1", null, "xyz"), home, "cringleValidate") }
        assertTrue("'xyz'" in e.message.orEmpty() && e.message.orEmpty().startsWith("cringleValidate:"), e.message)
    }

    @Test
    fun theIdentityOfTheBuildIsCreatedInTheHomeAndStaysTheSame() {
        val target = PublishTarget("127.0.0.1:1", null, fingerprint.uppercase())
        val first = RepositoryConnection.open(target, home, "cringlePublish").use { it.identityFingerprint }
        val second = RepositoryConnection.open(target, home, "cringlePublish").use { it.identityFingerprint }
        assertEquals(first, second)
        assertTrue(Files.isRegularFile(home.resolve("certs/identity.key")))
    }
}
