// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.packaging.Blueprint
import cringle.packaging.ManifestJson
import cringle.packaging.PackageFormatException
import cringle.packaging.PackageWriter
import cringle.packaging.ProjectManifest
import java.io.File
import java.io.OutputStream

/** Everything that goes into a project ZIP: the manifest, the blueprints and the two content folders. */
internal class ProjectPackageContent(
    val manifest: ProjectManifest,
    val blueprints: List<Blueprint>,
    val schemas: Map<String, String>,
    val binaries: Map<String, ByteArray>,
) {
    /** The name of the package file, for example `acme-shop-0.3.1.cringle`. */
    val fileName: String get() = "${manifest.name}-${manifest.version}.cringle"

    /** Writes the package through [PackageWriter], the only writer, which is deterministic. */
    fun write(out: OutputStream) {
        PackageWriter.writeProject(manifest, blueprints, schemas, binaries, out)
    }
}

/**
 * Turns the DSL and the collected files into a [ProjectPackageContent]. The manifest the project declares is encoded
 * and parsed again here, so that the build writes exactly the manifest that the runtime reads back.
 *
 * A project package carries no code, so there are no JARs: a plugin is what ships classes, and the deploy resolves
 * the dependencies of a project against the repository.
 */
internal object ProjectPackageBuilder {

    /**
     * Builds the content of a project package.
     *
     * @param manifestJson the manifest as `ManifestJson` encodes it, with `blueprints` and `schemas` still empty.
     * @param blueprintFiles the `*.json` files directly below the blueprints folder, they become `blueprints/` entries.
     * @param schemaFiles the `*.json` files directly below the schemas folder, they become `schemas/` entries.
     * @param binariesDir the binaries folder and root of the entry names, or `null` if there is none.
     * @param binaryFiles the content of [binariesDir], it becomes the `binaries/` entries.
     */
    fun build(
        manifestJson: String,
        blueprintFiles: List<File>,
        schemaFiles: List<File>,
        binariesDir: File?,
        binaryFiles: List<File>,
    ): ProjectPackageContent {
        val declared = ManifestJson.parseProject(manifestJson)
        val blueprints = blueprintFiles.sortedBy { it.name }.map { file ->
            ManifestJson.parseBlueprint(file.readText(), "blueprints/${file.name}")
        }
        val entries = blueprints.map { "blueprints/${it.name}.json" }
        entries.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let { (entry, count) ->
            throw PackageFormatException(entry, "$count blueprint files declare this blueprint name")
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
            blueprints = entries,
            schemas = schemas.keys.sorted(),
        )
        return ProjectPackageContent(manifest, blueprints, schemas, binaries)
    }
}
