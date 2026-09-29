// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.packaging.ManifestJson
import cringle.packaging.PackageFormatException
import cringle.packaging.PackageProblem
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
            schemas["schemas/${file.name}"] = file.readText()
        }
        val binaries = LinkedHashMap<String, ByteArray>()
        for (file in binaryFiles.sortedBy { it.path }) {
            binaries[entryNameOf(binariesDir, file)] = file.readBytes()
        }
        val manifest = declared.copy(
            libs = libs.keys.sorted(),
            schemas = schemas.keys.sorted(),
        )
        return PluginPackageContent(manifest, schemas, libs, binaries)
    }

    /** A problem of the `packaging` library, rendered the way the runtime renders it. */
    fun render(problem: PackageProblem): String = "${problem.path}: ${problem.message}"

    /**
     * A format error of the `packaging` library, rendered the same way. Its message already starts with the path,
     * so nothing has to be added.
     */
    fun render(problem: PackageFormatException): String {
        val text = problem.message.orEmpty()
        return if (text.startsWith("${problem.path}: ")) text else "${problem.path}: $text"
    }

    private fun entryNameOf(root: File?, file: File): String {
        val relative = root?.toPath()?.relativize(file.toPath()) ?: file.toPath()
        return "binaries/" + relative.joinToString("/") { it.toString() }
    }
}
