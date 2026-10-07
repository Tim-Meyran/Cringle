// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * What the CLI knows about the distribution it was started from (#57). The start scripts of a distribution set the
 * system property `cringle.home` to its directory, which holds the file `VERSION` with the version of the release. A
 * CLI that was not started from a distribution (from the build, from a test) has no such directory and reports the
 * version of local builds.
 */
internal object Distribution {
    /** The version local builds report, see `releaseVersion` in the root `build.gradle.kts`. */
    const val LOCAL_VERSION: String = "0.0.0-SNAPSHOT"

    /** The system property the start scripts set to the directory of the distribution. */
    const val HOME_PROPERTY: String = "cringle.home"

    /** The empty file that the Windows MSI installs into `cringle.home`: such an installation is updated with a newer MSI, not by `self-update`. */
    const val MSI_MARKER: String = "installed-by-msi"

    /** The version of the distribution in [home], or [LOCAL_VERSION] if there is none or its `VERSION` cannot be read. */
    fun version(home: String? = System.getProperty(HOME_PROPERTY)): String {
        if (home.isNullOrBlank()) return LOCAL_VERSION
        return try {
            Files.readString(Path.of(home, "VERSION")).trim().ifEmpty { LOCAL_VERSION }
        } catch (_: IOException) {
            LOCAL_VERSION
        }
    }
}
