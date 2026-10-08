// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.common.LocalTrust
import cringle.common.TrustKind
import cringle.common.TrustStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps the trust store of the management server in step with the other components of the same Cringle home when the local
 * trust is on (`--trust-local`, `CRINGLE_TRUST_LOCAL=1`, [LocalTrust]): the daemon (`COMPONENT`), the router of the daemon
 * (`ROUTER`) and the engines (`ENGINE`) are entered as soon as their public key files appear in `<home>/daemon`,
 * `<home>/router` and `<home>/engines/<id>`. Nothing is created and nothing is ever removed: revoking a key stays a decision
 * of the operator (`cringle trust revoke`, which is not undone by the next [sync] only if the key file is gone).
 *
 * [sync] is cheap (a few small files) and thread-safe; calls closer than [minInterval] to the last scan do nothing, so it can run
 * before every new connection.
 */
public class LocalTrustSync(
    private val home: Path,
    private val trustStore: TrustStore,
    private val minInterval: java.time.Duration = java.time.Duration.ofMillis(500),
) {
    private var lastScan = 0L

    /** Enters what is new; returns the number of entries added. */
    @Synchronized
    public fun sync(force: Boolean = false): Int {
        val now = System.nanoTime()
        if (!force && lastScan != 0L && now - lastScan < minInterval.toNanos()) return 0
        lastScan = now
        var added = 0
        fun trust(componentDir: Path, name: String, kind: TrustKind) {
            val fingerprint = LocalTrust.fingerprintOf(componentDir) ?: return
            if (LocalTrust.trust(trustStore, fingerprint, name, kind)) added++
        }
        trust(home.resolve("daemon"), "daemon", TrustKind.COMPONENT)
        trust(home.resolve("router"), "router", TrustKind.ROUTER)
        val engines = home.resolve("engines")
        if (Files.isDirectory(engines)) {
            Files.list(engines).use { dirs -> dirs.filter { Files.isDirectory(it) }.forEach { trust(it, it.fileName.toString(), TrustKind.ENGINE) } }
        }
        return added
    }
}
