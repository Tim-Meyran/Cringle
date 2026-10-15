// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.contract.BlockDefinition
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import cringle.contract.UserRole
import cringle.management.test.ManagementTls
import cringle.management.web.WebServer
import cringle.management.web.WebTestClient
import cringle.repository.PackageRepository
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import cringle.testkit.TestPluginBuilder
import io.grpc.Status
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Fetching the packages of a remote repository (#426): two repositories, the management server trusts both. */
@Tag("integration")
class FetchPackagesTest {
    @TempDir
    lateinit var dir: Path

    private val closeables = ArrayList<AutoCloseable>()
    private lateinit var core: ManagementCore
    private lateinit var local: PackageRepository
    private lateinit var remote: PackageRepository
    private lateinit var remoteAddress: String
    private lateinit var web: WebServer
    private lateinit var users: UserManager
    private lateinit var admin: WebTestClient
    private lateinit var viewer: WebTestClient

    private fun plugin(name: String, version: String): Path {
        val string = SchemaRef("cringle.std", "String")
        return TestPluginBuilder(name, version).provider("com.acme.FlowProvider")
            .block(BlockDefinition("src", emptyList(), listOf(PortDefinition("out", PortDirection.OUT, setOf(TetherType.MESSAGE), string)), emptyList()))
            .build(Files.createDirectories(dir.resolve("plugins-$name-$version"))).file
    }

    @BeforeEach
    fun start() {
        local = PackageRepository(dir.resolve("local"))
        remote = PackageRepository(dir.resolve("remote"))
        remote.publish(plugin("acme-flow", "1.0.0"))
        remote.publish(plugin("acme-flow", "1.1.0"))
        remote.publish(plugin("acme-flow", "2.0.0"))
        remote.publish(plugin("acme-other", "1.0.0"))
        local.publish(plugin("acme-flow", "1.0.0"))

        users = UserManager(FileUserStore(dir.resolve("users.json")))
        val token = users.bootstrap()!!
        val viewerToken = users.createToken(users.createUser("vera", setOf(UserRole.VIEWER)).user.id, "web", null).secret
        val tls = ManagementTls(dir.resolve("tls"))
        val localServer = tls.startRepository(local)
        val remoteServer = tls.startRepository(remote)
        closeables += AutoCloseable { localServer.stop() }
        closeables += AutoCloseable { remoteServer.stop() }
        remoteAddress = "127.0.0.1:${remoteServer.port}"
        core = tls.core(ManagementStore(dir.resolve("state.json")), "127.0.0.1:${localServer.port}")
        closeables += core
        web = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        closeables += web
        admin = WebTestClient(web.port, tls.identity.publicKeyFingerprint).login(token)
        viewer = WebTestClient(web.port, tls.identity.publicKeyFingerprint).login(viewerToken)
    }

    @AfterEach
    fun stop() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    private fun here() = local.list().map { "${it.name}@${it.version}" }.toSet()

    private fun fetch(vararg specs: String, update: Boolean = false, dryRun: Boolean = false) = runBlocking { core.fetchPackages(remoteAddress, null, specs.toList(), update, dryRun) }

    @Test
    fun everyMissingVersionArrivesAndASecondRunFindsThemPresent() {
        val first = fetch()
        assertEquals(setOf("acme-flow@1.1.0", "acme-flow@2.0.0", "acme-other@1.0.0"), first.packagesList.map { "${it.name}@${it.version}" }.toSet())
        assertTrue(first.packagesList.all { it.state == "fetched" && it.kind == "plugin" && it.message.contains("untrusted") }, first.toString())
        assertEquals(1, first.present)
        assertEquals(setOf("acme-flow@1.0.0", "acme-flow@1.1.0", "acme-flow@2.0.0", "acme-other@1.0.0"), here())
        val second = fetch()
        assertEquals(0, second.packagesCount)
        assertEquals(4, second.present)
        // the trust status is not copied: a fetched plugin is untrusted here
        assertTrue(local.list().filter { it.version != "1.0.0" || it.name == "acme-other" }.all { it.trust == cringle.repository.PluginTrust.UNTRUSTED })
    }

    @Test
    fun aRangeAndTheUpdateOptionSelect() {
        val ranged = fetch("acme-flow@^1.0.0")
        assertEquals(listOf("acme-flow@1.1.0"), ranged.packagesList.map { "${it.name}@${it.version}" })
        val updated = fetch(update = true)
        assertEquals(listOf("acme-flow@2.0.0"), updated.packagesList.map { "${it.name}@${it.version}" }, "acme-other does not exist here and is not fetched by an update")
        assertTrue("acme-other@1.0.0" !in here())
        val named = fetch("acme-other")
        assertEquals(listOf("acme-other@1.0.0"), named.packagesList.map { "${it.name}@${it.version}" })
    }

    @Test
    fun aDryRunCopiesNothing() {
        val dry = fetch(dryRun = true)
        assertEquals(3, dry.packagesCount)
        assertTrue(dry.packagesList.all { it.state == "would-fetch" })
        assertEquals(setOf("acme-flow@1.0.0"), here())
    }

    @Test
    fun anUnreachableSourceAnInvalidRangeAndAMissingAddressAreRefused() {
        val unreachable = runCatching { runBlocking { core.fetchPackages("127.0.0.1:1", null, emptyList(), false, false) } }.exceptionOrNull()
        assertTrue(unreachable is ManagementException && unreachable.message!!.contains("remote repository"), unreachable.toString())
        val range = runCatching { fetch("acme-flow@not-a-range") }.exceptionOrNull()
        assertEquals(Status.Code.INVALID_ARGUMENT, (range as ManagementException).code)
        assertEquals(Status.Code.INVALID_ARGUMENT, (runCatching { runBlocking { core.fetchPackages(" ", null, emptyList(), false, false) } }.exceptionOrNull() as ManagementException).code)
    }

    @Test
    fun thePageFetchesAndNeedsOperate() {
        val page = admin.get("/packages").body()
        assertTrue(page.contains("Fetch from a remote repository") && page.contains("/packages/fetch"), page)
        val dry = admin.post("/packages/fetch", mapOf("source" to remoteAddress, "dryRun" to "on")).body()
        assertTrue(dry.contains("3 version(s) would be fetched") && dry.contains("would-fetch"), dry)
        assertEquals(setOf("acme-flow@1.0.0"), here())
        val done = admin.post("/packages/fetch", mapOf("source" to remoteAddress, "packages" to "acme-other, acme-flow@^2")).body()
        assertTrue(done.contains("2 version(s) fetched, 1 already here") || done.contains("2 version(s) fetched"), done)
        assertTrue("acme-other@1.0.0" in here() && "acme-flow@2.0.0" in here())
        val error = admin.post("/packages/fetch", mapOf("source" to "127.0.0.1:1")).body()
        assertTrue(error.contains("class=\"notice error\""), error)
        assertEquals(403, viewer.post("/packages/fetch", mapOf("source" to remoteAddress)).statusCode())
        assertTrue(!viewer.get("/packages").body().contains("/packages/fetch"))
    }
}
