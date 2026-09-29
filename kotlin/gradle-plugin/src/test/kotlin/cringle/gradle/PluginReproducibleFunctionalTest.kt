// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * AC 3: the same sources built twice, the second time in a copy of the project in another directory, produce
 * byte-identical packages.
 */
class PluginReproducibleFunctionalTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun twoCleanBuildsInDifferentDirectoriesProduceTheSameBytes() {
        val first = SamplePlugin.copyTo(temp.resolve("here"), "one")
        val second = SamplePlugin.copyTo(temp.resolve("elsewhere").resolve("deeply").resolve("nested"), "two")

        SamplePlugin.runner(first, "cringlePackage").build()
        SamplePlugin.runner(second, "cringlePackage").build()

        val a = packageOf(first)
        val b = packageOf(second)
        assertEquals(sha256(a), sha256(b), "the two packages are not byte-identical")
    }

    private fun packageOf(project: Path): ByteArray {
        val file = project.resolve("build/distributions/acme-orders-1.2.0.cringle")
        check(Files.isRegularFile(file)) { "$file was not written" }
        return Files.readAllBytes(file)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
