// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.common.Identity
import cringle.common.PublicKeyFingerprint
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.TrustStore
import cringle.repository.RepositoryClient
import cringle.repository.RepositoryTls
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.GradleException

/**
 * The TLS connection of a build to the repository of [PublishTarget] (Architecture chapters 5 and 18): a
 * [RepositoryClient] that presents the identity of this build and accepts the repository only with the key whose
 * fingerprint was configured. There is no trust on first use.
 *
 * The identity lives in `<home>/certs` and is created on first use, like the identities of the other components. The
 * repository has to trust its fingerprint ([identityFingerprint]); the trust store of this side is a temporary file
 * that holds the one fingerprint of the repository and is deleted with [close].
 */
internal class RepositoryConnection private constructor(
    val client: RepositoryClient,
    /** The fingerprint of the key of this build, which the repository has to have in its trust store. */
    val identityFingerprint: String,
    private val workDir: Path,
) : AutoCloseable {

    override fun close() {
        client.close()
        workDir.toFile().deleteRecursively()
    }

    companion object {

        /** The subject of the identity of a build; the trust of a peer rests on the key, not on this name. */
        const val SUBJECT: String = "gradle:cringle-plugin"

        /**
         * Opens the connection to [target] with the identity in [home]. [task] names the task in the message of a
         * missing or malformed fingerprint.
         */
        fun open(target: PublishTarget, home: Path, task: String): RepositoryConnection {
            val fingerprint = normalized(target.fingerprint, task)
            val identity = try {
                Identity.loadOrCreate(home, SUBJECT)
            } catch (e: Exception) {
                throw GradleException("$task: cannot load or create the identity of this build in ${home.resolve("certs")}: ${e.message}", e)
            }
            val workDir = Files.createTempDirectory("cringle-gradle-trust")
            try {
                val trustStore = TrustStore(workDir.resolve("trust.json"))
                trustStore.add(TrustEntry(fingerprint, target.server, TrustKind.SERVER, target.server))
                val client = RepositoryClient(target.server, target.token, RepositoryTls(identity, trustStore))
                return RepositoryConnection(client, identity.publicKeyFingerprint, workDir)
            } catch (e: Throwable) {
                workDir.toFile().deleteRecursively()
                throw e
            }
        }

        private fun normalized(fingerprint: String?, task: String): String {
            val text = fingerprint?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: throw GradleException(PublishSettings.missingFingerprint(task))
            if (!PublicKeyFingerprint.pattern.matches(text)) {
                throw GradleException("$task: the repository fingerprint is not a SHA-256 fingerprint (64 hexadecimal characters): '$fingerprint'")
            }
            return text
        }
    }
}
