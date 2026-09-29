// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.Properties
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
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
    private val pluginClasspath: List<File> by lazy {
        val metadata = checkNotNull(javaClass.classLoader.getResource("plugin-under-test-metadata.properties")) {
            "the java-gradle-plugin plugin did not write plugin-under-test-metadata.properties"
        }
        val cringle = Properties().apply { metadata.openStream().use { load(it) } }
            .getProperty("implementation-classpath")
            .orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotEmpty() }
            .map(::File)
        val kotlin = required("cringle.kotlinPluginClasspath")
            .split(File.pathSeparator)
            .filter { it.isNotEmpty() }
            .map(::File)
        (cringle + kotlin).distinctBy { it.absolutePath }
    }

    /** The name of the sample's `build.gradle.kts` inside a copy. */
    val buildScript: Path get() = Path.of("build.gradle.kts")

    /**
     * Copies the sample into [target]/[name] and returns that directory. The copy also gets a JAR of its own, so that
     * `lib/` of the package has a dependency that the parent classloader does not provide.
     */
    fun copyTo(target: Path, name: String): Path {
        val dir = target.resolve(name)
        copyDirectory(source, dir)
        writeSupportJar(dir)
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

    private fun writeSupportJar(dir: Path) {
        val libs = dir.resolve("libs")
        libs.createDirectories()
        val entry = JarEntry("META-INF/MANIFEST.MF")
        entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
        JarOutputStream(Files.newOutputStream(libs.resolve("acme-orders-support.jar"))).use { out ->
            out.putNextEntry(entry)
            out.write("Manifest-Version: 1.0\r\n\r\n".toByteArray())
            out.closeEntry()
        }
    }

    /** Replaces [from] by [to] in the `build.gradle.kts` of the copy in [dir]. */
    fun replaceInBuildScript(dir: Path, from: String, to: String) {
        val file = dir.resolve(buildScript)
        val text = file.readText()
        check(from in text) { "'$from' is not in the build script of the sample" }
        file.writeText(text.replace(from, to))
    }

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
