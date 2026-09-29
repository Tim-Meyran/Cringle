// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * AC 2: `cringleValidate` fails the build for an invalid plugin name, an invalid version range in a dependency and a
 * schema that a block lists but that is not in the package. Every finding is on one line as `<path>: <message>`, with
 * the text of the `packaging` library.
 */
class PluginValidateFunctionalTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun anInvalidPluginNameFailsTheBuild() {
        val project = SamplePlugin.copyTo(temp, "name")
        SamplePlugin.replaceInBuildScript(project, """name = "acme-orders"""", """name = "Acme Orders"""")

        val output = validate(project)

        assertTrue(
            "cringle-plugin.json \$.name: invalid name 'Acme Orders':" in output,
            "the finding of the manifest parser is missing in:\n$output",
        )
    }

    @Test
    fun anInvalidVersionRangeFailsTheBuild() {
        val project = SamplePlugin.copyTo(temp, "range")
        SamplePlugin.replaceInBuildScript(project, """"acme-core", "^1.0.0"""", """"acme-core", "1.0.0 or later"""")

        val output = validate(project)

        assertTrue(
            "$.dependencies.acme-core: invalid range '1.0.0 or later' for 'acme-core'" in output,
            "the finding of the version range parser is missing in:\n$output",
        )
    }

    @Test
    fun aListedButMissingSchemaFailsTheBuildAndNamesEveryReference() {
        val project = SamplePlugin.copyTo(temp, "schema")
        SamplePlugin.delete(project, "src/main/cringle/schemas/acme.orders.json")

        val output = validate(project)

        for (finding in listOf(
            "$.blocks[0].schemas[0]: schema 'acme.orders/Order' does not resolve",
            "$.blocks[0].configSchema: schema 'acme.orders/OrdersConfig' does not resolve",
            "$.blocks[0].ports[0].schema: schema 'acme.orders/Order' does not resolve",
            "$.blocks[0].ports[1].schema: schema 'acme.orders/Order' does not resolve",
        )) {
            assertTrue(finding in output, "'$finding' is missing in:\n$output")
        }
    }

    @Test
    fun theUnmodifiedSampleValidates() {
        val project = SamplePlugin.copyTo(temp, "valid")
        val result = SamplePlugin.runner(project, "cringleValidate").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":cringleValidate")?.outcome)
    }

    /** Runs `cringleValidate`, which has to fail, and returns everything the build printed. */
    private fun validate(project: Path): String {
        val result: GradleRunner = SamplePlugin.runner(project, "cringleValidate")
        val failed = result.buildAndFail()
        assertEquals(TaskOutcome.FAILED, failed.task(":cringleValidate")?.outcome)
        return failed.output
    }
}
