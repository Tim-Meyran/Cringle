// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import cringle.common.Identity
import java.nio.file.Path
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.time.Duration

/**
 * The stable identity of an engine: a thin caller of [Identity] in `common` (Architecture chapter 5), stored in
 * `<engineDir>/certs` with the subject `CN=<engineId>`. [renew] issues a new certificate for the same key pair.
 */
public class EngineIdentity private constructor(private val identity: Identity) {
    /** The persistent key pair. */
    public val keyPair: KeyPair get() = identity.keyPair

    /** The current certificate. */
    public val certificate: X509Certificate get() = identity.certificate

    /** SHA-256 of the certificate encoding, lowercase hex. */
    public val fingerprint: String get() = identity.certificateFingerprint

    /** The public key fingerprint that trust is given to; it stays the same after [renew]. */
    public val publicKeyFingerprint: String get() = identity.publicKeyFingerprint

    /** The identity in `common`, for the TLS helper. */
    public val common: Identity get() = identity

    /** Issues a new certificate with the same key pair and subject, and stores it. The identity stays the same. */
    public fun renew() {
        identity.renew()
    }

    public companion object {
        /** Certificate validity, `[Zu bestätigen]`; renewal triggers are an open point in the architecture. */
        public val VALIDITY: Duration = Identity.VALIDITY

        /** Loads the identity from `<engineDir>/certs`, creating key pair and certificate on first use. */
        public fun loadOrCreate(engineDir: Path, engineId: String): EngineIdentity =
            EngineIdentity(Identity.loadOrCreate(engineDir, engineId))
    }
}
