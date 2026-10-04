// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.contract.UserRole
import cringle.packaging.PackageHash
import cringle.packaging.PackageKind
import cringle.packaging.PackageWriter
import cringle.packaging.PluginManifest
import cringle.repository.PackageEntry
import cringle.repository.PackageRepository
import cringle.repository.RepositoryClient
import cringle.repository.RepositoryServer
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `cringlePublish` of a `cringle.project` build (#64): the same task as in `cringle.plugin`, with the same sources for
 * the address and the token. The repository validates a project against the plugins it depends on, so every test starts
 * with the sample plugin `acme-orders 1.2.0` (published with its own `cringlePublish`) in the repository the project
 * publishes to.
 *
 * Like `PublishFunctionalTest` no test here leaves the loopback interface or reads the profile of the user who runs
 * the build: the Cringle home is a folder of the build directory.
 */
class ProjectPublishFunctionalTest {

    @TempDir
    lateinit var temp: Path

    private val home: Path = Path.of(required("cringle.testHome"))

    private val profile = home.resolve(CliProfile.FILE_NAME)

    private lateinit var repository: PackageRepository

    @BeforeEach
    fun repositoryWithThePluginTheProjectNeeds() {
        Files.deleteIfExists(profile)
        repository = PackageRepository(temp.resolve("repo"))
        val core = Files.createTempFile(temp, "acme-core", ".cringle")
        Files.newOutputStream(core).use { out ->
            PackageWriter.writePlugin(PluginManifest("acme-core", "1.0.0"), emptyMap(), emptyMap(), emptyMap(), out)
        }
        repository.publish(core)
        val server = RepositoryServer(repository, tempDir = upload()).start()
        try {
            val plugin = SamplePlugin.copyTo(temp, "plugin")
            val result = SamplePlugin.runner(plugin, "cringlePublish", "-P${CringlePlugin.PROPERTY_SERVER}=127.0.0.1:${server.port}").build()
            assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePublish")?.outcome, result.output)
        } finally {
            server.stop()
        }
        assertEquals(listOf("acme-core", "acme-orders"), repository.list().map { it.name }.sorted())
    }

    @AfterEach
    fun noProfileLeft() {
        Files.deleteIfExists(profile)
    }

    /**
     * AC 1: the project package goes to the repository, and name, version and SHA-256 are what `RepositoryClient.get`
     * reports; the second run of the same version fails with a plain message.
     */
    @Test
    fun publishesTheProjectPackageAndRefusesTheSameVersionASecondTime() {
        val project = copyProject("publish")
        val server = RepositoryServer(repository, tempDir = upload()).start()
        val address = "127.0.0.1:${server.port}"
        val arguments = arrayOf("cringlePublish", "-P${CringlePlugin.PROPERTY_SERVER}=$address")

        val result = runner(project, *arguments).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePublish")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":cringleValidate")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePackage")?.outcome, result.output)

        val file = project.resolve("build/distributions/acme-shop-0.3.1.cringle")
        val entry = stored(address)
        assertEquals("acme-shop", entry.name)
        assertEquals("0.3.1", entry.version)
        assertEquals(PackageKind.PROJECT, entry.kind)
        assertEquals(PackageHash.sha256(file), entry.sha256, "the repository has to hold the bytes of $file")
        assertTrue(
            "published acme-shop 0.3.1 (SHA-256 ${entry.sha256}) to $address" in result.output,
            "the task has to report name, version and SHA-256:\n${result.output}",
        )

        val again = runner(project, *arguments).buildAndFail()
        assertTrue("already published" in again.output, "the second run has to say that the version exists:\n${again.output}")
        assertFalse("\tat " in again.output, "the message has to stand on its own, without a stack trace:\n${again.output}")
        server.stop()
    }

    /** AC 2: address and token come out of the profile of `cringle login`, and the token is in no output of the build. */
    @Test
    fun theAddressAndTheTokenComeFromTheProfileAndTheTokenIsInNoOutput() {
        val users = UserManager(FileUserStore(temp.resolve("users.json")))
        val operator = users.createUser("author", setOf(UserRole.OPERATOR))
        val token = users.createToken(operator.user.id, "t", null).secret
        val server = RepositoryServer(repository, users = users, tempDir = upload()).start()
        val address = "127.0.0.1:${server.port}"
        home.createDirectories()
        profile.writeText("""{"server": "$address", "token": "$token"}""")
        val project = copyProject("profile")

        val result = runner(project, "cringlePublish").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePublish")?.outcome, result.output)
        assertEquals("acme-shop", stored(address, token).name)
        assertFalse(token in result.output, "the token appeared in the output of the build:\n${result.output}")
        assertFalse(token in Files.readString(project.resolve("build.gradle.kts")), "the token must not be in the build script")
        server.stop()
    }

    /** AC 2: the block `cringle { publish { server = … } }` names the address, as in a plugin build. */
    @Test
    fun theBlockOfTheBuildScriptNamesTheRepository() {
        val project = copyProject("block")
        val address = "127.0.0.1:${closedPort()}"
        SampleProject.replaceInBuildScript(project, "    name = \"acme-shop\"", "    publish { server = \"$address\" }\n    name = \"acme-shop\"")

        val result = runner(project, "cringlePublish", "--dryRun").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePublish")?.outcome, result.output)
        assertTrue("would publish acme-shop 0.3.1 to $address" in result.output, "the task has to name target, name and version:\n${result.output}")
        assertEquals(listOf("acme-core", "acme-orders"), repository.list().map { it.name }.sorted(), "--dryRun must publish nothing")
    }

    /** AC 2: a repository that wants a token and gets none is answered with the same message as for a plugin. */
    @Test
    fun withoutATokenTheMessageNamesTheEnvironmentAndTheLogin() {
        val users = UserManager(FileUserStore(temp.resolve("users.json")))
        users.bootstrap()
        val server = RepositoryServer(repository, users = users, tempDir = upload()).start()
        val project = copyProject("without-token")

        val result = runner(project, "cringlePublish", "-P${CringlePlugin.PROPERTY_SERVER}=127.0.0.1:${server.port}").buildAndFail()
        assertTrue("CRINGLE_TOKEN" in result.output, "the message has to name the variable:\n${result.output}")
        assertTrue("cringle login" in result.output, "the message has to name the login:\n${result.output}")
        assertFalse("\tat " in result.output, "the message has to stand on its own, without a stack trace:\n${result.output}")
        server.stop()
    }

    /** A project whose blueprint is wrong never reaches the repository: the validation stops the build first. */
    @Test
    fun anInvalidProjectIsNotPublished() {
        val project = copyProject("invalid")
        SampleProject.replaceInBuildScript(project, """fabric("orders")""", """fabric("nope")""")
        val server = RepositoryServer(repository, tempDir = upload()).start()

        val result = runner(project, "cringlePublish", "-P${CringlePlugin.PROPERTY_SERVER}=127.0.0.1:${server.port}").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":cringleValidate")?.outcome, result.output)
        assertEquals(null, result.task(":cringlePublish"), "publish must not run after a failed validation")
        assertEquals(listOf("acme-core", "acme-orders"), repository.list().map { it.name }.sorted())
        server.stop()
    }

    /**
     * A copy of the sample project for the tests that publish it. The blueprint of the sample has a tether from the `out`
     * port of a block of `acme-orders` to its `in` port, and the plugin sample declares `out` for MESSAGE and `in` for
     * REQUEST_RESPONSE, so the repository would rightly refuse that pair; the copy has no tether, and its blocks get the
     * retries their configuration schema requires.
     */
    private fun copyProject(name: String): Path {
        return SampleProject.copyTo(temp, name)
    }

    /** A runner for the copy in [project], without `--stacktrace`, so a failure shows the message a user sees. */
    private fun runner(project: Path, vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(project.toFile())
        .withPluginClasspath(TestKitClasspath.cringle)
        .forwardOutput()
        .withArguments(*arguments, "-g", required("cringle.gradleUserHome"), "--offline")

    private fun upload(): Path = temp.resolve("upload").also { it.createDirectories() }

    private fun stored(address: String, token: String? = null): PackageEntry =
        RepositoryClient(address, token).use { client -> runBlocking { client.get("acme-shop", "0.3.1") } }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private fun required(name: String): String =
        checkNotNull(System.getProperty(name)) { "the system property '$name' is not set" }
}
