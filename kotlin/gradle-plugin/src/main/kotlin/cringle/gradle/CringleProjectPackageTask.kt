// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.packaging.PackageFormatException
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

/**
 * The inputs `cringlePackage` and `cringleValidate` share. Both turn the same declared metadata and the same files
 * into the same package, so a package that validates is exactly the package that `cringlePackage` writes.
 */
public abstract class CringleProjectPackageTask : DefaultTask() {

    /** The manifest as `ManifestJson` encodes it, with `blueprints` and `schemas` still empty. */
    @get:Input
    public abstract val manifestJson: Property<String>

    /** The `*.json` files directly below the blueprints folder. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val blueprintFiles: ConfigurableFileCollection

    /** The `*.json` files directly below the schemas folder. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val schemaFiles: ConfigurableFileCollection

    /** The binaries folder. It is not an input itself, only the root of the entry names in [binaryFiles]. */
    @get:Internal
    public abstract val binariesDir: DirectoryProperty

    /** The content of [binariesDir]. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val binaryFiles: ConfigurableFileCollection

    /**
     * Assembles the package. The files are sorted by name, so a build does not depend on the order in which Gradle
     * hands out the entries of a configuration.
     *
     * @throws GradleException with the `<path>: <message>` text of the `packaging` library if the declared metadata or
     *   a blueprint document is not valid, for example because the name or the version does not match the format.
     */
    internal fun assemble(): ProjectPackageContent = try {
        ProjectPackageBuilder.build(
            manifestJson = manifestJson.get(),
            blueprintFiles = blueprintFiles.files.sortedBy { it.name },
            schemaFiles = schemaFiles.files.sortedBy { it.name },
            binariesDir = binariesDir.orNull?.asFile,
            binaryFiles = binaryFiles.files.sortedBy { it.path },
        )
    } catch (e: PackageFormatException) {
        throw GradleException(ProblemRender.text(e), e)
    }
}
