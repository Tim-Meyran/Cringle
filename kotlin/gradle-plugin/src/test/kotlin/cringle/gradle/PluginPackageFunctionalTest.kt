// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.packaging.PackageProblem
import cringle.packaging.PackageReader
import cringle.packaging.PackageValidator
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * AC 1: the sample applies `cringle.plugin`, `cringlePackage` succeeds, and what it writes is a plugin package that
 * the runtime reads and accepts.
 */
@Tag("integration")
class PluginPackageFunctionalTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun theSampleBuildsAPluginPackageTheRuntimeAccepts() {
        val project = SamplePlugin.copyTo(temp, "first")
        val result = SamplePlugin.runner(project, "cringlePackage").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePackage")?.outcome)
        val packageFile = project.resolve("build/distributions/acme-orders-1.2.0.cringle")
        assertTrue(Files.isRegularFile(packageFile), "$packageFile was not written")

        val plugin = PackageReader.readPlugin(packageFile)
        assertEquals(emptyList<PackageProblem>(), PackageValidator.validatePlugin(plugin))
        assertEquals("acme-orders", plugin.manifest.name)
        assertEquals("1.2.0", plugin.manifest.version)
        assertEquals(mapOf("acme-core" to "^1.0.0"), plugin.manifest.dependencies)
        assertEquals(listOf("acme.orders.OrdersProvider"), plugin.manifest.providers)
        assertEquals(listOf("acme.orders.OrdersDriver"), plugin.manifest.drivers)
        assertEquals(listOf("orders"), plugin.manifest.blocks.map { it.name })
        assertEquals(listOf("schemas/acme.orders.json"), plugin.manifest.schemas)
        assertEquals(
            listOf("binaries/acme/orders/readme.txt"),
            plugin.files.filter { it.startsWith("binaries/") },
        )
    }

    @Test
    fun libContainsTheProjectJarAndNothingTheParentClassloaderProvides() {
        val project = SamplePlugin.copyTo(temp, "libs")
        SamplePlugin.runner(project, "cringlePackage").build()

        val libs = PackageReader.readPlugin(project.resolve("build/distributions/acme-orders-1.2.0.cringle"))
            .manifest.libs
        assertTrue(
            libs.contains("lib/acme-orders-plugin-1.2.0.jar"),
            "lib/ has to contain the JAR of the project, but has $libs",
        )
        assertTrue(
            libs.contains("lib/acme-orders-support.jar"),
            "lib/ has to contain the dependency of the project, but has $libs",
        )
        val providedByParentClassloader = listOf(
            "contract",
            "kotlin-stdlib",
            "kotlinx-coroutines",
            "kotlinx-serialization",
            "gradle-api",
            "localGroovy",
        )
        for (forbidden in providedByParentClassloader) {
            assertTrue(libs.none { "/$forbidden" in it }, "lib/ must not contain '$forbidden', but has $libs")
        }
    }
}
