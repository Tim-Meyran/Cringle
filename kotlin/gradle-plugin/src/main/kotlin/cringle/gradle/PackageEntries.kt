// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import java.io.File

/**
 * The names the entries of a package get, the mapping from a file of the project to its entry in the ZIP
 * (`spec/package-format.md`, section 2). Both package kinds use them, so both builders use this.
 */
internal object PackageEntries {

    /**
     * The name of the `schemas/` entry for the schema file [name], which the project keeps directly below its schemas
     * folder. The reader refuses everything that is not a `.json` file directly below `schemas/`, so the name of the
     * file is the name of the entry.
     */
    fun schema(name: String): String = "schemas/$name"

    /**
     * The name of the `binaries/` entry for [file] below [root]. Binaries keep their folders, so the whole tree below
     * the binaries folder ends up in the package.
     */
    fun binary(root: File?, file: File): String {
        val relative = root?.toPath()?.relativize(file.toPath()) ?: file.toPath()
        return "binaries/" + relative.joinToString("/") { it.toString() }
    }
}
