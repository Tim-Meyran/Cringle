// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * AC 1: `./gradlew publishToLocalRepo` writes `cringle:testkit` (and the other four sample libraries — contract, schema,
 * packaging and common) into `build/cringle-repo`, a folder with the layout of Maven Local. The test reads that folder;
 * it never looks at `~/.m2`, which no test of this build touches. The folder holds the five sample libraries only:
 * the plugin, the markers, router and repository go to `build/cringle-test-maven-local` instead.
 *
 * The publications of a snapshot repository carry a timestamp in the name of the files, so the tests look for
 * `<artifactId>-*<extension>` instead of the plain name that `./gradlew publishToMavenLocal` writes. The version
 * itself is the one of the version catalog and is part of every POM and of every folder.
 */
@Tag("integration")
class LocalRepoPublicationTest {

    /** The folder the task `publishToLocalRepo` of the root build has written before the tests start. */
    private val localRepo: Path = Path.of(required("cringle.localRepo"))

    /** The version all publications carry, the one definition in the version catalog. */
    private val version: String = required("cringle.version")

    @Test
    fun theFiveSampleLibrariesArePublished() {
        for (library in listOf("contract", "schema", "packaging", "common", "testkit")) {
            val dir = moduleDir(library)
            publishedFile(dir, library, ".jar")
            publishedFile(dir, library, ".pom")
            publishedFile(dir, library, ".module")
        }
    }

    @Test
    fun theFolderHoldsNothingButTheFiveSampleLibraries() {
        assertEquals(
            listOf("common", "contract", "packaging", "schema", "testkit"),
            childDirectories(localRepo.resolve("cringle")),
            "build/cringle-repo must hold the five sample libraries only, not the plugin, the markers, router or repository",
        )
    }

    @Test
    fun everyPublicationCarriesTheSnapshotVersion() {
        val poms = Files.walk(localRepo).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.name.endsWith(".pom") }.toList()
        }
        assertTrue(poms.isNotEmpty(), "no POM in $localRepo")
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

    /** The folder of the module [artifactId] of the group `cringle` at the published version. */
    private fun moduleDir(artifactId: String): Path = localRepo.resolve("cringle").resolve(artifactId).resolve(version)

    /**
     * A file of [artifactId] with the extension [extension] in [dir]. Unlike the sibling test, `build/cringle-repo` is
     * not wiped before each publication, so the folder may carry old snapshot publications alongside the latest one;
     * the test only asserts that at least one matching file exists.
     */
    private fun publishedFile(dir: Path, artifactId: String, extension: String): Path {
        assertTrue(Files.isDirectory(dir), "$dir was not published")
        val found = Files.list(dir).use { files ->
            files.filter { it.name.startsWith("$artifactId-") && it.name.endsWith(extension) }.toList()
        }
        assertTrue(found.isNotEmpty(), "expected at least one $artifactId*$extension in $dir, but found none")
        return found.first()
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
