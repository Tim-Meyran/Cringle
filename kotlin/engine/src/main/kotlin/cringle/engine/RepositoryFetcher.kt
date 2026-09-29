// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import cringle.repository.RepositoryClient
import cringle.repository.RepositoryClientException
import java.nio.file.Path

/** Downloads packages from a Repository. Close it after use. */
internal class RepositoryFetcher(address: String, token: String?) : PackageFetcher, AutoCloseable {
    private val client = RepositoryClient(address, token)

    override suspend fun fetch(artifact: Artifact, target: Path) {
        try {
            client.download(artifact.name, artifact.version, target)
        } catch (e: RepositoryClientException) {
            throw PackageCacheException(false, "${artifact.label}: repository ${e.status}: ${e.message}", e)
        }
    }

    override fun close() = client.close()
}
