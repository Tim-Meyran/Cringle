// SPDX-License-Identifier: Apache-2.0

package cringle.repository

import cringle.common.ComponentKind
import cringle.common.test.TestTls
import cringle.contract.UserRole
import cringle.packaging.PackageWriter
import cringle.packaging.PluginManifest
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import io.grpc.Status
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** The repository over mutual TLS (#37): who gets in is decided by the trust store, before any token is looked at. */
class RepositoryTlsTest {
    @TempDir
    lateinit var dir: Path

    private val tls by lazy { TestTls(dir.resolve("tls")) }

    private fun plugin(name: String, version: String): Path {
        val file = Files.createTempFile(dir, "p-", ".cringle")
        Files.newOutputStream(file).use {
            PackageWriter.writePlugin(PluginManifest(name, version), emptyMap(), emptyMap(), emptyMap(), it)
        }
        return file
    }

    private fun repositoryTls(peer: String) = RepositoryTls(tls.identity(peer, ComponentKind.REPOSITORY), tls.trustStore(peer))

    private fun clientTls(peer: String) = RepositoryTls(tls.identity(peer), tls.trustStore(peer))

    private fun status(body: suspend () -> Unit): Status.Code = assertThrows<RepositoryClientException> { runBlocking { body() } }.status

    /** A client that the server trusts, and a server that the client trusts, publish and download. */
    @Test
    fun aTrustedClientPublishesAndDownloadsOverMutualTls(): Unit = runBlocking {
        tls.identity("repo", ComponentKind.REPOSITORY)
        tls.identity("author")
        tls.trust("repo", "author")
        tls.trust("author", "repo")
        val server = RepositoryServer(PackageRepository(dir.resolve("repo")), tls = repositoryTls("repo")).start()
        RepositoryClient("127.0.0.1:${server.port}", tls = clientTls("author")).use { c ->
            val published = c.publish(plugin("acme-core", "1.0.0"))
            assertEquals(published.sha256, c.get("acme-core", "1.0.0").sha256)
            val target = dir.resolve("dl.cringle")
            c.download("acme-core", "1.0.0", target)
            assertEquals(published.sha256, cringle.packaging.PackageHash.sha256(target))
        }
        server.stop()
    }

    /** The server refuses a client whose key is not in its trust store with a TLS failure, not with `UNAUTHENTICATED`. */
    @Test
    fun aClientWithoutATrustEntryIsRefusedByTlsEvenWithAValidToken(): Unit = runBlocking {
        val users = UserManager(FileUserStore(dir.resolve("users.json")))
        val admin = users.bootstrap()!!
        tls.identity("repo", ComponentKind.REPOSITORY)
        tls.identity("stranger")
        tls.trust("stranger", "repo")
        val server = RepositoryServer(PackageRepository(dir.resolve("repo")), users = users, tls = repositoryTls("repo")).start()
        RepositoryClient("127.0.0.1:${server.port}", admin, clientTls("stranger")).use { c ->
            assertEquals(Status.Code.UNAVAILABLE, status { c.list() })
        }
        server.stop()
    }

    /** A client does not talk to a server whose key is not in its trust store. */
    @Test
    fun aServerWithoutATrustEntryIsRefusedByTheClient(): Unit = runBlocking {
        tls.identity("repo", ComponentKind.REPOSITORY)
        tls.identity("author")
        tls.trust("repo", "author")
        val server = RepositoryServer(PackageRepository(dir.resolve("repo")), tls = repositoryTls("repo")).start()
        RepositoryClient("127.0.0.1:${server.port}", tls = clientTls("author")).use { c ->
            assertEquals(Status.Code.UNAVAILABLE, status { c.list() })
        }
        server.stop()
    }

    /** A plaintext client cannot use a repository that speaks TLS. */
    @Test
    fun aPlaintextClientIsRefusedByATlsServer(): Unit = runBlocking {
        tls.identity("repo", ComponentKind.REPOSITORY)
        val server = RepositoryServer(PackageRepository(dir.resolve("repo")), tls = repositoryTls("repo")).start()
        val plain = io.grpc.ManagedChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
        try {
            val e = assertThrows<io.grpc.StatusException> {
                runBlocking { cringle.repository.v1.RepositoryServiceGrpcKt.RepositoryServiceCoroutineStub(plain).listPackages(cringle.repository.v1.ListPackagesRequest.getDefaultInstance()) }
            }
            assertEquals(Status.Code.UNAVAILABLE, e.status.code)
        } finally {
            plain.shutdownNow()
        }
        server.stop()
    }

    /** A trusted client still needs a token where the repository asks for one: TLS adds to authentication, it does not replace it. */
    @Test
    fun aTrustedClientWithoutATokenStillFailsAuthentication(): Unit = runBlocking {
        val users = UserManager(FileUserStore(dir.resolve("users.json")))
        users.bootstrap()
        users.createUser("v", setOf(UserRole.VIEWER))
        tls.identity("repo", ComponentKind.REPOSITORY)
        tls.identity("author")
        tls.trust("repo", "author")
        tls.trust("author", "repo")
        val server = RepositoryServer(PackageRepository(dir.resolve("repo")), users = users, tls = repositoryTls("repo")).start()
        RepositoryClient("127.0.0.1:${server.port}", tls = clientTls("author")).use { c ->
            assertEquals(Status.Code.UNAUTHENTICATED, status { c.list() })
        }
        server.stop()
    }
}
