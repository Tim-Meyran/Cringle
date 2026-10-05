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
 * the test compiles, the harness drives the block, and the assertions hold.
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
        val report = project.resolve("build/reports/tests/test/index.html")
        assertTrue(
            Files.isRegularFile(report),
            "the sample's HTML test report was not written at $report in:\n$output",
        )
        val reportText = Files.readString(report)
        println("DEBUG HTML REPORT (first 2000 chars):\n" + reportText.take(2000))
        assertTrue(
            reportText.contains("OrdersBlockTest"),
            "the sample's HTML test report at $report did not mention OrdersBlockTest in:\n$output",
        )
    }
}
