// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.common.LocalTrustScanner
import cringle.common.TrustKind
import cringle.common.TrustStore
import java.nio.file.Path

/**
 * Keeps the trust store of the management server in step with the other components of the same Cringle home when the local
 * trust is on (`--trust-local`, `CRINGLE_TRUST_LOCAL=1`, `LocalTrust`): the daemon (`COMPONENT`), the router of the daemon
 * (`ROUTER`), the repository (`COMPONENT`) and the engines (`ENGINE`) are entered as soon as their public key files appear in
 * `<home>/daemon`, `<home>/router`, `<home>/repository` and `<home>/engines/<id>`. Nothing is created and nothing is removed.
 *
 * [sync] is cheap and thread-safe; calls closer than `minInterval` to the last scan do nothing, so it can run before every new
 * connection.
 */
public class LocalTrustSync(
    home: Path,
    trustStore: TrustStore,
    minInterval: java.time.Duration = java.time.Duration.ofMillis(500),
) {
    private val scanner = LocalTrustScanner(
        home,
        trustStore,
        listOf(
            LocalTrustScanner.Component("daemon", "daemon", TrustKind.COMPONENT),
            LocalTrustScanner.Component("router", "router", TrustKind.ROUTER),
            LocalTrustScanner.Component("repository", "repository", TrustKind.COMPONENT),
        ),
        engines = true,
        minInterval = minInterval,
    )

    /** Enters what is new; returns the number of entries added. */
    public fun sync(force: Boolean = false): Int = scanner.sync(force)
}
