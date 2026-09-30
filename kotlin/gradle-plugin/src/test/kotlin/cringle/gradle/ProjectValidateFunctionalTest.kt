// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.packaging.PackageFormatException
import cringle.packaging.PackageReader
import cringle.packaging.PackageWriter
import cringle.packaging.ProjectManifest
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * AC 3: `cringleValidate` fails the build for a fabric that names a blueprint the project does not have, a tether that
 * points at a block the blueprint does not have, an invalid version range in a dependency and a schema that the
 * manifest lists but the package does not carry. Every finding is on one line as `<path>: <message>`, with the text of
 * the `packaging` library.
 */
class ProjectValidateFunctionalTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun aFabricWithAnUnknownBlueprintFailsTheBuild() {
        val project = SampleProject.copyTo(temp, "fabric")
        SampleProject.replaceInBuildScript(project, """fabric("orders")""", """fabric("nope")""")

        val output = validate(project)

        assertTrue(
            "$.fabrics[0].blueprint: unknown blueprint 'nope'" in output,
            "the finding of the validator is missing in:\n$output",
        )
    }

    @Test
    fun aTetherToAnUnknownBlockFailsTheBuild() {
        val project = SampleProject.copyTo(temp, "tether")
        SampleProject.replaceIn(
            project,
            "src/main/cringle/blueprints/orders.json",
            """"from": { "block": "source"""",
            """"from": { "block": "ghost"""",
        )

        val output = validate(project)

        assertTrue(
            "blueprints/orders.json \$.tethers[0].from.block: unknown block id 'ghost'" in output,
            "the finding of the validator is missing in:\n$output",
        )
    }

    @Test
    fun anInvalidVersionRangeFailsTheBuild() {
        val project = SampleProject.copyTo(temp, "range")
        SampleProject.replaceInBuildScript(project, """"acme-orders", "^1.2.0"""", """"acme-orders", "1.0.0 or later"""")

        val output = validate(project)

        assertTrue(
            "$.dependencies.acme-orders: invalid range '1.0.0 or later' for 'acme-orders' required by the project"
                in output,
            "the finding of the version range parser is missing in:\n$output",
        )
    }

    @Test
    fun aSchemaListedInTheManifestButMissingFromThePackageFailsTheReader() {
        // The other three cases are Gradle builds against the sample. This one cannot be: a project derives the schema
        // list from the files it finds, so no build through the `cringle { }` block can list a schema it does not
        // carry. `cringleValidate` hands the assembled package to `PackageReader`, which is what rejects it, so the
        // test drives that reader with a manifest written by hand.
        val manifest = ProjectManifest(name = "acme-shop", version = "0.3.1", schemas = listOf("schemas/acme.shop.json"))
        val file = temp.resolve("listed-but-missing.cringle")
        Files.newOutputStream(file).use { PackageWriter.writeProject(manifest, emptyList(), emptyMap(), emptyMap(), it) }

        val e = assertThrows<PackageFormatException> { PackageReader.readProject(file) }

        assertEquals("schemas/acme.shop.json", e.path)
        assertTrue("listed in the manifest but missing from the package" in e.message.orEmpty(), e.message)
    }

    @Test
    fun theUnmodifiedSampleValidates() {
        val project = SampleProject.copyTo(temp, "valid")
        val result = SampleProject.runner(project, "cringleValidate").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":cringleValidate")?.outcome)
    }

    /** Runs `cringleValidate`, which has to fail, and returns everything the build printed. */
    private fun validate(project: Path): String {
        val result: GradleRunner = SampleProject.runner(project, "cringleValidate")
        val failed = result.buildAndFail()
        assertEquals(TaskOutcome.FAILED, failed.task(":cringleValidate")?.outcome)
        return failed.output
    }
}
