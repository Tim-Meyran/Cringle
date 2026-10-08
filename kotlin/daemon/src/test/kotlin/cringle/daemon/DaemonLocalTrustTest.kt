// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import cringle.common.LocalTrust
import cringle.common.TrustKind
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `--trust-local` of the daemon: the management server of the same home is trusted without a fingerprint being copied. */
class DaemonLocalTrustTest {
    @TempDir
    lateinit var home: Path

    @Test
    fun withTheLocalTrustTheManagementServerOfTheHomeIsTrustedByTheDaemonAndItsRouter() {
        Daemon(home, combined = true, trustLocal = true).use { daemon ->
            daemon.start()
            val management = LocalTrust.fingerprintOf(home.resolve("management")) ?: error("the daemon has to create the identity the management server will load")
            assertEquals(TrustKind.COMPONENT, daemon.trustStore.list().single { it.fingerprint == management }.kind)
            assertTrue(daemon.router!!.tls!!.trustStore.isTrusted(management), "the management server may call the router")
        }
    }

    @Test
    fun withoutTheLocalTrustNothingIsTrustedAndNothingIsCreated() {
        Daemon(home, combined = true).use { daemon ->
            daemon.start()
            assertFalse(Files.exists(home.resolve("management")))
            assertTrue(daemon.trustStore.list().none { it.kind == TrustKind.COMPONENT })
        }
    }

    @Test
    fun theKeyOfTheManagementServerThatAlreadyExistsIsTheOneThatIsTrusted() {
        val existing = LocalTrust.ensure(home.resolve("management"), "management:management")
        Daemon(home, trustLocal = true).use { daemon ->
            assertTrue(daemon.trustStore.isTrusted(existing))
        }
    }
}
