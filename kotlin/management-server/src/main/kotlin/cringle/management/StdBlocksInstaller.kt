// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.management.web.publishPackage
import cringle.packaging.PackageWriter
import cringle.packaging.PluginManifest
import cringle.repository.v1.ListPackagesRequest
import cringle.repository.v1.PackageKind
import cringle.repository.v1.PluginTrust
import cringle.repository.v1.SetPluginTrustRequest
import cringle.stdblocks.StdBlockProvider
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Puts the standard blocks, the plugin `cringle-std` (`docs/standard-blocks.md`), into the repository so that a new installation has blocks in the palette
 * of the editor. The package is built from the jar of the `stdblocks` module, which is a resource of this program. A version that the repository has is left
 * alone (versions are immutable); a version that is new is published and marked `trusted`, once: a later decision of the administrator stays.
 */
internal class StdBlocksInstaller(private val core: ManagementCore) {
    private val log = LoggerFactory.getLogger("cringle.management.std")

    /** The package `cringle-std` as bytes. */
    fun packageBytes(): ByteArray {
        val jar = StdBlocksInstaller::class.java.getResourceAsStream("/std/stdblocks.jar")?.use { it.readBytes() }
            ?: throw IllegalStateException("the jar of the standard blocks is not part of this program")
        val schema = StdBlockProvider::class.java.getResourceAsStream("/cringle/stdblocks/schema.json")!!.use { it.readBytes() }.decodeToString()
        val manifest = PluginManifest(
            StdBlockProvider.PLUGIN_NAME, StdBlockProvider.VERSION, emptyMap(), listOf(StdBlockProvider::class.java.name), emptyList(),
            StdBlockProvider().definitions, listOf("lib/stdblocks.jar"), listOf("schemas/cringle.stdblocks.json"),
        )
        return ByteArrayOutputStream().also { PackageWriter.writePlugin(manifest, mapOf("schemas/cringle.stdblocks.json" to schema), mapOf("lib/stdblocks.jar" to jar), emptyMap(), it) }.toByteArray()
    }

    /** One attempt: publishes and trusts the plugin if the repository does not have this version. Throws if the repository cannot be reached. */
    suspend fun install(): Boolean {
        val present = core.repository().listPackages(ListPackagesRequest.newBuilder().setKind(PackageKind.PACKAGE_KIND_PLUGIN).build()).packagesList
            .any { it.name == StdBlockProvider.PLUGIN_NAME && it.version == StdBlockProvider.VERSION }
        if (present) return false
        publishPackage(core, packageBytes())
        core.repository().setPluginTrust(SetPluginTrustRequest.newBuilder().setName(StdBlockProvider.PLUGIN_NAME).setTrust(PluginTrust.PLUGIN_TRUST_TRUSTED).build())
        log.info("published the standard blocks {} {} and marked them trusted", StdBlockProvider.PLUGIN_NAME, StdBlockProvider.VERSION)
        return true
    }

    /** [install] that never throws: the repository may start after the management server, so it is tried again for about two minutes. */
    suspend fun installWhenReachable(attempts: Int = 40, pause: Long = 3000): Boolean {
        var last: Exception? = null
        repeat(attempts) {
            try {
                install()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                delay(pause)
            }
        }
        log.warn("the standard blocks were not published: {}", last?.message)
        return false
    }

    /** Runs [installWhenReachable] on a thread of its own. */
    fun startInBackground() {
        Thread({ runBlocking { installWhenReachable() } }, "std-blocks-installer").also { it.isDaemon = true }.start()
    }
}
