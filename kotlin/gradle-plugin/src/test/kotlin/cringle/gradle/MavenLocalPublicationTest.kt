// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * AC 1: `publishToTestMavenLocal` writes the plugin, the marker of `cringle.plugin`, the three libraries the plugin
 * needs and nothing else into `build/cringle-test-maven-local`, a folder with the layout of Maven Local. The test
 * reads that folder; it never looks at `~/.m2`, which no test of this build touches.
 *
 * The publications of a snapshot repository carry a timestamp in the name of the files, so the tests look for
 * `<artifactId>-*<extension>` instead of the plain name that `./gradlew publishToMavenLocal` writes. The version
 * itself is the one of the version catalog and is part of every POM and of every folder.
 */
class MavenLocalPublicationTest {

    /** The folder the task `publishToTestMavenLocal` of the root build has written before the tests start. */
    private val mavenLocal: Path = Path.of(required("cringle.testMavenLocal"))

    /** The version all publications carry, the one definition in the version catalog. */
    private val version: String = required("cringle.version")

    @Test
    fun thePluginIsPublishedWithItsPomAndItsModuleMetadata() {
        val dir = moduleDir("gradle-plugin")
        publishedFile(dir, "gradle-plugin", ".jar")
        val pom = publishedFile(dir, "gradle-plugin", ".pom").readText()
        assertEquals(
            listOf("packaging:$version", "contract:$version"),
            cringleDependencies(pom),
            "the plugin has to depend on packaging and contract, but its POM has $pom",
        )
        // The module metadata tells a consumer that the JAR and the POM belong to the same module.
        val metadata = publishedFile(dir, "gradle-plugin", ".module").readText()
        assertTrue("\"$version\"" in metadata, "the module metadata of the plugin has to carry $version:\n$metadata")
    }

    @Test
    fun theMarkerOfThePluginIsPublishedAndPointsAtThePlugin() {
        val dir = markerDir("cringle.plugin")
        val pom = publishedFile(dir, "cringle.plugin.gradle.plugin", ".pom").readText()
        assertEquals(
            listOf("gradle-plugin:$version"),
            cringleDependencies(pom),
            "the marker of cringle.plugin has to depend on the plugin, but its POM has $pom",
        )
    }

    @Test
    fun theMarkerOfTheProjectPluginIsNotPublished() {
        // A marker is a promise that the plugin exists. `cringle.project` comes with #52, until then it is published
        // nowhere, so an external project gets a "not found" and not a promise that fails later.
        assertFalse(
            Files.exists(mavenLocal.resolve("cringle").resolve("project")),
            "the marker of cringle.project must not be published, but ${mavenLocal.resolve("cringle/project")} exists",
        )
        assertEquals(
            listOf("cringle.plugin.gradle.plugin"),
            childDirectories(mavenLocal.resolve("cringle").resolve("plugin")),
            "the folder of the markers must hold the marker of cringle.plugin only",
        )
    }

    @Test
    fun theLibrariesThePluginNeedsArePublished() {
        for (library in listOf("contract", "schema", "packaging")) {
            val dir = moduleDir(library)
            publishedFile(dir, library, ".jar")
            publishedFile(dir, library, ".pom")
            publishedFile(dir, library, ".module")
        }
    }

    @Test
    fun theFolderHoldsNothingButThePublicationsOfThisBuild() {
        assertEquals(
            listOf("contract", "gradle-plugin", "packaging", "plugin", "schema"),
            childDirectories(mavenLocal.resolve("cringle")),
            "build/cringle-test-maven-local must hold the plugin, its marker and the three libraries only",
        )
    }

    @Test
    fun everyPublicationCarriesTheSnapshotVersion() {
        val poms = Files.walk(mavenLocal).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.name.endsWith(".pom") }.toList()
        }
        assertTrue(poms.isNotEmpty(), "no POM in $mavenLocal")
        for (pom in poms) {
            val text = pom.readText()
            assertEquals(version, ownVersion(text), "${pom.fileName} has to be published under $version")
            val dependencies = cringleDependencies(text)
            assertTrue(
                dependencies.all { it.endsWith(":$version") },
                "every cringle dependency of ${pom.fileName} has to carry $version, but has $dependencies",
            )
        }
    }

    @Test
    fun noPublicationOfTheOldLocalVersionIsLeft() {
        val files = Files.walk(mavenLocal).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
        for (file in files) {
            val inPath = "0.0.0-local" in file.toString()
            val inContent = !file.name.endsWith(".jar") && "0.0.0-local" in file.readText()
            assertFalse(inPath || inContent, "$file still carries the version 0.0.0-local of #21")
        }
    }

    /** The folder of the module [artifactId] of the group `cringle` at the published version. */
    private fun moduleDir(artifactId: String): Path = mavenLocal.resolve("cringle").resolve(artifactId).resolve(version)

    /** The folder of the marker of the plugin id [pluginId]. */
    private fun markerDir(pluginId: String): Path = mavenLocal.resolve(pluginId.replace('.', '/'))
        .resolve("$pluginId.gradle.plugin")
        .resolve(version)

    /** The single file of [artifactId] with the extension [extension] in [dir]. */
    private fun publishedFile(dir: Path, artifactId: String, extension: String): Path {
        assertTrue(Files.isDirectory(dir), "$dir was not published")
        val found = Files.list(dir).use { files ->
            files.filter { it.name.startsWith("$artifactId-") && it.name.endsWith(extension) }.toList()
        }
        assertEquals(1, found.size, "expected exactly one $artifactId*$extension in $dir, but found $found")
        return found.single()
    }

    private fun childDirectories(dir: Path): List<String> {
        assertTrue(Files.isDirectory(dir), "$dir was not published")
        return Files.list(dir).use { files ->
            files.filter { it.isDirectory() }.map { it.fileName.toString() }.sorted().toList()
        }
    }

    /** The version of the module itself, which is the first one in a POM. */
    private fun ownVersion(pom: String): String =
        requireNotNull(VERSION.find(pom)) { "the POM has no version:\n$pom" }.groupValues[1]

    /** The dependencies of the group `cringle`, which are the ones of this build, as `artifactId:version`. */
    private fun cringleDependencies(pom: String): List<String> =
        DEPENDENCY.findAll(pom)
            .map { it.groupValues[1] }
            .filter { "<groupId>cringle</groupId>" in it }
            .map {
                val artifact = requireNotNull(ARTIFACT.find(it)) { "a dependency has no artifactId:\n$it" }.groupValues[1]
                val version = requireNotNull(VERSION.find(it)) { "a dependency has no version:\n$it" }.groupValues[1]
                "$artifact:$version"
            }
            .toList()

    private fun required(name: String): String =
        checkNotNull(System.getProperty(name)) { "the system property '$name' is not set" }

    private companion object {

        val VERSION = Regex("<version>([^<]+)</version>")
        val ARTIFACT = Regex("<artifactId>([^<]+)</artifactId>")
        val DEPENDENCY = Regex("(?s)<dependency>(.*?)</dependency>")
    }
}
