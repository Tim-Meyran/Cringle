// SPDX-License-Identifier: Apache-2.0

package cringle.cli

/** The platform the updater runs on. */
internal enum class Platform {
    LINUX, WINDOWS;

    /** The name of the release archive for [version] on this platform. */
    fun archiveName(version: String): String = when (this) {
        LINUX -> "cringle-$version-linux.tar.gz"
        WINDOWS -> "cringle-$version-windows.zip"
    }

    companion object {
        fun current(): Platform = if (System.getProperty("os.name").lowercase().contains("win")) WINDOWS else LINUX
    }
}
