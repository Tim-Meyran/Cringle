// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import cringle.repository.RepositoryClient
import cringle.repository.RepositoryClientException
import cringle.repository.RepositoryTls
import java.nio.file.Path

/**
 * Downloads packages from a Repository over mutual TLS with the identity and the trust store of the engine: the
 * repository is accepted only if its key is in the trust store, and it has to trust the key of the engine. Close it
 * after use.
 */
internal class RepositoryFetcher(address: String, token: String?, tls: RepositoryTls) : PackageFetcher, AutoCloseable {
    private val client = RepositoryClient(address, token, tls)

    override suspend fun fetch(artifact: Artifact, target: Path) {
        try {
            client.download(artifact.name, artifact.version, target)
        } catch (e: RepositoryClientException) {
            throw PackageCacheException(false, "${artifact.label}: repository ${e.status}: ${e.message}", e)
        }
    }

    override fun close() = client.close()
}
