// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.test.TestTls
import cringle.repository.PackageRepository
import cringle.repository.RepositoryClient
import cringle.repository.RepositoryServer
import cringle.repository.RepositoryTls
import cringle.router.users.UserManager
import java.nio.file.Files
import java.nio.file.Path

/**
 * A repository over TLS for the functional tests: the builds under test connect with the identity of the build in
 * [home] (and in `<home>/empty`, the home of the tests that bring their own environment), so both are pre-created here
 * and entered into the trust store of the repository, as an operator would do with the fingerprint of a build. The
 * fingerprint of the repository key is what the builds are pinned to. All keys live in [temp], the identities of the
 * builds in [home], a folder of the build directory, never the home of the user who runs the test.
 */
internal class TlsRepository(private val temp: Path, private val home: Path) {
    private val tls = TestTls(temp.resolve("tls"))

    /** The fingerprint of the key of the repository, which the builds are pinned to. */
    val fingerprint: String get() = tls.fingerprint(REPOSITORY)

    init {
        tls.identity(REPOSITORY, ComponentKind.REPOSITORY)
        tls.identity(CHECKER)
        tls.trust(REPOSITORY, CHECKER)
        tls.trust(CHECKER, REPOSITORY, TrustKind.SERVER)
    }

    /**
     * Starts the repository over [repository], trusting the builds in [home]. The profile of [home] holds the
     * fingerprint (and nothing else) afterwards, so a build finds the repository key without being told.
     */
    fun start(repository: PackageRepository, users: UserManager? = null, upload: Path): RepositoryServer {
        for (build in listOf(home, home.resolve("empty"))) {
            Files.createDirectories(build)
            val identity = Identity.loadOrCreate(build, RepositoryConnection.SUBJECT)
            tls.trustStore(REPOSITORY).add(TrustEntry(identity.publicKeyFingerprint, "build:${build.fileName}", TrustKind.COMPONENT))
        }
        writeProfile(null, null)
        return RepositoryServer(repository, users = users, tempDir = upload, tls = RepositoryTls(tls.identity(REPOSITORY), tls.trustStore(REPOSITORY))).start()
    }

    /** The profile of `cringle login` in [home]: the address and the token, or `null`, and the repository fingerprint. */
    fun writeProfile(server: String?, token: String?, fingerprint: String? = this.fingerprint) {
        Files.createDirectories(home)
        val fields = listOf(
            "\"server\": ${server?.let { "\"$it\"" } ?: "null"}",
            "\"token\": ${token?.let { "\"$it\"" } ?: "null"}",
            "\"fingerprint\": ${fingerprint?.let { "\"$it\"" } ?: "null"}",
        )
        Files.writeString(home.resolve(CliProfile.FILE_NAME), "{" + fields.joinToString(", ") + "}")
    }

    /** A client that checks what a build stored: it trusts the repository, and the repository trusts it. */
    fun client(address: String, token: String? = null): RepositoryClient =
        RepositoryClient(address, token, RepositoryTls(tls.identity(CHECKER), tls.trustStore(CHECKER)))

    private companion object {
        const val REPOSITORY = "repository"
        const val CHECKER = "checker"
    }
}
