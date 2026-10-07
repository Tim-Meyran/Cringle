// SPDX-License-Identifier: Apache-2.0

package cringle.repository

import cringle.contract.UserRole
import cringle.packaging.PackageHashMismatchException
import cringle.packaging.PackageKind
import cringle.packaging.PackageWriter
import cringle.packaging.PluginManifest
import cringle.packaging.ProjectManifest
import cringle.packaging.Resolver
import cringle.packaging.Version
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import io.grpc.Status
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class RepositoryTest {
    @TempDir
    lateinit var dir: Path

    private fun plugin(name: String, version: String, deps: Map<String, String> = emptyMap()): Path {
        val file = Files.createTempFile(dir, "p-", ".cringle")
        Files.newOutputStream(file).use {
            PackageWriter.writePlugin(PluginManifest(name, version, dependencies = deps), emptyMap(), emptyMap(), emptyMap(), it)
        }
        return file
    }

    private fun project(name: String, version: String, deps: Map<String, String> = emptyMap()): Path {
        val file = Files.createTempFile(dir, "j-", ".cringle")
        Files.newOutputStream(file).use {
            PackageWriter.writeProject(ProjectManifest(name, version, dependencies = deps), emptyList(), emptyMap(), emptyMap(), it)
        }
        return file
    }

    private fun repo() = PackageRepository(dir.resolve("repo"))

    private val tls by lazy { cringle.common.test.TestTls(dir.resolve("tls")) }

    /** The repository over mutual TLS: the one client of the tests is trusted, and trusts the server. */
    private fun serve(r: PackageRepository, users: UserManager? = null): RepositoryServer {
        tls.identity("repo", cringle.common.ComponentKind.REPOSITORY)
        tls.identity("client")
        tls.trust("repo", "client")
        tls.trust("client", "repo", cringle.common.TrustKind.SERVER)
        return RepositoryServer(r, users = users, tls = RepositoryTls(tls.identity("repo"), tls.trustStore("repo"))).start()
    }

    private fun client(address: String, token: String? = null) =
        RepositoryClient(address, token, RepositoryTls(tls.identity("client"), tls.trustStore("client")))

    private fun code(body: () -> Unit): RepositoryError = assertThrows<RepositoryException>(body).error

    @Test
    fun publishListGetAndImmutability() {
        val r = repo()
        val e = r.publish(plugin("acme-core", "1.0.0"))
        assertEquals(PackageKind.PLUGIN, e.kind)
        assertEquals(PluginTrust.UNTRUSTED, e.trust)
        r.publish(plugin("acme-core", "1.1.0"))
        r.publish(project("shop", "0.1.0", mapOf("acme-core" to "^1.0.0")))
        assertEquals(3, r.list().size)
        assertEquals(1, r.list(PackageKind.PROJECT).size)
        assertEquals(listOf("1.0.0", "1.1.0"), r.versions("acme-core").map { it.version })
        assertNull(r.get("shop", "0.1.0").trust)
        assertEquals(RepositoryError.ALREADY_EXISTS, code { r.publish(plugin("acme-core", "1.0.0")) })
        assertEquals(RepositoryError.ALREADY_EXISTS, code { r.publish(project("acme-core", "2.0.0")) })
        assertEquals(RepositoryError.NOT_FOUND, code { r.get("nope", "1.0.0") })
    }

    @Test
    fun invalidPackagesAndMissingDependenciesAreRejected() {
        val r = repo()
        val junk = Files.createTempFile(dir, "junk", ".cringle").also { Files.writeString(it, "not a zip") }
        assertEquals(RepositoryError.INVALID, code { r.publish(junk) })
        assertEquals(RepositoryError.INVALID, code { r.publish(project("shop", "0.1.0", mapOf("missing" to "^1.0.0"))) })
        assertEquals(RepositoryError.HASH_MISMATCH, code { r.publish(plugin("a-b", "1.0.0"), "00") })
        assertTrue(r.list().isEmpty())
    }

    @Test
    fun aVersionThatCannotBeParsedIsRejectedBeforeAnythingIsWritten() {
        val r = repo()
        for (bad in listOf("99999999999.0.0", "2147483648.0.0", "1.0.0-01", "1.0.0-1.02")) {
            assertEquals(RepositoryError.INVALID, code { r.publish(plugin("acme-core", bad)) }, bad)
            assertEquals(RepositoryError.INVALID, code { r.publish(project("shop", bad)) }, bad)
        }
        assertTrue(r.list().isEmpty())
        assertFalse(Files.exists(dir.resolve("repo/packages")))
        assertFalse(Files.exists(dir.resolve("repo/index.json")))
        // The repository keeps working, and a version nobody may write twice is refused twice.
        r.publish(plugin("acme-core", "1.0.0-1"))
        assertEquals(listOf("1.0.0-1"), r.versions("acme-core").map { it.version })
        assertEquals(listOf(Version.parse("1.0.0-1")), r.asSource().versions("acme-core"))
        assertEquals(RepositoryError.ALREADY_EXISTS, code { r.publish(plugin("acme-core", "1.0.0-1")) })
        assertEquals(RepositoryError.INVALID, code { r.publish(plugin("acme-core", "1.0.0-01")) })
        assertEquals(RepositoryError.INVALID, code { r.publish(project("shop", "1.0.0-01")) })
        assertEquals(1, r.list().size)
    }

    @Test
    fun anIndexEntryWithAnUnparseableVersionIsLeftOutWithAWarning() {
        val home = dir.resolve("repo")
        repo().publish(plugin("acme-core", "1.0.0"))
        repo().publish(plugin("other", "2.0.0"))
        val index = home.resolve("index.json")
        Files.writeString(index, Files.readString(index).replace("\"version\": \"1.0.0\"", "\"version\": \"99999999999.0.0\""))

        val warnings = ArrayList<String>()
        val r = PackageRepository(home, warn = { warnings += it })
        assertEquals(listOf("other"), r.list().map { it.name })
        assertEquals(listOf("2.0.0"), r.versions("other").map { it.version })
        assertTrue(r.versions("acme-core").isEmpty())
        assertEquals(listOf(Version.parse("2.0.0")), r.asSource().versions("other"))
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue(warnings.single().contains("99999999999.0.0"), warnings.single())

        // The entry is really gone, so the same version can be published again.
        r.publish(plugin("acme-core", "1.0.0"))
        assertEquals(2, r.list().size)
        assertEquals(listOf("1.0.0"), r.versions("acme-core").map { it.version })
    }

    @Test
    fun aBrokenIndexStillKeepsTheRepositoryFromStarting() {
        val home = dir.resolve("repo")
        repo().publish(plugin("acme-core", "1.0.0"))
        val index = home.resolve("index.json")
        Files.writeString(index, Files.readString(index).replace("\"sizeBytes\"", "\"groesse\""))
        assertThrows<RepositoryIndexException> { PackageRepository(home) }
    }

    @Test
    fun trustDefaultsToUntrustedAndPersists() {
        val r = repo()
        r.publish(plugin("acme-core", "1.0.0"))
        r.publish(plugin("acme-core", "1.1.0"))
        r.publish(project("shop", "0.1.0"))
        assertEquals(RepositoryError.INVALID, code { r.setTrust("shop", PluginTrust.TRUSTED) })
        assertEquals(RepositoryError.NOT_FOUND, code { r.setTrust("nope", PluginTrust.TRUSTED) })
        r.setTrust("acme-core", PluginTrust.TRUSTED)
        val again = repo()
        assertEquals(setOf(PluginTrust.TRUSTED), again.versions("acme-core").map { it.trust }.toSet())
        assertEquals(3, again.list().size)
    }

    @Test
    fun repositoryIsAPackageSourceForTheResolver() {
        val r = repo()
        r.publish(plugin("acme-core", "1.0.0"))
        r.publish(plugin("acme-core", "1.2.0"))
        val resolution = Resolver.resolve(mapOf("acme-core" to "^1.0.0"), r.asSource())
        assertEquals("1.2.0", resolution.packages.getValue("acme-core").version.toString())
    }

    @Test
    fun grpcRoundTripWithHashCheck(): Unit = runBlocking {
        val r = repo()
        val server = serve(r)
        client("127.0.0.1:${server.port}").use { c ->
            val published = c.publish(plugin("acme-core", "1.0.0"))
            c.publish(project("shop", "0.1.0", mapOf("acme-core" to "^1.0.0")))
            assertEquals(2, c.list().size)
            assertEquals(1, c.list(PackageKind.PLUGIN).size)
            assertEquals(published.sha256, c.get("acme-core", "1.0.0").sha256)
            val target = dir.resolve("dl.cringle")
            c.download("acme-core", "1.0.0", target)
            assertEquals(published.sha256, cringle.packaging.PackageHash.sha256(target))
            assertEquals(Status.Code.ALREADY_EXISTS, assertThrows<RepositoryClientException> { runBlocking { c.publish(plugin("acme-core", "1.0.0")) } }.status)
            assertEquals(Status.Code.NOT_FOUND, assertThrows<RepositoryClientException> { runBlocking { c.get("x", "1.0.0") } }.status)
            assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows<RepositoryClientException> { runBlocking { c.publish(project("bad", "1.0.0", mapOf("missing" to "^1.0.0"))) } }.status)
            assertEquals(PluginTrust.UNTRUSTED, c.get("acme-core", "1.0.0").trust)
            c.setPluginTrust("acme-core", PluginTrust.TRUSTED)
            assertEquals(PluginTrust.TRUSTED, c.get("acme-core", "1.0.0").trust)
            val viaSource = Resolver.resolve(mapOf("acme-core" to "^1.0.0"), c)
            assertEquals("1.0.0", viaSource.packages.getValue("acme-core").version.toString())
        }
        server.stop()
    }

    @Test
    fun tamperedStoredFileIsDetectedByTheClient(): Unit = runBlocking {
        val r = repo()
        r.publish(plugin("acme-core", "1.0.0"))
        Files.write(r.file("acme-core", "1.0.0"), byteArrayOf(1, 2, 3))
        val server = serve(r)
        client("127.0.0.1:${server.port}").use { c ->
            val target = dir.resolve("bad.cringle")
            assertThrows<PackageHashMismatchException> { runBlocking { c.download("acme-core", "1.0.0", target) } }
            assertTrue(Files.notExists(target))
        }
        server.stop()
    }

    @Test
    fun authenticationAndRolesAreEnforced(): Unit = runBlocking {
        val users = UserManager(FileUserStore(dir.resolve("users.json")))
        val adminToken = users.bootstrap()!!
        val viewer = users.createUser("v", setOf(UserRole.VIEWER))
        val operator = users.createUser("o", setOf(UserRole.OPERATOR))
        val viewerToken = users.createToken(viewer.user.id, "t", null).secret
        val operatorToken = users.createToken(operator.user.id, "t", null).secret
        val server = serve(repo(), users)
        val addr = "127.0.0.1:${server.port}"
        client(addr, adminToken).use { it.publish(plugin("acme-core", "1.0.0")) }
        client(addr, viewerToken).use { c ->
            assertEquals(1, c.list().size)
            assertEquals(Status.Code.PERMISSION_DENIED, assertThrows<RepositoryClientException> { runBlocking { c.publish(plugin("x-y", "1.0.0")) } }.status)
        }
        client(addr, operatorToken).use { c ->
            c.publish(plugin("x-y", "1.0.0"))
            assertEquals(Status.Code.PERMISSION_DENIED, assertThrows<RepositoryClientException> { runBlocking { c.setPluginTrust("x-y", PluginTrust.TRUSTED) } }.status)
        }
        client(addr).use { c ->
            assertEquals(Status.Code.UNAUTHENTICATED, assertThrows<RepositoryClientException> { runBlocking { c.list() } }.status)
        }
        client(addr, adminToken).use { c ->
            c.setPluginTrust("x-y", PluginTrust.TRUSTED)
            assertEquals(PluginTrust.TRUSTED, c.get("x-y", "1.0.0").trust)
        }
        server.stop()
    }
}
