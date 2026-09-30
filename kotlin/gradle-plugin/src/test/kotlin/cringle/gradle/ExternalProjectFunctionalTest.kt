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
 * AC 6: a project outside this build gets the Cringle Gradle plugin from Maven Local and builds a project package
 * with it.
 *
 * The example project `samples/external-project-example` is a plain Gradle project: it applies `cringle.project` and
 * never sees the plugin classpath of this build, TestKit does not inject anything here. The nested build runs with
 * `--offline` and with the Gradle cache of this build, and Maven Local is the folder `publishToTestMavenLocal` has
 * written, which the runner passes as `-Dmaven.repo.local`. Nothing can be downloaded, so the plugin can only come
 * from that folder, and no test of this build ever writes to the real `~/.m2`.
 */
class ExternalProjectFunctionalTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun anExternalProjectBuildsAProjectPackageWithThePluginFromMavenLocal() {
        val project = copyExample()
        val result = runner(project, "cringlePackage").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePackage")?.outcome)
        val packageFile = project.resolve("build/distributions/acme-shop-0.3.1.cringle")
        assertTrue(Files.isRegularFile(packageFile), "$packageFile was not written")

        // A package is a ZIP. Reading it with the packaging module is what the runtime does with it later on.
        assertArrayEquals(
            byteArrayOf(0x50, 0x4B, 0x03, 0x04),
            Files.newInputStream(packageFile).use { it.readNBytes(4) },
            "$packageFile is not a ZIP",
        )
        val pkg = PackageReader.readProject(packageFile)
        assertEquals(emptyList<PackageProblem>(), PackageValidator.validateProjectSources(pkg))
        assertEquals("acme-shop", pkg.manifest.name)
        assertEquals("0.3.1", pkg.manifest.version)
        assertEquals(mapOf("acme-orders" to "^1.2.0"), pkg.manifest.dependencies)
        assertEquals(listOf("blueprints/orders.json"), pkg.manifest.blueprints)
        assertEquals(listOf("schemas/acme.shop.json"), pkg.manifest.schemas)
        assertEquals(listOf("orders"), pkg.blueprints.map { it.name })
    }

    /**
     * Copies the example into a directory of its own, so the test never sees the build output of a build somebody ran
     * on the example by hand, and pins the version of the plugin to the one of this build.
     */
    private fun copyExample(): Path {
        val source = Path.of(System.getProperty("cringle.externalProjectExampleDir", "samples/external-project-example"))
        val target = temp.resolve("external-project-example")
        Files.walk(source).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { file ->
                val into = target.resolve(source.relativize(file).toString())
                into.parent.createDirectories()
                file.copyTo(into)
            }
        }
        val settings = target.resolve("settings.gradle.kts")
        val text = settings.readText()
        val marker = """id("cringle.project") version """
        val at = text.indexOf(marker)
        check(at >= 0) { "'$marker' is not in the settings of the example" }
        // The version is the text between the quotes, so the search starts behind the opening one and ends at the
        // closing one, which both stay where they are.
        val from = at + marker.length + 1
        val until = text.indexOf('"', from)
        check(until > from) { "no version behind '$marker' in the settings of the example" }
        settings.writeText(text.substring(0, from) + version + text.substring(until))
        return target
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

        /** The Gradle cache of this build. */
        val gradleUserHome: String = required("cringle.gradleUserHome")
    }
}
