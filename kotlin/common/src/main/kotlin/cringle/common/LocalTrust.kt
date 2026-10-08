// SPDX-License-Identifier: Apache-2.0

package cringle.common

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Trust between the components of one Cringle home (`--trust-local`, `CRINGLE_TRUST_LOCAL=1`). The daemon, the router, the
 * engines and the management server of a machine run under one user and keep their keys below one home (`<home>/daemon`,
 * `<home>/router`, `<home>/engines/<id>`, `<home>/management`), so a component can read the fingerprints of the others from
 * the public key files there instead of an operator copying them. It is off by default: whoever can write into the home
 * can make a component trust a key, which is no more than that person can do with the keys in it anyway. It is meant for
 * development and for a machine that runs all components itself; components on different machines still need the
 * fingerprints entered by hand (`docs/trust.md`).
 */
public object LocalTrust {
    /** The environment variable that turns the local trust on (value `1`). */
    public const val ENV: String = "CRINGLE_TRUST_LOCAL"

    /** Whether the local trust is on: the option was given, or [ENV] is `1` in [environment]. */
    public fun enabled(option: Boolean, environment: Map<String, String> = System.getenv()): Boolean = option || environment[ENV] == "1"

    /**
     * The fingerprint of the key in `<componentDir>/certs/identity.pub`, or `null` if the component has no identity (yet) or the
     * file cannot be read. Nothing is created.
     */
    public fun fingerprintOf(componentDir: Path): String? {
        val file = componentDir.resolve("certs").resolve("identity.pub")
        if (!Files.isRegularFile(file)) return null
        return try {
            val der = Base64.getMimeDecoder().decode(Files.readString(file).lines().filterNot { it.startsWith("-----") }.joinToString(""))
            PublicKeyFingerprint.of(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The fingerprint of the component in [componentDir], whose identity is created with the subject [commonName] if it does not
     * exist yet. The component loads the same identity when it starts, so creating it early is safe.
     */
    public fun ensure(componentDir: Path, commonName: String): String =
        fingerprintOf(componentDir) ?: Identity.loadOrCreate(componentDir, commonName, renew = false).publicKeyFingerprint

    /** Trusts [fingerprint] in [store] as [kind] under [name], unless it is trusted already. Returns whether it was added. */
    public fun trust(store: TrustStore, fingerprint: String, name: String, kind: TrustKind): Boolean {
        if (store.isTrusted(fingerprint)) return false
        store.add(TrustEntry(fingerprint, name, kind))
        return true
    }
}
