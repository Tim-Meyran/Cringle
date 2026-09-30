// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.packaging.PackageProblem
import cringle.packaging.PackageReader
import cringle.packaging.PackageValidator
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * AC 2: a project outside this build gets the Cringle Gradle plugin from Maven Local and builds a plugin package with
 * it.
 *
 * The example project `samples/external-plugin-example` is a plain Gradle project: it applies `cringle.plugin` and
 * never sees the plugin classpath of this build, TestKit does not inject anything here. The nested build runs with
 * `--offline` and with the Gradle cache of this build, and Maven Local is the folder `publishToTestMavenLocal` has
 * written, which the runner passes as `-Dmaven.repo.local`. Nothing can be downloaded, so the plugin can only come
 * from that folder.
 */
class ExternalPluginFunctionalTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun anExternalProjectBuildsAPluginPackageWithThePluginFromMavenLocal() {
        val project = copyExample()
        val result = runner(project, "cringlePackage").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePackage")?.outcome)
        val packageFile = project.resolve("build/distributions/acme-orders-1.2.0.cringle")
        assertTrue(Files.isRegularFile(packageFile), "$packageFile was not written")

        // A package is a ZIP. Reading it with the packaging module is what the runtime does with it later on.
        assertArrayEquals(
            byteArrayOf(0x50, 0x4B, 0x03, 0x04),
            Files.newInputStream(packageFile).use { it.readNBytes(4) },
            "$packageFile is not a ZIP",
        )
        val plugin = PackageReader.readPlugin(packageFile)
        assertEquals(emptyList<PackageProblem>(), PackageValidator.validatePlugin(plugin))
        assertEquals("acme-orders", plugin.manifest.name)
        assertEquals("1.2.0", plugin.manifest.version)
        assertEquals(listOf("acme.orders.OrdersProvider"), plugin.manifest.providers)
        assertEquals(listOf("acme.orders.OrdersDriver"), plugin.manifest.drivers)
        assertEquals(listOf("orders"), plugin.manifest.blocks.map { it.name })
    }

    /**
     * Copies the example into a directory of its own, so the test never sees the build output of a build somebody
     * ran on the example by hand, and pins the two versions of the example to the ones of this build.
     */
    private fun copyExample(): Path {
        val source = Path.of(System.getProperty("cringle.externalExampleDir", "samples/external-plugin-example"))
        val target = temp.resolve("external-plugin-example")
        Files.walk(source).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { file ->
                val into = target.resolve(source.relativize(file).toString())
                into.parent.createDirectories()
                file.copyTo(into)
            }
        }
        pinVersion(target.resolve("settings.gradle.kts"), """id("cringle.plugin") version """, version)
        pinVersion(target.resolve("build.gradle.kts"), """kotlin("jvm") version """, kotlinVersion)
        pinVersion(target.resolve("build.gradle.kts"), """compileOnly("cringle:contract:""", version)
        return target
    }

    /** Writes [value] into [file] as the version behind [marker], which may be a string of its own or part of one. */
    private fun pinVersion(file: Path, marker: String, value: String) {
        val text = file.readText()
        val at = text.indexOf(marker)
        check(at >= 0) { "'$marker' is not in ${file.fileName} of the example" }
        val start = at + marker.length
        val opening = if (text[start] == '"') "\"" else ""
        val end = text.indexOf('"', start + opening.length)
        check(end > start) { "no version behind '$marker' in ${file.fileName} of the example" }
        file.writeText(text.substring(0, start) + opening + value + text.substring(end))
    }

    /**
     * A runner for the copy in [project]. There is no plugin classpath to inject: the plugin comes from the folder
     * that is passed as `-Dmaven.repo.local`.
     */
    private fun runner(project: Path, vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(project.toFile())
        .forwardOutput()
        .withArguments(
            *arguments,
            "-Dmaven.repo.local=$testMavenLocal",
            "-g",
            gradleUserHome,
            // This build has resolved every module of the nested build into this cache before the test starts, so the
            // nested build needs no network and cannot download the plugin either.
            "--offline",
            "--stacktrace",
        )

    private companion object {

        private fun required(name: String): String =
            checkNotNull(System.getProperty(name)) { "the system property '$name' is not set" }

        /** The folder `publishToTestMavenLocal` has written, which is Maven Local for the nested build. */
        val testMavenLocal: String = required("cringle.testMavenLocal")

        /** The version the plugin and the libraries of this build are published under. */
        val version: String = required("cringle.version")

        /** The Kotlin version of this build, so the nested build resolves the Kotlin plugin from the same cache. */
        val kotlinVersion: String = required("cringle.kotlinVersion")

        /** The Gradle cache of this build. */
        val gradleUserHome: String = required("cringle.gradleUserHome")
    }
}
