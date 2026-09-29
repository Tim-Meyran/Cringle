// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.packaging.PackageFormatException
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

/**
 * The inputs `cringlePackage` and `cringleValidate` share. Both turn the same declared metadata and the same files
 * into the same package, so a package that validates is exactly the package that `cringlePackage` writes.
 */
public abstract class CringlePluginPackageTask : DefaultTask() {

    /** The manifest as `ManifestJson` encodes it, with `libs` and `schemas` still empty. */
    @get:Input
    public abstract val manifestJson: Property<String>

    /** The JAR of the project itself; it becomes the first entry of `lib/`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val projectJar: RegularFileProperty

    /** The JARs of the runtime classpath that the plugin may ship; the parent classloader provides the rest. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val runtimeJars: ConfigurableFileCollection

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
     * @throws GradleException with the `<path>: <message>` text of the `packaging` library if the declared metadata
     *   is not a valid manifest, for example because the name or the version does not match the format.
     */
    internal fun assemble(): PluginPackageContent = try {
        PluginPackageBuilder.build(
            manifestJson = manifestJson.get(),
            projectJar = projectJar.get().asFile,
            runtimeJars = runtimeJars.files.sortedBy { it.name },
            schemaFiles = schemaFiles.files.sortedBy { it.name },
            binariesDir = binariesDir.orNull?.asFile,
            binaryFiles = binaryFiles.files.sortedBy { it.path },
        )
    } catch (e: PackageFormatException) {
        throw GradleException(PluginPackageBuilder.render(e), e)
    }
}
