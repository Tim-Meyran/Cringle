// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.common.TrustKind
import cringle.common.TrustStore
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalTrustSyncTest {
    @TempDir
    lateinit var home: Path

    private val store by lazy { TrustStore(home.resolve("management/trust.json")) }

    private fun fingerprint(dir: String, kind: ComponentKind, id: String) =
        Identity.loadOrCreate(home.resolve(dir), kind.commonName(id)).publicKeyFingerprint

    @Test
    fun theKeysOfTheDaemonTheRouterAndTheEnginesOfTheHomeAreTrustedWithTheirKinds() {
        val daemon = fingerprint("daemon", ComponentKind.DAEMON, "daemon")
        val router = fingerprint("router", ComponentKind.ROUTER, "router")
        val e1 = fingerprint("engines/e1", ComponentKind.ENGINE, "e1")
        val e2 = fingerprint("engines/e2", ComponentKind.ENGINE, "e2")

        assertEquals(4, LocalTrustSync(home, store).sync(force = true))

        val entries = store.list().associateBy { it.fingerprint }
        assertEquals(TrustKind.COMPONENT, entries.getValue(daemon).kind)
        assertEquals(TrustKind.ROUTER, entries.getValue(router).kind)
        assertEquals(TrustKind.ENGINE, entries.getValue(e1).kind)
        assertEquals("e2", entries.getValue(e2).name)
    }

    @Test
    fun aHomeWithoutComponentsTrustsNothingAndCreatesNothing() {
        assertEquals(0, LocalTrustSync(home, store).sync(force = true))
        assertTrue(store.list().isEmpty())
        assertTrue(!java.nio.file.Files.exists(home.resolve("daemon")) && !java.nio.file.Files.exists(home.resolve("engines")), "the scan creates no identity")
    }

    @Test
    fun anEngineThatAppearsLaterIsTrustedByTheNextScanAndNothingIsAddedTwice() {
        val sync = LocalTrustSync(home, store, Duration.ZERO)
        assertEquals(0, sync.sync())
        fingerprint("engines/late", ComponentKind.ENGINE, "late")
        assertEquals(1, sync.sync())
        assertEquals(0, sync.sync(), "an entry that exists is left alone")
        assertEquals(1, store.list().size)
    }

    @Test
    fun scansCloserTogetherThanTheIntervalAreSkippedUnlessForced() {
        val sync = LocalTrustSync(home, store, Duration.ofHours(1))
        assertEquals(0, sync.sync())
        fingerprint("daemon", ComponentKind.DAEMON, "daemon")
        assertEquals(0, sync.sync(), "inside the interval nothing is scanned")
        assertEquals(1, sync.sync(force = true))
    }
}
