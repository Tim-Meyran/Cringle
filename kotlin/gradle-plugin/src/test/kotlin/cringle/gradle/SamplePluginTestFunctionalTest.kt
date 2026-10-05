// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * AC: the sample plugin's own test runs and passes in a nested build with `--offline`, and it uses `BlockTestHarness`.
 *
 * The sample at `samples/sample-plugin` has a test `OrdersBlockTest` that exercises the `orders` block through the
 * testkit. Running `./gradlew test` in a copy of the sample proves that the testkit is wired up correctly end to end:
 * the test compiles, the harness drives the block, and the assertions hold. The check inspects the JUnit XML test
 * results Gradle writes at `build/test-results/test/TEST-acme.orders.OrdersBlockTest.xml` and asserts that the root
 * `<testsuite>` element reports `failures="0"` and `errors="0"`.
 */
class SamplePluginTestFunctionalTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun theSamplePluginTestRunsAndPasses() {
        val project = SamplePlugin.copyTo(temp, "test")
        val result = SamplePlugin.runner(project, "test").build()
        val output = result.output

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        val xmlReport = project.resolve("build/test-results/test/TEST-acme.orders.OrdersBlockTest.xml")
        assertTrue(
            Files.isRegularFile(xmlReport),
            "the sample's JUnit XML test results were not written at $xmlReport in:\n$output",
        )
        val xmlText = Files.readString(xmlReport)
        val failuresZero = Regex("""failures="0"""").containsMatchIn(xmlText)
        val errorsZero = Regex("""errors="0"""").containsMatchIn(xmlText)
        assertTrue(
            failuresZero && errorsZero,
            "the sample's JUnit XML test results at $xmlReport did not report failures=\"0\" and errors=\"0\" in:\n$output",
        )
    }
}
