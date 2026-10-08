// SPDX-License-Identifier: Apache-2.0

package cringle.repository

import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.common.LocalTrust
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.TrustStore
import io.grpc.Status
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** The repository as a process (`runRepository`): identity, trust store and the local trust. */
class RepositoryProgramTest {
    @TempDir
    lateinit var home: Path

    /** A client with its own identity in [dir] that trusts the repository [program] and nothing else. */
    private fun client(program: RepositoryProgram, dir: String, kind: ComponentKind): RepositoryClient {
        val identity = Identity.loadOrCreate(home.resolve(dir), kind.commonName(dir))
        val trust = TrustStore(home.resolve("$dir-server-trust.json"))
        trust.add(TrustEntry(program.identity.publicKeyFingerprint, "repository", TrustKind.SERVER))
        return RepositoryClient("127.0.0.1:${program.port}", null, RepositoryTls(identity, trust))
    }

    private fun listed(client: RepositoryClient): Status.Code = try {
        runBlocking { client.list() }
        Status.Code.OK
    } catch (e: RepositoryClientException) {
        e.status
    }

    private fun awaitTrusted(program: RepositoryProgram, fingerprint: String) {
        val end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (!program.trustStore.isTrusted(fingerprint)) {
            check(System.nanoTime() < end) { "$fingerprint was not trusted within 10 s" }
            Thread.sleep(50)
        }
    }

    @Test
    fun theRepositoryHasItsIdentityAndItsFolderInTheHomeAndRefusesAPeerWithoutATrustEntry() {
        RepositoryProgram(home).use { program ->
            program.start()
            assertEquals("CN=repository:repository", program.identity.certificate.subjectX500Principal.name)
            assertTrue(Files.isDirectory(home.resolve("repository/upload")))
            assertEquals(program.identity.publicKeyFingerprint, LocalTrust.fingerprintOf(home.resolve("repository")))
            client(program, "stranger", ComponentKind.MANAGEMENT).use { assertEquals(Status.Code.UNAVAILABLE, listed(it)) }
        }
    }

    @Test
    fun withTheLocalTrustTheManagementServerTheEnginesAndTheGradlePluginOfTheHomeAreTrusted() {
        val management = Identity.loadOrCreate(home.resolve("management"), ComponentKind.MANAGEMENT.commonName("management")).publicKeyFingerprint
        val engine = Identity.loadOrCreate(home.resolve("engines/e1"), ComponentKind.ENGINE.commonName("e1")).publicKeyFingerprint
        RepositoryProgram(home, trustLocal = true).use { program ->
            program.start()
            assertEquals(TrustKind.COMPONENT, program.trustStore.list().single { it.fingerprint == management }.kind)
            assertEquals(TrustKind.ENGINE, program.trustStore.list().single { it.fingerprint == engine }.kind)
            client(program, "management", ComponentKind.MANAGEMENT).use { assertEquals(Status.Code.OK, listed(it), "the management server may call the repository") }

            // an engine created later is trusted by a later scan; the identity of the Gradle plugin lives in the home itself
            val late = Identity.loadOrCreate(home.resolve("engines/late"), ComponentKind.ENGINE.commonName("late")).publicKeyFingerprint
            val plugin = Identity.loadOrCreate(home, "gradle:cringle-plugin").publicKeyFingerprint
            awaitTrusted(program, late)
            awaitTrusted(program, plugin)
        }
    }

    @Test
    fun withoutTheLocalTrustTheManagementServerOfTheHomeIsNotTrusted() {
        val management = Identity.loadOrCreate(home.resolve("management"), ComponentKind.MANAGEMENT.commonName("management")).publicKeyFingerprint
        RepositoryProgram(home).use { program ->
            program.start()
            assertFalse(program.trustStore.isTrusted(management))
        }
    }

    @Test
    fun withAuthTheAdminTokenIsGivenOnTheFirstStartOnly() {
        val first = RepositoryProgram(home, auth = true)
        first.use { assertNotNull(it.bootstrapToken) }
        RepositoryProgram(home, auth = true).use { assertNull(it.bootstrapToken) }
        RepositoryProgram(home).use { assertNull(it.bootstrapToken) }
    }

    @Test
    fun aPortThatIsNoPortIsRefusedByTheStartOfTheProgram() {
        assertThrows<Exception> { RepositoryProgram(home, port = 70000).start() }
    }
}
