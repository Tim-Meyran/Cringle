// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The sample plugin project of this repository and the TestKit runner that builds it. The three functional tests all
 * work on copies of the sample, so a test never sees the build output of another test.
 */
internal object SamplePlugin {

    /** Where the `cringleRepo` and `cringleVersion` properties of the sample come from. */
    val localRepo: String = required("cringle.localRepo")

    /** The version the three libraries of this repository are published under. */
    val version: String = required("cringle.version")

    /** The Gradle cache of this build, which the nested builds resolve from instead of a cache of their own. */
    private val gradleUserHome: String = required("cringle.gradleUserHome")

    private val source: Path = Path.of(System.getProperty("cringle.sampleDir", "samples/sample-plugin"))

    /**
     * What the nested build sees as its plugin classpath: the Cringle plugin with everything it needs at runtime, and
     * the Kotlin plugin of this repository. TestKit injects both into the build under test, and a build under test
     * resolves its plugins from that classpath only.
     */
    private val pluginClasspath: List<File> get() = TestKitClasspath.forNestedBuild

    /** The name of the sample's `build.gradle.kts` inside a copy. */
    val buildScript: Path get() = Path.of("build.gradle.kts")

    /**
     * Copies the sample into [target]/[name] and returns that directory. Every test works on its own copy, so a test
     * never sees the build output of another test.
     */
    fun copyTo(target: Path, name: String): Path {
        val dir = target.resolve(name)
        copyTo(source, dir)
        return dir
    }

    /** Copies the sample project in [source] into [dir], keeping the paths below the source. */
    fun copyTo(source: Path, dir: Path): Path {
        copyDirectory(source, dir)
        return dir
    }

    /** A runner for the copy in [dir]. The output goes to the test log, so a failure shows the build. */
    fun runner(dir: Path, vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(dir.toFile())
        .withPluginClasspath(pluginClasspath)
        .forwardOutput()
        .withArguments(
            *arguments,
            "-PcringleRepo=$localRepo",
            "-PcringleVersion=$version",
            "-g",
            gradleUserHome,
            // This build has resolved every library of the nested build into this cache before the test starts, so the
            // nested build needs no network.
            "--offline",
            "--stacktrace",
        )

    /** Replaces [from] by [to] in the [file] of the copy in [dir], for example to break a blueprint. */
    fun replaceIn(dir: Path, file: String, from: String, to: String) {
        val path = dir.resolve(file)
        val text = path.readText()
        check(from in text) { "'$from' is not in $file of the sample" }
        path.writeText(text.replace(from, to))
    }

    /** Replaces [from] by [to] in the `build.gradle.kts` of the copy in [dir]. */
    fun replaceInBuildScript(dir: Path, from: String, to: String) =
        replaceIn(dir, buildScript.toString(), from, to)

    /** Deletes [file] in the copy in [dir]. */
    fun delete(dir: Path, file: String) {
        val path = dir.resolve(file)
        check(path.deleteIfExists()) { "$file is not in the copy of the sample" }
    }

    private fun copyDirectory(from: Path, to: Path) {
        Files.walk(from).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { file ->
                val target = to.resolve(from.relativize(file).toString())
                target.parent.createDirectories()
                file.copyTo(target)
            }
        }
    }

    private fun required(name: String): String =
        checkNotNull(System.getProperty(name)) { "the system property '$name' is not set" }
}
