// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.management.test.ManagementTls
import cringle.repository.PackageRepository
import cringle.repository.v1.ListPackagesRequest
import cringle.repository.v1.PluginTrust
import cringle.stdblocks.StdBlockProvider
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Tag("integration")
class StdBlocksInstallerTest {
    @TempDir
    lateinit var dir: Path

    private fun plugins(core: ManagementCore) =
        runBlocking { core.repository().listPackages(ListPackagesRequest.getDefaultInstance()).packagesList.filter { it.name == StdBlockProvider.PLUGIN_NAME } }

    @Test
    fun theStandardBlocksArePublishedOnceAndTrusted() {
        val tls = ManagementTls(dir.resolve("tls"))
        val repository = tls.startRepository(PackageRepository(dir.resolve("repo")))
        val core = tls.core(ManagementStore(dir.resolve("state.json")), "127.0.0.1:${repository.port}")
        try {
            val installer = StdBlocksInstaller(core)
            assertTrue(runBlocking { installer.install() }, "published")
            val published = plugins(core).single()
            assertEquals(StdBlockProvider.VERSION, published.version)
            assertEquals(PluginTrust.PLUGIN_TRUST_TRUSTED, published.trust)

            // a second start does not publish again, and a decision of the administrator stays
            runBlocking { core.repository().setPluginTrust(cringle.repository.v1.SetPluginTrustRequest.newBuilder().setName(StdBlockProvider.PLUGIN_NAME).setTrust(PluginTrust.PLUGIN_TRUST_UNTRUSTED).build()) }
            assertFalse(runBlocking { installer.install() }, "present")
            assertEquals(1, plugins(core).size)
            assertEquals(PluginTrust.PLUGIN_TRUST_UNTRUSTED, plugins(core).single().trust)
        } finally {
            core.close()
            repository.stop()
        }
    }

    @Test
    fun aRepositoryThatCannotBeReachedDoesNotStopTheManagementServer() {
        val tls = ManagementTls(dir.resolve("tls"))
        val repository = tls.startRepository(PackageRepository(dir.resolve("repo")))
        val address = "127.0.0.1:${repository.port}"
        repository.stop()
        val core = tls.core(ManagementStore(dir.resolve("state.json")), address)
        try {
            assertFalse(runBlocking { StdBlocksInstaller(core).installWhenReachable(attempts = 2, pause = 10) })
        } finally {
            core.close()
        }
    }

    @Test
    fun thePackageHasTheBlocksTheJarAndTheSchema() {
        val tls = ManagementTls(dir.resolve("tls"))
        val core = tls.core(ManagementStore(dir.resolve("state.json")), "127.0.0.1:1")
        try {
            val file = dir.resolve("std.cringle")
            java.nio.file.Files.write(file, StdBlocksInstaller(core).packageBytes())
            val plugin = cringle.packaging.PackageReader.readPlugin(file)
            assertEquals(StdBlockProvider().definitions.map { it.name }, plugin.manifest.blocks.map { it.name })
            assertTrue(plugin.files.contains("lib/stdblocks.jar"), plugin.files.toString())
            assertEquals(emptyList<Any>(), cringle.packaging.PackageValidator.validatePlugin(plugin))
        } finally {
            core.close()
        }
    }
}
