// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.contract.TetherType
import cringle.packaging.FabricConfig
import cringle.packaging.PackageProblem
import cringle.packaging.PackageReader
import cringle.packaging.PackageValidator
import kotlinx.serialization.json.JsonObject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * AC 1: the sample project applies `cringle.project`, `cringlePackage` succeeds, and what it writes is a project
 * package that the runtime reads and accepts.
 */
class ProjectPackageFunctionalTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun theSampleBuildsAProjectPackageTheRuntimeAccepts() {
        val project = SampleProject.copyTo(temp, "first")
        val result = SampleProject.runner(project, "cringlePackage").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePackage")?.outcome)
        val packageFile = project.resolve("build/distributions/acme-shop-0.3.1.cringle")
        assertTrue(Files.isRegularFile(packageFile), "$packageFile was not written")

        val pkg = PackageReader.readProject(packageFile)
        assertEquals(emptyList<PackageProblem>(), PackageValidator.validateProjectSources(pkg))
        assertEquals("acme-shop", pkg.manifest.name)
        assertEquals("0.3.1", pkg.manifest.version)
        assertEquals(mapOf("acme-orders" to "^1.2.0"), pkg.manifest.dependencies)
        assertEquals(listOf("blueprints/orders.json"), pkg.manifest.blueprints)
        assertEquals(listOf("schemas/acme.shop.json"), pkg.manifest.schemas)
        assertEquals(listOf(FabricConfig("orders", 2, listOf("edge"), mapOf("zone" to "a"))), pkg.manifest.fabrics)

        assertEquals(listOf("orders"), pkg.blueprints.map { it.name })
        assertEquals(listOf("source", "sink"), pkg.blueprints.single().blocks.map { it.id })
        assertEquals(
            listOf("acme-orders/orders", "acme-orders/orders"),
            pkg.blueprints.single().blocks.map { it.block },
        )
        assertEquals("3", pkg.blueprints.single().blocks.single { it.id == "source" }.config["retries"]?.toString())
        assertEquals("3", pkg.blueprints.single().blocks.single { it.id == "sink" }.config["retries"]?.toString())
        assertEquals("source", pkg.blueprints.single().tethers.single().from.block)
        assertEquals("out", pkg.blueprints.single().tethers.single().from.port)
        assertEquals("sink", pkg.blueprints.single().tethers.single().to.block)
        assertEquals("in", pkg.blueprints.single().tethers.single().to.port)
        assertEquals(TetherType.REQUEST_RESPONSE, pkg.blueprints.single().tethers.single().type)
        assertTrue("acme.shop" in pkg.schemas.getValue("schemas/acme.shop.json"), "the schema of the project is missing")
        assertEquals(
            listOf("binaries/acme/shop/readme.txt"),
            pkg.files.filter { it.startsWith("binaries/") },
        )
    }

    @Test
    fun aProjectPackageCarriesNoJars() {
        // Only a plugin ships code, so a project package has no `lib/` and applying the plugin adds no dependency.
        val project = SampleProject.copyTo(temp, "no-lib")
        SampleProject.runner(project, "cringlePackage").build()

        val pkg = PackageReader.readProject(project.resolve("build/distributions/acme-shop-0.3.1.cringle"))
        assertFalse(pkg.files.any { it.startsWith("lib/") }, "a project package must not carry JARs, but has ${pkg.files}")
    }
}
