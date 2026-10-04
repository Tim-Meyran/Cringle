// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The task graph of `cringle.plugin` and `cringle.project` (#64): `cringlePackage` validates first and writes nothing
 * for an invalid package, and `cringlePublish` depends on `cringlePackage` alone (validation reaches it through the
 * package task).
 */
class PackageValidatesFirstFunctionalTest {

    @TempDir
    lateinit var temp: Path

    /** The direct dependencies of two tasks, printed by a task the test adds to the build script of a copy. */
    private val printDependencies = """

        tasks.register("printDependencies") {
            doLast {
                fun direct(name: String) = tasks.getByName(name).taskDependencies.getDependencies(null).map { it.name }.sorted()
                println("publish-depends-on=" + direct("cringlePublish"))
                println("package-depends-on=" + direct("cringlePackage"))
            }
        }
    """.trimIndent()

    @Test
    fun aProjectPackageIsValidatedFirstAndNotWrittenWhenInvalid() {
        val project = SampleProject.copyTo(temp, "project-invalid")
        SampleProject.replaceIn(project, "src/main/cringle/blueprints/orders.json", """tethers": []""", """tethers": [ { "type": "MESSAGE", "from": { "block": "ghost", "port": "out" }, "to": { "block": "sink", "port": "in" } } ]""")

        val result = SampleProject.runner(project, "cringlePackage").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":cringleValidate")?.outcome, result.output)
        assertNull(result.task(":cringlePackage"), "cringlePackage must not run after a failed validation:\n${result.output}")
        assertFalse(Files.exists(project.resolve("build/distributions/acme-shop-0.3.1.cringle")), "an invalid package must not be written")
    }

    @Test
    fun aPluginPackageIsValidatedFirstAndNotWrittenWhenInvalid() {
        val project = SamplePlugin.copyTo(temp, "plugin-invalid")
        SamplePlugin.replaceInBuildScript(project, """name = "acme-orders"""", """name = "Acme Orders"""")

        val result = SamplePlugin.runner(project, "cringlePackage").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":cringleValidate")?.outcome, result.output)
        assertNull(result.task(":cringlePackage"), "cringlePackage must not run after a failed validation:\n${result.output}")
        assertFalse(Files.exists(project.resolve("build/distributions")) && Files.list(project.resolve("build/distributions")).use { s -> s.findAny().isPresent }, "an invalid package must not be written")
    }

    @Test
    fun validationRunsBeforePackagingInBothPlugins() {
        val project = SampleProject.copyTo(temp, "project-valid")
        val plugin = SamplePlugin.copyTo(temp, "plugin-valid")
        for ((result, file) in listOf(
            SampleProject.runner(project, "cringlePackage").build() to project.resolve("build/distributions/acme-shop-0.3.1.cringle"),
            SamplePlugin.runner(plugin, "cringlePackage").build() to plugin.resolve("build/distributions/acme-orders-1.2.0.cringle"),
        )) {
            val order = result.tasks.map { it.path }
            assertTrue(":cringleValidate" in order && ":cringlePackage" in order, order.toString())
            assertTrue(order.indexOf(":cringleValidate") < order.indexOf(":cringlePackage"), "validate has to run first: $order")
            assertEquals(TaskOutcome.SUCCESS, result.task(":cringleValidate")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePackage")?.outcome)
            assertTrue(Files.isRegularFile(file), "$file was not written")
        }
    }

    @Test
    fun publishDependsOnPackageAloneInBothPlugins() {
        val project = SampleProject.copyTo(temp, "project-graph")
        val plugin = SamplePlugin.copyTo(temp, "plugin-graph")
        append(project, printDependencies)
        append(plugin, printDependencies)

        for (runner in listOf<GradleRunner>(SampleProject.runner(project, "printDependencies", "-q"), SamplePlugin.runner(plugin, "printDependencies", "-q"))) {
            val lines = runner.build().output.lines().map { it.trim() }
            assertEquals("publish-depends-on=[cringlePackage]", lines.single { it.startsWith("publish-depends-on=") })
            val packageDependencies = lines.single { it.startsWith("package-depends-on=") }
            assertTrue("cringleValidate" in packageDependencies, "cringlePackage has to depend on cringleValidate: $packageDependencies")
        }
    }

    private fun append(project: Path, text: String) {
        Files.writeString(project.resolve("build.gradle.kts"), Files.readString(project.resolve("build.gradle.kts")) + "\n" + text + "\n")
    }
}
