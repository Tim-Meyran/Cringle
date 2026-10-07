// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.contract.UserRole
import cringle.packaging.PackageHash
import cringle.packaging.PackageWriter
import cringle.packaging.PluginManifest
import cringle.repository.PackageEntry
import cringle.repository.PackageRepository
import cringle.repository.RepositoryClient
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
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
 * The functional tests of `cringlePublish`: the task of #51 builds the package of the sample and uploads it to a
 * `RepositoryServer` that runs in this JVM, while the build under test publishes it.
 *
 * No test here reaches the network or the home of the user who runs the build. The repository is a temporary folder, the
 * server is on the loopback interface, the profile of `cringle login` is written into the Cringle home of the build
 * directory (the build script puts it into the environment of this JVM, so `CRINGLE_HOME` is never empty and
 * `~/.cringle/cli.json` is never read), and no test writes into `~/.m2`.
 *
 * The runner is built here instead of taken from [SamplePlugin], because three of these tests need a build under test
 * that does not print a stack trace, that runs in a forked process (a build that gets an environment of its own can
 * only be sure of it outside the daemon) and that keeps its output out of the log of the build.
 */
class PublishFunctionalTest {

    @TempDir
    lateinit var temp: Path

    /** The Cringle home the nested builds read the profile from, a folder of the build directory. */
    private val home: Path = Path.of(required("cringle.testHome"))

    private val profile = home.resolve(CliProfile.FILE_NAME)

    private lateinit var repository: PackageRepository

    private val tlsRepo by lazy { TlsRepository(temp, home) }

    @BeforeEach
    fun repositoryAndProfile() {
        Files.deleteIfExists(profile)
        repository = PackageRepository(temp.resolve("repo"))
    }

    @AfterEach
    fun noProfileLeft() {
        Files.deleteIfExists(profile)
    }

