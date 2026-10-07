// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.contract.UserRole
import cringle.packaging.PackageWriter
import cringle.packaging.PluginManifest
import cringle.repository.PackageRepository
import cringle.repository.RepositoryServer
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

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

    // --- validation against the plugins the package depends on (#72) ---

    private val home: Path = Path.of(checkNotNull(System.getProperty("cringle.testHome")) { "cringle.testHome is not set" })

    private val profile = home.resolve(CliProfile.FILE_NAME)

    @AfterEach
    fun noProfileLeft() {
        Files.deleteIfExists(profile)
    }

    /** The schema document of the plugin `acme-core`, which the sample plugin depends on. */
    private val coreSchema = """{"namespace":"acme.core","types":{"Money":{"record":{"amount":"cringle.std/Int"}}}}"""

    /** A repository that holds `acme-core 1.0.0` with its schema `acme.core/Money`. */
    private fun repositoryWithCore(): PackageRepository {
        val repository = PackageRepository(temp.resolve("repo"))
        val file = Files.createTempFile(temp, "acme-core", ".cringle")
        Files.newOutputStream(file).use { out ->
            PackageWriter.writePlugin(
                PluginManifest("acme-core", "1.0.0", schemas = listOf("schemas/acme.core.json")),
                mapOf("schemas/acme.core.json" to coreSchema),
                emptyMap(),
                emptyMap(),
                out,
            )
        }
        repository.publish(file)
        return repository
    }

    private val tlsRepo by lazy { TlsRepository(temp, home) }

    private fun server(repository: PackageRepository, users: UserManager? = null): RepositoryServer =
        tlsRepo.start(repository, users, temp.resolve("upload").also { it.createDirectories() })

    /** The sample, with the `in` port of its block using the schema [ref] of the dependency `acme-core`. */
    private fun sampleUsing(name: String, ref: String): Path {
        val project = SamplePlugin.copyTo(temp, name)
        SamplePlugin.replaceInBuildScript(project, """port("in", PortDirection.IN, "acme.orders/Order", """, """port("in", PortDirection.IN, "$ref", """)
        return project
    }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    /** An environment with no address and no token of the machine the test runs on, and a home without a profile. */
    private fun cleanEnvironment(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val empty = home.resolve("empty").also { it.createDirectories() }
        return System.getenv().filterKeys { it != "CRINGLE_SERVER" && it != "CRINGLE_TOKEN" && it != "CRINGLE_FINGERPRINT" } + mapOf("CRINGLE_HOME" to empty.toString()) + extra
    }

    private fun validateWith(project: Path, vararg arguments: String, environment: Map<String, String>? = null): GradleRunner =
        SamplePlugin.runner(project, "cringleValidate", *arguments).apply { if (environment != null) withEnvironment(environment) }

    @Test
    fun aSchemaOfADependencyIsFoundInTheRepositoryAndAMissingOneFails() {
        val server = server(repositoryWithCore())
        val address = "127.0.0.1:${server.port}"
        try {
            val valid = validateWith(sampleUsing("money", "acme.core/Money"), "-P${CringlePlugin.PROPERTY_SERVER}=$address").build()
            assertEquals(TaskOutcome.SUCCESS, valid.task(":cringleValidate")?.outcome, valid.output)
            assertFalse("validated without" in valid.output, "a reachable repository needs no fallback:\n${valid.output}")

            val missing = validateWith(sampleUsing("nope", "acme.core/Nope"), "-P${CringlePlugin.PROPERTY_SERVER}=$address").buildAndFail()
            assertEquals(TaskOutcome.FAILED, missing.task(":cringleValidate")?.outcome)
            assertTrue(
                "$.blocks[0].ports[0].schema: schema 'acme.core/Nope' does not resolve" in missing.output,
                "the finding for the missing schema of the dependency is missing in:\n${missing.output}",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun cringlePackageValidatesAgainstTheDependenciesToo() {
        val server = server(repositoryWithCore())
        try {
            val project = sampleUsing("package", "acme.core/Nope")
            val result = SamplePlugin.runner(project, "cringlePackage", "-P${CringlePlugin.PROPERTY_SERVER}=127.0.0.1:${server.port}").buildAndFail()
            assertEquals(TaskOutcome.FAILED, result.task(":cringleValidate")?.outcome, result.output)
            assertEquals(null, result.task(":cringlePackage"), "the package must not be written:\n${result.output}")
        } finally {
            server.stop()
        }
    }

    @Test
    fun withoutAConfiguredRepositoryTheValidationGoesOnWithAWarning() {
        val project = sampleUsing("offline", "acme.core/Money")

        val result = validateWith(project, environment = cleanEnvironment()).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":cringleValidate")?.outcome, result.output)
        assertTrue("no repository is configured" in result.output, "the warning has to say why:\n${result.output}")
        assertTrue("acme.core/Money" in result.output, "the warning has to name the reference that was not checked:\n${result.output}")
    }

    @Test
    fun anUnreachableRepositoryAlsoOnlyWarns() {
        val project = sampleUsing("unreachable", "acme.core/Money")

        val result = validateWith(project, "-P${CringlePlugin.PROPERTY_SERVER}=127.0.0.1:${closedPort()}", "-P${CringlePlugin.PROPERTY_FINGERPRINT}=${"0".repeat(64)}").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":cringleValidate")?.outcome, result.output)
        assertTrue("is not reachable" in result.output, "the warning has to say that the repository is not there:\n${result.output}")
    }

    /** TLS (#37): a repository that is up but cannot be pinned makes the validation go on with a warning that names the four places. */
    @Test
    fun aRepositoryWithoutAFingerprintOnlyWarnsAndNamesTheFourPlaces() {
        val server = server(repositoryWithCore())
        try {
            tlsRepo.writeProfile("127.0.0.1:${server.port}", null, fingerprint = null)
            val result = validateWith(sampleUsing("no-fingerprint", "acme.core/Money")).build()

            assertEquals(TaskOutcome.SUCCESS, result.task(":cringleValidate")?.outcome, result.output)
            for (place in listOf("-Pcringle.fingerprint", "cringle { publish { fingerprint", "CRINGLE_FINGERPRINT", "cli.json")) {
                assertTrue(place in result.output, "the warning has to name '$place':\n${result.output}")
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun aReferenceIntoTheOwnNamespaceStillFailsWithoutARepository() {
        // the existing test of the missing schema runs with a declared dependency and no repository; this is the same with
        // a repository that is not reachable
        val project = SamplePlugin.copyTo(temp, "own")
        SamplePlugin.delete(project, "src/main/cringle/schemas/acme.orders.json")

        val failed = validateWith(project, "-P${CringlePlugin.PROPERTY_SERVER}=127.0.0.1:${closedPort()}").buildAndFail()

        assertTrue("schema 'acme.orders/Order' does not resolve" in failed.output, failed.output)
    }

    @Test
    fun aPluginWithoutADeclaredDependencyCannotUseASchemaOfAnother() {
        val project = sampleUsing("undeclared", "acme.core/Money")
        SamplePlugin.replaceInBuildScript(project, """    dependency("acme-core", "^1.0.0")""", "")

        val failed = validateWith(project, environment = cleanEnvironment()).buildAndFail()

        assertEquals(TaskOutcome.FAILED, failed.task(":cringleValidate")?.outcome)
        assertTrue("schema 'acme.core/Money' does not resolve" in failed.output, failed.output)
        assertTrue("declares no dependency that could provide the namespace 'acme.core'" in failed.output, failed.output)
        assertTrue("dependency(" in failed.output, "the message has to say how to declare it:\n${failed.output}")
    }

    @Test
    fun addressAndTokenComeFromTheProfileAndFromTheEnvironmentLikeForPublish() {
        val users = UserManager(FileUserStore(temp.resolve("users.json")))
        val token = users.createToken(users.createUser("author", setOf(UserRole.OPERATOR)).user.id, "t", null).secret
        val server = server(repositoryWithCore(), users)
        val address = "127.0.0.1:${server.port}"
        try {
            // the profile of `cringle login` in the Cringle home; the repository is asked, or the missing schema would pass
            tlsRepo.writeProfile(address, token)
            val fromProfile = validateWith(sampleUsing("profile", "acme.core/Nope")).buildAndFail()
            assertTrue("schema 'acme.core/Nope' does not resolve" in fromProfile.output, fromProfile.output)
            assertFalse(token in fromProfile.output, "the token appeared in the output:\n${fromProfile.output}")

            // without a token in the profile the repository refuses the read: a warning that names both places
            tlsRepo.writeProfile(address, null)
            val withoutToken = validateWith(sampleUsing("no-token", "acme.core/Money")).build()
            assertEquals(TaskOutcome.SUCCESS, withoutToken.task(":cringleValidate")?.outcome, withoutToken.output)
            assertTrue("CRINGLE_TOKEN" in withoutToken.output && "cringle login" in withoutToken.output, withoutToken.output)

            // the environment wins over the profile, as for cringlePublish
            Files.deleteIfExists(profile)
            val environment = cleanEnvironment(mapOf("CRINGLE_SERVER" to address, "CRINGLE_TOKEN" to token, "CRINGLE_FINGERPRINT" to tlsRepo.fingerprint))
            val fromEnvironment = validateWith(sampleUsing("environment", "acme.core/Nope"), environment = environment).buildAndFail()
            assertTrue("schema 'acme.core/Nope' does not resolve" in fromEnvironment.output, fromEnvironment.output)
            assertFalse(token in fromEnvironment.output, "the token appeared in the output:\n${fromEnvironment.output}")
        } finally {
            server.stop()
        }
    }
}