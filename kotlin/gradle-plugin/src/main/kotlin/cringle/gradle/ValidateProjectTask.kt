// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.packaging.PackageFormatException
import cringle.packaging.PackageProblem
import cringle.packaging.PackageReader
import cringle.packaging.PackageValidator
import cringle.packaging.VersionRange
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

/**
 * Checks the declared project package without writing it: the manifest, the blueprints and the schema documents are
 * read back exactly as the runtime reads them, and every problem is reported as `<path>: <message>` with the wording
 * of the `packaging` library.
 *
 * What is checked is what the project says about itself. Its dependencies are named with a version range and resolved
 * at deploy time, so the block definitions they contribute are not known here; everything that needs them is checked
 * when the project is deployed.
 */
public abstract class ValidateProjectTask : CringleProjectPackageTask() {

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
    private fun checkWithReader(content: ProjectPackageContent, problems: MutableList<String>) {
        val file = Files.createTempFile("cringle-validate", ".cringle")
        try {
            Files.newOutputStream(file).use { content.write(it) }
            val pkg = try {
                PackageReader.readProject(file)
            } catch (e: PackageFormatException) {
                problems += ProblemRender.text(e)
                null
            }
            if (pkg != null) {
                problems += PackageValidator.validateProjectSources(pkg).map { ProblemRender.text(it) }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /**
     * The `packaging` library keeps the dependency ranges of a manifest opaque and parses them in the resolver, so
     * they are checked here with the same parser the runtime uses and in the same wording: the runtime reports an
     * unparsable range of a project as it reports one of the root of a resolution.
     */
    private fun invalidRanges(content: ProjectPackageContent): List<String> =
        content.manifest.dependencies.mapNotNull { (name, range) ->
            try {
                VersionRange.parse(range)
                null
            } catch (e: IllegalArgumentException) {
                val message = "invalid range '$range' for '$name' required by the project: ${e.message}"
                ProblemRender.text(PackageProblem("$.dependencies.$name", message))
            }
        }
}
