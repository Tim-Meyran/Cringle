// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.BufferedOutputStream

/**
 * Writes the plugin package: `cringle-plugin.json`, the JARs of `lib/`, the schema documents and the binaries. The
 * file is called `<name>-<version>.cringle`, the shape the repository stores packages in.
 *
 * The ZIP is written through `PackageWriter` and not through a Gradle `Zip` task, because `PackageWriter` is what
 * the runtime reads back and it is deterministic on its own. Together with the reproducibly built JARs of the
 * project, two builds of the same sources produce the same bytes.
 */
public abstract class PackagePluginTask : CringlePluginPackageTask() {

    /** The package to write, `<project>/build/distributions/<name>-<version>.cringle`. */
    @get:OutputFile
    public abstract val packageFile: RegularFileProperty

    @TaskAction
    internal fun writePackage() {
        val content = assemble()
        val file = packageFile.get().asFile
        file.parentFile.mkdirs()
        file.outputStream().use { out -> BufferedOutputStream(out).use { content.write(it) } }
        logger.info("wrote plugin package {}", file)
    }
}
