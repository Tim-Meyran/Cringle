// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.packaging.PackageFormatException
import cringle.packaging.PackageProblem
import cringle.packaging.PackageReader
import cringle.packaging.VersionRange
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

/**
 * Checks the declared plugin package without writing it: the manifest, the block definitions and the schema
 * documents are read back exactly as the runtime reads them, and every problem is reported as `<path>: <message>`
 * with the wording of the `packaging` library.
 *
 * The package is checked against the plugins it depends on, which the repository of `cringlePublish` (same address and
 * token) provides, as the repository does when the package is published. Without a repository the check goes on with a
 * warning, see [PluginValidation].
 */
public abstract class ValidatePluginTask : CringlePluginPackageTask() {

    /** The address of the repository as `host:port`; without it the environment and the profile are asked. */
    @get:Input
    @get:Optional
    public abstract val server: Property<String>

    /** The fingerprint of the key of the repository; without it the environment and the profile are asked. */
    @get:Input
    @get:Optional
    public abstract val fingerprint: Property<String>

    @TaskAction
    internal fun validate() {
        val problems = ArrayList<String>()
        val content = try {
            assemble()
        } catch (e: GradleException) {
            problems += e.message.orEmpty()
            null
        }
        if (content != null) {
            checkWithReader(content, problems)
            problems += invalidRanges(content)
        }
        if (problems.isEmpty()) {
            logger.info("cringleValidate: {} is valid", content?.manifest?.name)
            return
        }
        problems.forEach { logger.error("cringleValidate: {}", it) }
        throw GradleException("cringleValidate found ${problems.size} problem(s):\n" + problems.joinToString("\n"))
    }

    /**
     * Writes the package to a temporary file and reads it back, so that the reader sees exactly what `cringlePackage`
     * would write. A package that passes here is a package the repository would accept.
     */
    private fun checkWithReader(content: PluginPackageContent, problems: MutableList<String>) {
        val file = Files.createTempFile("cringle-validate", ".cringle")
        try {
            Files.newOutputStream(file).use { content.write(it) }
            val pkg = try {
                PackageReader.readPlugin(file)
            } catch (e: PackageFormatException) {
                problems += ProblemRender.text(e)
                null
            }
            if (pkg != null) {
                val result = PluginValidation.validate(pkg, server.orNull, fingerprint.orNull)
                result.warnings.forEach { logger.warn(it) }
                problems += result.problems.map { ProblemRender.text(it) }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /**
     * The `packaging` library keeps the dependency ranges of a manifest opaque and parses them in the resolver, so
     * they are checked here with the same parser the runtime uses and in the same wording: the runtime reports an
     * unparsable range of a plugin as it reports one of the root of a resolution.
     */
    private fun invalidRanges(content: PluginPackageContent): List<String> =
        content.manifest.dependencies.mapNotNull { (name, range) ->
            try {
                VersionRange.parse(range)
                null
            } catch (e: IllegalArgumentException) {
                val message = "invalid range '$range' for '$name' required by the plugin: ${e.message}"
                ProblemRender.text(PackageProblem("$.dependencies.$name", message))
            }
        }
}
