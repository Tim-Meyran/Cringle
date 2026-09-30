// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.packaging.ManifestJson
import cringle.packaging.PackageFormatException
import cringle.packaging.PackageWriter
import cringle.packaging.PluginManifest
import java.io.File
import java.io.OutputStream

/** Everything that goes into a plugin ZIP: the manifest plus the three content folders. */
internal class PluginPackageContent(
    val manifest: PluginManifest,
    val schemas: Map<String, String>,
    val libs: Map<String, ByteArray>,
    val binaries: Map<String, ByteArray>,
) {
    /** The name of the package file, for example `acme-orders-1.2.0.cringle`. */
    val fileName: String get() = "${manifest.name}-${manifest.version}.cringle"

    /** Writes the package through [PackageWriter], the only writer, which is deterministic. */
    fun write(out: OutputStream) {
        PackageWriter.writePlugin(manifest, schemas, libs, binaries, out)
    }
}

/**
 * Turns the DSL and the collected files into a [PluginPackageContent]. The manifest the plugin declares is encoded
 * and parsed again here, so that the build writes exactly the manifest that the runtime reads back.
 */
internal object PluginPackageBuilder {

    /**
     * Builds the content of a plugin package.
     *
     * @param manifestJson the manifest as `ManifestJson` encodes it, with `libs` and `schemas` still empty.
     * @param projectJar the JAR of the project itself; it becomes the first entry of `lib/`.
     * @param runtimeJars the JARs of the runtime classpath, already reduced to what the plugin may ship.
     * @param schemaFiles the `*.json` files directly below the schemas folder, they become `schemas/` entries.
     * @param binariesDir the binaries folder and root of the entry names, or `null` if there is none.
     * @param binaryFiles the content of [binariesDir], it becomes the `binaries/` entries.
     */
    fun build(
        manifestJson: String,
        projectJar: File,
        runtimeJars: List<File>,
        schemaFiles: List<File>,
        binariesDir: File?,
        binaryFiles: List<File>,
    ): PluginPackageContent {
        val declared = ManifestJson.parsePlugin(manifestJson)
        val libs = LinkedHashMap<String, ByteArray>()
        for (jar in listOf(projectJar) + runtimeJars) {
            val entry = "lib/${jar.name}"
            if (libs.put(entry, jar.readBytes()) != null) {
                throw PackageFormatException(entry, "two JARs of the runtime classpath have the same file name")
            }
        }
        val schemas = LinkedHashMap<String, String>()
        for (file in schemaFiles.sortedBy { it.name }) {
            schemas[PackageEntries.schema(file.name)] = file.readText()
        }
        val binaries = LinkedHashMap<String, ByteArray>()
        for (file in binaryFiles.sortedBy { it.path }) {
            binaries[PackageEntries.binary(binariesDir, file)] = file.readBytes()
        }
        val manifest = declared.copy(
            libs = libs.keys.sorted(),
            schemas = schemas.keys.sorted(),
        )
        return PluginPackageContent(manifest, schemas, libs, binaries)
    }
}
