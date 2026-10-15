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
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.repository.PackageRepository
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import cringle.testkit.TestPluginBuilder
import cringle.testkit.TestProjectBuilder
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

/** Pushing packages to a remote repository (#431): two repositories, the management server trusts both. */
@Tag("integration")
class PushPackagesTest {
    @TempDir
    lateinit var dir: Path

    private val closeables = ArrayList<AutoCloseable>()
    private lateinit var core: ManagementCore
    private lateinit var local: PackageRepository
    private lateinit var remote: PackageRepository
    private lateinit var remoteAddress: String
    private lateinit var admin: WebTestClient
    private lateinit var viewer: WebTestClient

    private fun plugin(name: String, version: String) = TestPluginBuilder(name, version).provider("com.acme.FlowProvider")
        .block(BlockDefinition("src", emptyList(), listOf(PortDefinition("out", PortDirection.OUT, setOf(TetherType.MESSAGE), SchemaRef("cringle.std", "String"))), emptyList()))
        .build(Files.createDirectories(dir.resolve("plugins-$name-$version")))

    @BeforeEach
    fun start() {
        local = PackageRepository(dir.resolve("local"))
        remote = PackageRepository(dir.resolve("remote"))
        val newest = plugin("acme-flow", "1.1.0")
        local.publish(plugin("acme-flow", "1.0.0").file)
        local.publish(newest.file)
        local.publish(plugin("acme-flow", "2.0.0").file)
        local.publish(plugin("acme-other", "1.0.0").file)
        // a project that needs a plugin version the remote repository does not have
        local.publish(TestProjectBuilder("app", "1.0.0").dependency("acme-flow", "^1.1.0").blueprint(Blueprint("app", listOf(BlueprintBlock("s", "acme-flow/src")), emptyList())).build(dir.resolve("work").also { Files.createDirectories(it) }, listOf(newest.pkg)).file)
        remote.publish(plugin("acme-flow", "1.0.0").file)

        val users = UserManager(FileUserStore(dir.resolve("users.json")))
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
        val web = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        closeables += web
        admin = WebTestClient(web.port, tls.identity.publicKeyFingerprint).login(token)
        viewer = WebTestClient(web.port, tls.identity.publicKeyFingerprint).login(viewerToken)
    }

    @AfterEach
    fun stop() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    private fun there() = remote.list().map { "${it.name}@${it.version}" }.toSet()

    private fun push(vararg specs: String, update: Boolean = false, dryRun: Boolean = false) = runBlocking { core.pushPackages(remoteAddress, null, specs.toList(), update, dryRun) }

    @Test
    fun everyMissingVersionArrivesPluginsFirstAndASecondRunFindsThemThere() {
        val first = push()
        assertEquals(listOf("acme-flow@1.1.0", "acme-flow@2.0.0", "acme-other@1.0.0", "app@1.0.0"), first.packagesList.map { "${it.name}@${it.version}" })
        assertTrue(first.packagesList.all { it.state == "pushed" }, first.toString())
        assertEquals(1, first.present)
        assertEquals(setOf("acme-flow@1.0.0", "acme-flow@1.1.0", "acme-flow@2.0.0", "acme-other@1.0.0", "app@1.0.0"), there())
        val second = push()
        assertEquals(0, second.packagesCount)
        assertEquals(5, second.present)
        // the trust status is not copied
        assertTrue(remote.list().filter { it.name == "acme-other" }.all { it.trust == cringle.repository.PluginTrust.UNTRUSTED })
    }

    @Test
    fun aRangeAndUpdateSelectAndADryRunCopiesNothing() {
        assertEquals(listOf("acme-flow@1.1.0"), push("acme-flow@^1.0.0").packagesList.map { "${it.name}@${it.version}" })
        assertEquals(listOf("acme-flow@2.0.0"), push(update = true).packagesList.map { "${it.name}@${it.version}" }, "only names the remote repository has")
        assertTrue("acme-other@1.0.0" !in there())
        val dry = push("acme-other", dryRun = true)
        assertEquals(listOf("would-push"), dry.packagesList.map { it.state })
        assertTrue("acme-other@1.0.0" !in there())
    }

    @Test
    fun aProjectWithoutItsPluginAtTheRemoteIsReportedAndTheRestGoesOn() {
        val result = push("app", "acme-other")
        val states = result.packagesList.associate { it.name to it.state }
        assertEquals("pushed", states["acme-other"])
        assertEquals("failed", states["app"], result.toString())
        assertTrue(result.packagesList.first { it.name == "app" }.message.isNotEmpty())
        assertTrue("app@1.0.0" !in there())
    }

    @Test
    fun anUnreachableRemoteIsReportedAndThePageNeedsOperate() {
        val error = runCatching { runBlocking { core.pushPackages("127.0.0.1:1", null, emptyList(), false, false) } }.exceptionOrNull()
        assertTrue(error is ManagementException && error.message!!.contains("remote repository"), error.toString())
        val page = admin.get("/packages").body()
        assertTrue(page.contains("Push to a remote repository") && page.contains("/packages/push"), page)
        val dry = admin.post("/packages/push", mapOf("source" to remoteAddress, "dryRun" to "on")).body()
        assertTrue(dry.contains("would be pushed") && dry.contains("would-push"), dry)
        val done = admin.post("/packages/push", mapOf("source" to remoteAddress, "packages" to "acme-other")).body()
        assertTrue(done.contains("1 version(s) pushed"), done)
        assertTrue("acme-other@1.0.0" in there())
        assertEquals(403, viewer.post("/packages/push", mapOf("source" to remoteAddress)).statusCode())
        assertTrue(!viewer.get("/packages").body().contains("/packages/push"))
    }
}