    /**
     * AC 1 and AC 2: the task publishes the package of the sample, name, version and SHA-256 are what
     * `RepositoryClient.get` reports, and a second run of the same version fails with a plain message.
     */
    @Test
    fun publishesThePackageOfTheSampleAndRefusesTheSameVersionASecondTime() {
        val project = SamplePlugin.copyTo(temp, "publish")
        publishCore()
        val server = tlsRepo.start(repository, upload = upload())
        val address = "127.0.0.1:${server.port}"
        val arguments = listOf("cringlePublish", "-P${CringlePlugin.PROPERTY_SERVER}=$address")

        val result = runner(project, arguments).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePublish")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePackage")?.outcome, result.output)

        val file = project.resolve("build/distributions/acme-orders-1.2.0.cringle")
        val entry = stored(address)
        assertEquals("acme-orders", entry.name)
        assertEquals("1.2.0", entry.version)
        assertEquals(PackageHash.sha256(file), entry.sha256, "the repository has to hold the bytes of $file")
        assertTrue(
            "published acme-orders 1.2.0 (SHA-256 ${entry.sha256}) to $address" in result.output,
            "the task has to report name, version and SHA-256:\n${result.output}",
        )

        val again = runner(project, arguments).buildAndFail()
        assertTrue("already published" in again.output, "the second run has to say that the version exists:\n${again.output}")
        assertFalse("\tat " in again.output, "the message has to stand on its own, without a stack trace:\n${again.output}")
        server.stop()
    }

    /** AC 3: address and token come out of the profile that `cringle login` wrote, with no property and no variable. */
    @Test
    fun theAddressAndTheTokenComeFromTheProfileOfCringleHome() {
        val users = UserManager(FileUserStore(temp.resolve("users.json")))
        val operator = users.createUser("author", setOf(UserRole.OPERATOR))
        val token = users.createToken(operator.user.id, "t", null).secret
        val server = tlsRepo.start(repository, users, upload())
        val address = "127.0.0.1:${server.port}"
        writeProfile(address, token)
        val project = SamplePlugin.copyTo(temp, "profile")
        publishCore()

        val result = runner(project, listOf("cringlePublish")).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePublish")?.outcome, result.output)
        assertEquals("acme-orders", stored(address, token).name)
        assertFalse(token in result.output, "the token appeared in the output of the build:\n$result.output")
        server.stop()
    }

    /**
     * AC 3: the same token as `CRINGLE_TOKEN`, with the address as `CRINGLE_SERVER`, in a home without a profile. A
     * build under test that gets an environment of its own runs in a forked process, because a daemon keeps the
     * environment it was started with.
     */
    @Test
    fun theAddressAndTheTokenComeFromTheEnvironment() {
        val users = UserManager(FileUserStore(temp.resolve("users.json")))
        val operator = users.createUser("author", setOf(UserRole.OPERATOR))
        val token = users.createToken(operator.user.id, "t", null).secret
        val server = tlsRepo.start(repository, users, upload())
        val address = "127.0.0.1:${server.port}"
        val project = SamplePlugin.copyTo(temp, "environment")
        publishCore()
        val environment = System.getenv() + mapOf(
            "CRINGLE_SERVER" to address,
            "CRINGLE_TOKEN" to token,
            "CRINGLE_FINGERPRINT" to tlsRepo.fingerprint,
            "CRINGLE_HOME" to emptyHome().toString(),
        )

        val result = runner(project, listOf("cringlePublish"), environment = environment).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePublish")?.outcome, result.output)
        assertEquals("acme-orders", stored(address, token).name)
        server.stop()
    }

    /** AC 3: a repository that wants a token and gets none fails with a message that names both places a token is. */
    @Test
    fun withoutATokenTheMessageNamesTheEnvironmentAndTheLogin() {
        val users = UserManager(FileUserStore(temp.resolve("users.json")))
        users.bootstrap()
        val server = tlsRepo.start(repository, users, upload())
        val project = SamplePlugin.copyTo(temp, "without-token")

        val result = runner(project, listOf("cringlePublish", "-P${CringlePlugin.PROPERTY_SERVER}=127.0.0.1:${server.port}"))
            .buildAndFail()
        assertTrue("CRINGLE_TOKEN" in result.output, "the message has to name the variable:\n${result.output}")
        assertTrue("cringle login" in result.output, "the message has to name the login:\n${result.output}")
        assertFalse("\tat " in result.output, "the message has to stand on its own, without a stack trace:\n${result.output}")
        server.stop()
    }

    /** AC 3: a token that may read but not publish is refused with a message that names the right it lacks. */
    @Test
    fun aTokenWithoutTheRightToOperateIsRefusedWithAMessageThatNamesIt() {
        val users = UserManager(FileUserStore(temp.resolve("users.json")))
        val viewer = users.createUser("reader", setOf(UserRole.VIEWER))
        val server = tlsRepo.start(repository, users, upload())
        val address = "127.0.0.1:${server.port}"
        writeProfile(address, users.createToken(viewer.user.id, "t", null).secret)
        val project = SamplePlugin.copyTo(temp, "without-right")

        val result = runner(project, listOf("cringlePublish")).buildAndFail()
        assertTrue("Permission.OPERATE" in result.output, "the message has to name the right:\n${result.output}")
        assertFalse("\tat " in result.output, "the message has to stand on its own, without a stack trace:\n${result.output}")
        server.stop()
    }

    /** AC 3: a repository that is not there fails with a message that says so instead of a stack trace of gRPC. */
    @Test
    fun anUnreachableRepositoryFailsWithAMessageThatSaysSo() {
        val project = SamplePlugin.copyTo(temp, "unreachable")

        val result = runner(
            project,
            listOf("cringlePublish", "-P${CringlePlugin.PROPERTY_SERVER}=127.0.0.1:${closedPort()}", "-P${CringlePlugin.PROPERTY_FINGERPRINT}=${"0".repeat(64)}"),
        ).buildAndFail()
        assertTrue("not reachable" in result.output, "the message has to say that the server is not there:\n${result.output}")
        assertFalse("\tat " in result.output, "the message has to stand on its own, without a stack trace:\n${result.output}")
    }

    /**
     * AC 4: `--dryRun` builds and validates the package, names the target, the name and the version, and sends
     * nothing: the port in the build script is one no server listens on, so a connection would fail the build.
     */
    @Test
    fun dryRunNamesWhatItWouldPublishAndSendsNothing() {
        val project = SamplePlugin.copyTo(temp, "dry-run")
        val address = "127.0.0.1:${closedPort()}"
        SamplePlugin.replaceInBuildScript(
            project,
            "    name = \"acme-orders\"",
            "    publish { server = \"$address\" }\n    name = \"acme-orders\"",
        )

        val result = runner(project, listOf("cringlePublish", "--dryRun")).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePackage")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePublish")?.outcome, result.output)
        assertTrue("would publish acme-orders 1.2.0 to $address" in result.output, "the task has to name target, name and version:\n${result.output}")
        assertTrue(repository.list().isEmpty(), "nothing may reach a repository that is not there")
    }

    /**
     * AC 4: the token is in no message of the task. The build runs with `--debug`, which lowers the log level of
     * `java.util.logging` to FINEST: there the gRPC transport prints the headers of every call, the authorization
     * header of the publish call included. The token may therefore appear in that dump of the transport, but in no
     * message the plugin itself writes.
     */
    @Test
    fun theTokenIsInNoMessageOfTheTask() {
        val users = UserManager(FileUserStore(temp.resolve("users.json")))
        val operator = users.createUser("author", setOf(UserRole.OPERATOR))
        val token = users.createToken(operator.user.id, "quiet", null).secret
        val server = tlsRepo.start(repository, users, upload())
        val address = "127.0.0.1:${server.port}"
        writeProfile(address, token)
        val project = SamplePlugin.copyTo(temp, "quiet")
        publishCore()

        val result = runner(project, listOf("cringlePublish", "--debug"), forward = false).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePublish")?.outcome, excerpt(result.output))
        val elsewhere = result.output.lineSequence()
            .filter { it.contains(token) && !it.contains("OUTBOUND HEADERS") }
            .toList()
        assertTrue(
            elsewhere.isEmpty(),
            "the token may appear in the header dump of gRPC, but in no message of the plugin, yet it appears in " +
                elsewhere.joinToString("\n"),
        )
        server.stop()
    }

    /**
     * AC 4 (#72): in a build with `--info` the token is nowhere in the output, not in a message of the plugin and not in
     * anything the transport prints. Only `--debug` can show it, in the header dump of gRPC, see the test above. The
     * build validates against the dependencies of the repository and publishes, so the token is used by both.
     */
    @Test
    fun theTokenIsNowhereInTheOutputOfAnInfoBuild() {
        val users = UserManager(FileUserStore(temp.resolve("users.json")))
        val operator = users.createUser("author", setOf(UserRole.OPERATOR))
        val token = users.createToken(operator.user.id, "info", null).secret
        val server = tlsRepo.start(repository, users, upload())
        val address = "127.0.0.1:${server.port}"
        writeProfile(address, token)
        val project = SamplePlugin.copyTo(temp, "info")
        publishCore()

        val result = runner(project, listOf("cringlePublish", "--info"), forward = false).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePublish")?.outcome, excerpt(result.output))
        assertEquals(TaskOutcome.SUCCESS, result.task(":cringleValidate")?.outcome, excerpt(result.output))
        assertFalse(token in result.output, "the token appeared in the output of an --info build:\n${excerpt(result.output)}")
        server.stop()
    }
    /**
     * TLS (#37): a repository that is up but without a fingerprint to pin it to fails the task with a message that names
     * all four places the fingerprint comes from, and without a stack trace. Nothing is sent: there is no trust on first use.
     */
    @Test
    fun withoutAFingerprintTheMessageNamesTheFourPlaces() {
        val server = tlsRepo.start(repository, upload = upload())
        val address = "127.0.0.1:${server.port}"
        tlsRepo.writeProfile(address, null, fingerprint = null)
        val project = SamplePlugin.copyTo(temp, "without-fingerprint")
        publishCore()

        val result = runner(project, listOf("cringlePublish")).buildAndFail()
        for (place in listOf("-Pcringle.fingerprint", "cringle { publish { fingerprint", "CRINGLE_FINGERPRINT", "cli.json")) {
            assertTrue(place in result.output, "the message has to name '$place':\n${result.output}")
        }
        assertFalse("\tat " in result.output, "the message has to stand on its own, without a stack trace:\n${result.output}")
        assertTrue(repository.list().none { it.name == "acme-orders" }, "nothing may reach the repository without a fingerprint")
        server.stop()
    }

    /** TLS (#37): a repository whose key is not the pinned one is not accepted, and the message says what to check. */
    @Test
    fun aRepositoryWithAnotherKeyThanTheFingerprintIsRefused() {
        val server = tlsRepo.start(repository, upload = upload())
        val address = "127.0.0.1:${server.port}"
        val project = SamplePlugin.copyTo(temp, "wrong-fingerprint")
        publishCore()

        val result = runner(
            project,
            listOf("cringlePublish", "-P${CringlePlugin.PROPERTY_SERVER}=$address", "-P${CringlePlugin.PROPERTY_FINGERPRINT}=${"ab".repeat(32)}"),
        ).buildAndFail()
        assertTrue("not reachable" in result.output && "fingerprint" in result.output, "the message has to point at the fingerprint:\n${result.output}")
        assertFalse("\tat " in result.output, "the message has to stand on its own, without a stack trace:\n${result.output}")
        assertTrue(repository.list().none { it.name == "acme-orders" })
        server.stop()
    }

    /** TLS (#37): a text that is no SHA-256 fingerprint is refused before any connection, with the value in the message. */
    @Test
    fun aFingerprintThatIsNoFingerprintIsRefused() {
        val project = SamplePlugin.copyTo(temp, "bad-fingerprint")

        val result = runner(
            project,
            listOf("cringlePublish", "-P${CringlePlugin.PROPERTY_SERVER}=127.0.0.1:${closedPort()}", "-P${CringlePlugin.PROPERTY_FINGERPRINT}=not-a-fingerprint"),
        ).buildAndFail()
        assertTrue("not-a-fingerprint" in result.output && "SHA-256" in result.output, result.output)
    }

    /** TLS (#37): the property of the command line wins over the profile; a wrong value in the profile does not matter then. */
    @Test
    fun theFingerprintOfTheCommandLineWinsOverTheProfile() {
        val server = tlsRepo.start(repository, upload = upload())
        val address = "127.0.0.1:${server.port}"
        tlsRepo.writeProfile(address, null, fingerprint = "cd".repeat(32))
        val project = SamplePlugin.copyTo(temp, "fingerprint-property")
        publishCore()

        val result = runner(project, listOf("cringlePublish", "-P${CringlePlugin.PROPERTY_FINGERPRINT}=${tlsRepo.fingerprint}")).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePublish")?.outcome, result.output)
        assertEquals("acme-orders", stored(address).name)
        server.stop()
    }

    /** TLS (#37): `cringle { publish { fingerprint = … } }` in the build script pins the repository, as `server` names it. */
    @Test
    fun theBlockOfTheBuildScriptCanPinTheFingerprint() {
        val server = tlsRepo.start(repository, upload = upload())
        val address = "127.0.0.1:${server.port}"
        tlsRepo.writeProfile(address, null, fingerprint = null)
        val project = SamplePlugin.copyTo(temp, "fingerprint-block")
        SamplePlugin.replaceInBuildScript(
            project,
            "    name = \"acme-orders\"",
            "    publish { fingerprint = \"${tlsRepo.fingerprint}\" }\n    name = \"acme-orders\"",
        )
        publishCore()

        val result = runner(project, listOf("cringlePublish")).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":cringlePublish")?.outcome, result.output)
        assertEquals("acme-orders", stored(address).name)
        server.stop()
    }

    /**
     * The `acme-core` the sample depends on. A repository only takes a package whose dependencies it can resolve, so
     * the fixture has to hold the plugin the sample names.
     */
    private fun publishCore() {
        val file = Files.createTempFile(temp, "acme-core", ".cringle")
        Files.newOutputStream(file).use { out ->
            PackageWriter.writePlugin(PluginManifest("acme-core", "1.0.0"), emptyMap(), emptyMap(), emptyMap(), out)
        }
        repository.publish(file)
    }

    /** The folder the server collects an upload in, a folder of the temporary directory of the test. */
    private fun upload(): Path = temp.resolve("upload").also { it.createDirectories() }

    /** The metadata of the published version as the repository reports it to another client. */
    private fun stored(address: String, token: String? = null): PackageEntry =
        tlsRepo.client(address, token).use { client -> runBlocking { client.get("acme-orders", "1.2.0") } }

    /** The profile of `cringle login`, in the format the CLI writes it: the address and the token, or `null`. */
    private fun writeProfile(server: String, token: String?) {
        tlsRepo.writeProfile(server, token)
    }

    /** A home without a profile, for the tests that take the address and the token from the environment. */
    private fun emptyHome(): Path = home.resolve("empty").also { it.createDirectories() }

    /** A port no server listens on, so a connection to it is refused. */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    /**
     * A runner for the copy in [project]. Without an [environment] of its own the build runs in the daemon of this
     * build, which is what the other tests do; with one it runs in a forked process that has exactly that environment.
     */
    private fun runner(
        project: Path,
        arguments: List<String>,
        environment: Map<String, String>? = null,
        forward: Boolean = true,
    ): GradleRunner = GradleRunner.create()
        .withProjectDir(project.toFile())
        .withPluginClasspath(TestKitClasspath.forNestedBuild)
        .withArguments(
            *arguments.toTypedArray(),
            "-PcringleRepo=${SamplePlugin.localRepo}",
            "-PcringleVersion=${SamplePlugin.version}",
            "-g",
            required("cringle.gradleUserHome"),
            // This build has resolved every library of the nested build into this cache before the test starts, so the
            // nested build needs no network.
            "--offline",
        )
        .apply {
            if (forward) forwardOutput()
            // TestKit refuses an environment together with debug mode, so a build with its own environment always runs
            // in a forked process. That is the point: a daemon keeps the environment it was started with, and only a
            // new process has the environment of this test.
            if (environment != null) withEnvironment(environment)
        }

    /** The first lines of an output, for a message that stays readable. */
    private fun excerpt(output: String): String = output.lineSequence().take(40).joinToString("\n")

    private fun required(name: String): String =
        checkNotNull(System.getProperty(name)) { "the system property '$name' is not set" }
}
