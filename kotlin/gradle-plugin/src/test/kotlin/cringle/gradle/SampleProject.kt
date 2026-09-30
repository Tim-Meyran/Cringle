// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * The sample project of this repository and the TestKit runner that builds it. A project package holds no code, so
 * unlike the plugin sample the project sample needs no Kotlin plugin, no repository and no dependency: the plugin is
 * all it applies, and TestKit injects that plugin together with everything it needs at runtime.
 */
internal object SampleProject {

    /** The Gradle cache of this build, which the nested builds resolve from instead of a cache of their own. */
    private val gradleUserHome: String = required("cringle.gradleUserHome")

    private val source: Path = Path.of(System.getProperty("cringle.sampleProjectDir", "samples/sample-project"))

    /**
     * Copies the sample into [target]/[name] and returns that directory. Every test works on its own copy, so no test
     * sees the build output of another one.
     */
    fun copyTo(target: Path, name: String): Path = SamplePlugin.copyTo(source, target.resolve(name))

    /** A runner for the copy in [dir]. The output goes to the test log, so a failure shows the build. */
    fun runner(dir: Path, vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(dir.toFile())
        .withPluginClasspath(TestKitClasspath.cringle)
        .forwardOutput()
        .withArguments(
            *arguments,
            "-g",
            gradleUserHome,
            // This build has resolved every library of the nested build into this cache before the test starts, so the
            // nested build needs no network.
            "--offline",
            "--stacktrace",
        )

    /** Replaces [from] by [to] in the [file] of the copy in [dir], for example to break a blueprint. */
    fun replaceIn(dir: Path, file: String, from: String, to: String) = SamplePlugin.replaceIn(dir, file, from, to)

    /** Replaces [from] by [to] in the `build.gradle.kts` of the copy in [dir]. */
    fun replaceInBuildScript(dir: Path, from: String, to: String) = SamplePlugin.replaceInBuildScript(dir, from, to)

    /** Deletes [file] in the copy in [dir]. */
    fun delete(dir: Path, file: String) = SamplePlugin.delete(dir, file)

    private fun required(name: String): String =
        checkNotNull(System.getProperty(name)) { "the system property '$name' is not set" }
}
