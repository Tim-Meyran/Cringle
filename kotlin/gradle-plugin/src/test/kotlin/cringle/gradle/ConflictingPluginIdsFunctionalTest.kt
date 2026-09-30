// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * AC 4: a build that applies both plugin ids fails with a message that names both. A build is a plugin package or a
 * project package, and the extension `cringle { }` of the one is not the extension of the other, so a build that
 * applies both is a mistake that has to be visible and not a second package task that quietly overwrites the first.
 *
 * The sample project applies only `cringle.project`, so the tests use the sample plugin and replace its plugin id.
 */
class ConflictingPluginIdsFunctionalTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun theProjectPluginFailsTheBuildWhenThePluginIsAlreadyApplied() {
        val project = SamplePlugin.copyTo(temp, "project-last")
        SamplePlugin.replaceInBuildScript(
            project,
            """    id("cringle.plugin")""",
            """    id("cringle.plugin")
    id("cringle.project")""",
        )

        val failed = SamplePlugin.runner(project, "cringlePackage").buildAndFail()
        val output = failed.output

        assertTrue(CringleProjectPlugin.CONFLICT in output, "the message that names both ids is missing in:\n$output")
        assertNull(failed.task(":cringlePackage"), "the build must fail before a package is built")
    }

    @Test
    fun thePluginFailsTheBuildWhenTheProjectPluginIsAlreadyApplied() {
        val project = SamplePlugin.copyTo(temp, "plugin-last")
        SamplePlugin.replaceInBuildScript(
            project,
            """    id("cringle.plugin")""",
            """    id("cringle.project")
    id("cringle.plugin")""",
        )

        val failed = SamplePlugin.runner(project, "cringlePackage").buildAndFail()
        val output = failed.output

        assertTrue(CringleProjectPlugin.CONFLICT in output, "the message that names both ids is missing in:\n$output")
        assertNull(failed.task(":cringlePackage"), "the build must fail before a package is built")
    }
}
