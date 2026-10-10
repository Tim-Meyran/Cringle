// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.daemon.Daemon
import cringle.management.v1.RecoverRequest
import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The operations of one installation, one after the other (#234, `docs/acceptance-1.0.md`): a project is updated over several versions with migrations of its
 * data, rolled back with the downgrade processor, and the certificate of the daemon is renewed and the daemon started again; the management server still
 * reaches it (trust is given to the key, which renewal keeps) and brings the fabrics back.
 */
@Tag("integration")
class OperationsEndToEndTest : ServiceTestBase() {
    private val home get() = dir.resolve("home")
    private val data get() = home.resolve("data/mig-app/app/1/c1")

    private fun deploy(version: String) = runBlocking { core.deploy("mig-app", version, true, false, true) }

    private fun runningVersion() = core.deployedFabrics().filter { it.project == "mig-app" }.map { it.version }.distinct().single()

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timeout: $what" }
            Thread.sleep(200)
        }
    }

    @Test
    fun updateRollbackAndCertificateRenewalInOneInstallation() {
        // update: the data of version 1 is migrated by the processors of version 2 and 3
        deploy("1.0.0")
        Files.writeString(data.resolve("fixed"), "")
        deploy("2.0.0")
        assertEquals("2.0.0", runningVersion())
        deploy("3.0.0")
        assertEquals("3.0.0", runningVersion())

        // rollback: the downgrade processor of the version that comes back runs on the data
        val rolledBack = runBlocking { core.rollback("mig-app") }
        assertEquals("2.0.0", rolledBack.version)
        assertEquals("3.0.0->2.0.0", Files.readString(data.resolve("migrated")))
        assertEquals("2.0.0", runningVersion())

        // renewal: the same key with a new certificate; trust is given to the key, so nothing has to be entered anywhere
        val identity = Identity.loadOrCreate(home.resolve("daemon"), ComponentKind.DAEMON.commonName("daemon"))
        val key = identity.publicKeyFingerprint
        val certificate = identity.certificateFingerprint
        identity.renew()
        assertEquals(key, identity.publicKeyFingerprint)
        assertNotEquals(certificate, identity.certificateFingerprint)

        // the new certificate is used from the next start of the daemon (`docs/trust.md`)
        val port = daemon.port
        daemon.close()
        daemon = Daemon(home, port, combined = true).start().also { closeables += it }
        assertEquals(key, daemon.identityFingerprint)
        tls.trust(daemon)
        await("the management server reaches the daemon with the renewed certificate") { runBlocking { core.listMachines() }.single().reachable }

        // the engines were processes of the old daemon: the management server brings them and the fabrics back
        val report = runBlocking { s.recover(RecoverRequest.getDefaultInstance()) }
        assertEquals(emptyList<String>(), report.problemsList)
        await("the fabrics of the project run again") { runCatching { runningVersion() }.getOrNull() == "2.0.0" }
        assertTrue(Files.exists(data.resolve("migrated")), "the data of the project is still there after the restart")
    }
}
