// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import cringle.management.test.ManagementTls
import cringle.contract.LogEntry
import cringle.contract.LogLevel
import cringle.daemon.Daemon
import cringle.engine.CringleHome
import cringle.engine.drivers.LoggingService
import cringle.management.ManagementCore
import cringle.management.ManagementServer
import cringle.management.ManagementStore
import cringle.packaging.Blueprint
import cringle.packaging.FabricConfig
import cringle.repository.PackageRepository
import cringle.repository.RepositoryServer
import cringle.router.RouterServer
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import cringle.testkit.MarkerFixture
import cringle.testkit.TestProjectBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Runs the commands against a real ManagementServer with user management, a Daemon, a Repository and a Router. */
class CliTest {
    private lateinit var dir: Path
    private lateinit var home: Path
    private lateinit var cliHome: Path
    private lateinit var daemon: Daemon
    private lateinit var tls: ManagementTls
    private lateinit var server: ManagementServer
    private lateinit var adminToken: String
    private lateinit var users: UserManager
    private val closeables = ArrayList<AutoCloseable>()

    private class Result(val code: Int, val out: String, val err: String)

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("cringle-cli-test")
        home = Files.createDirectories(dir.resolve("home"))
        cliHome = dir.resolve("cli-home")
        tls = ManagementTls(dir.resolve("tls"))
        daemon = Daemon(home).start()
        closeables += daemon
        tls.trust(daemon)
        val repositoryServer = tls.startRepository(PackageRepository(dir.resolve("repo")))
        closeables += AutoCloseable { repositoryServer.stop() }
        val router = tls.startRouter(dir.resolve("registry.json"))
        closeables += AutoCloseable { router.stop() }
        users = UserManager(FileUserStore(dir.resolve("users.json")))
        adminToken = users.bootstrap()!!
        val core = tls.core(ManagementStore(dir.resolve("state.json")), "127.0.0.1:${repositoryServer.port}", null, "127.0.0.1:${router.port}")
        server = ManagementServer(core, users = users, recoverOnStart = false).start()
        closeables += server
    }

    @AfterEach
    fun tearDown() {
        closeables.reversed().forEach { runCatching { it.close() } }
        repeat(10) {
            if (runCatching { dir.toFile().deleteRecursively() }.getOrDefault(false) || !Files.exists(dir)) return
            Thread.sleep(200)
        }
    }

    private val address get() = "127.0.0.1:${server.port}"

    private fun cli(
        vararg args: String,
        token: String? = adminToken,
        stdin: String = "",
        server: Boolean = true,
        insecure: Boolean = true,
        extraEnvironment: Map<String, String> = emptyMap(),
    ): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val env = buildMap {
            put("CRINGLE_HOME", cliHome.toString())
            if (token != null) put("CRINGLE_TOKEN", token)
            putAll(extraEnvironment)
        }
        // the test server is not encrypted, which the CLI only accepts when it is told to
        val list = (if (server) listOf("--server", address) else emptyList()) + (if (insecure) listOf("--insecure-dev-mode") else emptyList()) + args.toList()
        val code = Cli(PrintStream(out, true), PrintStream(err, true), ByteArrayInputStream(stdin.toByteArray()), env).run(list)
        return Result(code, out.toString().trim(), err.toString().trim())
    }

    private fun ok(vararg args: String, token: String? = adminToken): Result = cli(*args, token = token).also { assertEquals(0, it.code, "cringle ${args.joinToString(" ")}: ${it.err}") }

    private fun json(r: Result) = Json.parseToJsonElement(r.out)

    private fun field(o: JsonObject, name: String) = (o[name] as JsonPrimitive).content

    private fun machine() = ok("machine", "add", "m1", "127.0.0.1:${daemon.port}")

    /** #57: `cringle --version` prints the version of the installation; it needs no server and no switch. */
    @Test
    fun versionIsPrintedWithoutAServerAndComesFromTheVersionFileOfTheDistribution() {
        val out = ByteArrayOutputStream()
        val code = Cli(PrintStream(out, true), PrintStream(ByteArrayOutputStream()), ByteArrayInputStream(ByteArray(0)), emptyMap(), "1.2.3").run(listOf("--version"))
        assertEquals(0, code)
        assertEquals("cringle 1.2.3", out.toString().trim())
        assertTrue(cli("--help", server = false).out.contains("--version"))

        val distribution = Files.createDirectories(dir.resolve("distribution"))
        assertEquals(Distribution.LOCAL_VERSION, Distribution.version(null))
        assertEquals(Distribution.LOCAL_VERSION, Distribution.version(distribution.toString()), "a directory without VERSION is a local build")
        Files.writeString(distribution.resolve("VERSION"), "4.5.6-rc.1\n")
        assertEquals("4.5.6-rc.1", Distribution.version(distribution.toString()))
    }

    @Test
    fun helpDocumentsEveryCommand() {
        val help = cli("--help", server = false)
        assertEquals(0, help.code)
        for (c in COMMANDS) {
            assertTrue(help.out.contains(c.name), "cringle --help does not mention '${c.name}'")
            val single = cli(*c.words.toTypedArray(), "--help", server = false)
            assertEquals(0, single.code, c.name)
            assertTrue(single.out.contains(c.summary), c.name)
        }
        assertEquals(0, cli("help", "engine", server = false).code)
        assertTrue(cli("help", "engine", server = false).out.contains("engine create"))
    }

    @Test
    fun wrongUsageIsReportedWithExitCodeTwo() {
        assertEquals(2, cli("nonsense", server = false).code)
        val missing = cli("machine", "add", "only-one")
        assertEquals(2, missing.code)
        assertTrue(missing.err.contains("usage: cringle machine add <id> <daemon-address>"))
        assertEquals(2, cli("engine", "list", "--bogus").code)
        assertEquals(2, cli("logs", "m1", server = false).code)
        val noServer = cli("machine", "list", server = false)
        assertEquals(2, noServer.code)
        assertTrue(noServer.err.contains("no server address"))
    }

    @Test
    fun loginStoresTheProfileAndLogoutRemovesTheToken() {
        val bad = cli("login", "--token", "wrong", token = null)
        assertEquals(1, bad.code)
        assertFalse(Files.exists(cliHome.resolve("cli.json")))

        val login = cli("login", token = null, stdin = "$adminToken\n")
        assertEquals(0, login.code, login.err)
        assertTrue(login.out.contains("as admin"))
        val profile = Files.readString(cliHome.resolve("cli.json"))
        assertTrue(profile.contains(address))

        // the stored profile is enough now: no --server, no token in the environment
        val out = ByteArrayOutputStream()
        val code = Cli(PrintStream(out, true), PrintStream(ByteArrayOutputStream()), ByteArrayInputStream(ByteArray(0)), mapOf("CRINGLE_HOME" to cliHome.toString())).run(listOf("whoami", "--json"))
        assertEquals(0, code)
        assertEquals("admin", field(Json.parseToJsonElement(out.toString()) as JsonObject, "name"))

        assertEquals(0, cli("logout", server = false, token = null).code)
        assertFalse(Files.readString(cliHome.resolve("cli.json")).contains(adminToken))
        val after = cli("whoami", token = null)
        assertEquals(1, after.code)
        assertTrue(after.err.contains("cringle login"))
    }

    @Test
    fun withoutTheInsecureSwitchTheCliDoesNotConnect() {
        val refused = cli("machine", "list", insecure = false)
        assertEquals(2, refused.code)
        assertTrue(refused.err.contains("--insecure-dev-mode"), refused.err)
        assertEquals(0, cli("machine", "list").code, "the switch allows it")
        assertEquals(0, cli("machine", "list", insecure = false, extraEnvironment = mapOf("CRINGLE_INSECURE_DEV_MODE" to "1")).code, "so does the environment")
        assertEquals(2, cli("machine", "list", insecure = false, extraEnvironment = mapOf("CRINGLE_INSECURE_DEV_MODE" to "0")).code)
        // commands that do not connect need no switch
        assertEquals(0, cli("--help", server = false, insecure = false).code)
        assertEquals(0, cli("logout", server = false, token = null, insecure = false).code)

        // login without the switch does not connect and writes nothing
        Files.deleteIfExists(cliHome.resolve("cli.json"))
        val login = cli("login", token = null, stdin = "$adminToken\n", insecure = false)
        assertEquals(2, login.code)
        assertFalse(Files.exists(cliHome.resolve("cli.json")))

        // login with the switch stores it; the stored profile needs no switch any more
        assertEquals(0, cli("login", token = null, stdin = "$adminToken\n").code)
        assertTrue(Files.readString(cliHome.resolve("cli.json")).contains("\"insecure\": true"))
        assertEquals(0, cli("machine", "list", server = false, token = null, insecure = false).code)
        // logging out removes the token, not the decision
        assertEquals(0, cli("logout", server = false, token = null, insecure = false).code)
        assertTrue(Files.readString(cliHome.resolve("cli.json")).contains("\"insecure\": true"))
    }

    @Test
    fun loginReadsTheTokenFromAFileOrStandardInputAndWarnsAboutTheArgument() {
        val file = dir.resolve("token.txt")
        Files.writeString(file, "$adminToken\n")
        val fromFile = cli("login", "--token-file", file.toString(), token = null)
        assertEquals(0, fromFile.code, fromFile.err)
        assertFalse(fromFile.err.contains("warning"), fromFile.err)
        assertEquals(0, cli("login", token = null, stdin = "$adminToken\n").code)

        val argument = cli("login", "--token", adminToken, token = null)
        assertEquals(0, argument.code, argument.err)
        assertTrue(argument.err.contains("warning") && argument.err.contains("history"), argument.err)
        assertFalse(argument.err.contains(adminToken), "the warning must not repeat the token")

        assertEquals(2, cli("login", "--token", adminToken, "--token-file", file.toString(), token = null).code)
        assertEquals(2, cli("login", "--token-file", dir.resolve("missing.txt").toString(), token = null).code)
        val empty = dir.resolve("empty.txt")
        Files.writeString(empty, "\n")
        assertEquals(2, cli("login", "--token-file", empty.toString(), token = null).code)
    }

    @Test
    fun theProfileIsWrittenWithOwnerOnlyRights() {
        val profile = cliHome.resolve("cli.json")
        assertEquals(0, cli("login", token = null, stdin = "$adminToken\n").code)
        assertOwnerOnly(profile)
        // a second login replaces the file and it keeps the rights
        assertEquals(0, cli("login", token = null, stdin = "$adminToken\n").code)
        assertOwnerOnly(profile)
        assertEquals(0, cli("logout", server = false, token = null).code)
        assertOwnerOnly(profile)
        assertEquals(listOf("cli.json"), Files.list(cliHome).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    /** Only the owner may access [file]: `rw-------` on POSIX, one ACL entry for the owner on Windows. */
    private fun assertOwnerOnly(file: Path) {
        val views = file.fileSystem.supportedFileAttributeViews()
        when {
            "posix" in views -> assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
            "acl" in views -> {
                val acl = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView::class.java).acl
                assertEquals(1, acl.size, "the access list has other entries: $acl")
                assertEquals(Files.getOwner(file), acl.single().principal())
            }
            else -> org.junit.jupiter.api.Assertions.fail<Unit>("no POSIX permissions and no ACLs on this file system: $views")
        }
    }

    @Test
    fun usersGroupsAndTokensAreManagedAndRolesAreEnforced() {
        val created = json(ok("user", "create", "vera", "--role", "viewer", "--json")) as JsonObject
        val id = field(created, "id")
        ok("group", "create", "ops", "--role", "operator")
        assertTrue(ok("group", "list").out.contains("ops"))
        assertTrue(ok("user", "list").out.contains("vera"))

        val token = field(json(ok("token", "create", id, "--label", "test", "--json")) as JsonObject, "token")
        assertEquals("vera", field(json(ok("whoami", "--json", token = token)) as JsonObject, "name"))
        assertTrue(ok("engine", "list", token = token).out.contains("no engines"))
        val denied = cli("machine", "add", "x", "127.0.0.1:1", token = token)
        assertEquals(1, denied.code)
        assertTrue(denied.err.contains("does not allow"))

        val tokenId = ((json(ok("token", "list", id, "--json")) as JsonArray)[0] as JsonObject).let { field(it, "id") }
        ok("token", "revoke", tokenId)
        assertEquals(1, cli("whoami", token = token).code)
        ok("user", "delete", id)
        assertFalse(ok("user", "list").out.contains("vera"))
    }

    @Test
    fun machinesAreAddedListedAndRemoved() {
        val added = json(cli("machine", "add", "m1", "127.0.0.1:${daemon.port}", "--json").also { assertEquals(0, it.code, it.err) }) as JsonObject
        assertEquals("true", field(added, "reachable"))
        val list = ok("machine", "list")
        assertTrue(list.out.lines().first().startsWith("MACHINE"))
        assertTrue(list.out.contains("m1"))
        assertEquals(1, cli("machine", "add", "m1", "127.0.0.1:${daemon.port}").code)
        ok("machine", "remove", "m1")
        assertEquals("no machines", ok("machine", "list").out)
    }

    @Test
    fun enginesAreCreatedStartedInspectedTaggedStoppedAndDeleted() {
        machine()
        val created = json(ok("engine", "create", "m1", "--id", "e1", "--name", "First", "--role", "worker", "--label", "zone=a", "--json")) as JsonObject
        assertEquals("worker", ((created["roles"] as JsonArray)[0] as JsonPrimitive).content)
        assertEquals("a", (((created["labels"]) as JsonObject)["zone"] as JsonPrimitive).content)
        ok("engine", "start", "m1", "e1")
        val status = ok("engine", "status", "m1", "e1")
        assertTrue(status.out.contains("state") && status.out.contains("running"))
        assertTrue(ok("engine", "list", "m1").out.contains("e1"))
        assertTrue(ok("engine", "list").out.contains("First"))
        ok("engine", "tag", "m1", "e1", "--role", "db")
        assertTrue(ok("engine", "list").out.contains("db"))
        assertTrue(ok("engine", "stop", "m1", "e1").out.contains("stopped"))
        assertEquals(1, cli("engine", "start", "m1", "nope").code)
        ok("engine", "delete", "m1", "e1")
        assertEquals("no engines", ok("engine", "list").out)
    }

    @Test
    fun repositoryPublishListDownloadAndTrust() {
        val plugin = MarkerFixture.plugin(Files.createDirectories(dir.resolve("build")))
        val published = ok("repo", "publish", plugin.file.toString())
        assertTrue(published.out.contains("acme-demo"))
        assertEquals(1, cli("repo", "publish", plugin.file.toString()).code) // versions are immutable
        assertEquals(2, cli("repo", "publish", dir.resolve("missing.zip").toString()).code)
        assertTrue(ok("repo", "list").out.contains("untrusted"))
        assertTrue(ok("repo", "list", "--kind", "project").out.contains("empty"))
        assertTrue(ok("repo", "versions", "acme-demo").out.contains("1.0.0"))
        assertTrue(ok("repo", "trust", "acme-demo", "trusted").out.contains("trusted"))
        assertEquals(2, cli("repo", "trust", "acme-demo", "maybe").code)
        val target = dir.resolve("downloaded.zip")
        ok("repo", "download", "acme-demo", "1.0.0", target.toString())
        assertEquals(Files.readAllBytes(plugin.file).toList(), Files.readAllBytes(target).toList())
        assertEquals(1, cli("repo", "download", "acme-demo", "9.9.9", dir.resolve("x.zip").toString()).code)
        assertFalse(Files.exists(dir.resolve("x.zip")))
    }

    @Test
    fun routersAreAddedListedAndRemoved() {
        assertEquals("no remote routers", ok("router", "list").out)
        // the router of the management server runs mTLS and wants the key of the remote router confirmed
        val remote = tls.startRouter(dir.resolve("remote-registry.json"))
        closeables += AutoCloseable { remote.stop() }
        val remoteAddress = "127.0.0.1:${remote.port}"
        assertEquals(1, cli("router", "add", remoteAddress).code, "without the fingerprint the router is not added")
        ok("router", "add", remoteAddress, "--fingerprint", remote.identity!!.publicKeyFingerprint)
        assertTrue(ok("router", "list").out.contains(remoteAddress))
        ok("router", "remove", remoteAddress)
        assertEquals("no remote routers", ok("router", "list").out)
    }

    @Test
    fun deployFabricsLogsCacheAndRecoverWorkThroughTheCli() {
        machine()
        ok("engine", "create", "m1", "--id", "e1", "--role", "worker")
        ok("engine", "start", "m1", "e1")

        val work = Files.createDirectories(dir.resolve("build"))
        val marker = dir.resolve("marker.txt")
        val plugin = MarkerFixture.plugin(work)
        val project = TestProjectBuilder("demo", "0.1.0")
            .dependency("acme-demo", "^1.0.0")
            .blueprint(Blueprint("main", listOf(MarkerFixture.block("b1", marker)), emptyList()))
            .fabric(FabricConfig("main", 1, listOf("worker"), emptyMap()))
            .build(work, listOf(plugin.pkg))
        ok("repo", "publish", plugin.file.toString())
        ok("repo", "publish", project.file.toString())
        ok("repo", "trust", "acme-demo", "trusted")

        val deployed = json(ok("deploy", "demo", "--json")) as JsonObject
        assertEquals("0.1.0", field(deployed, "version"))
        assertEquals("started", Files.readString(marker))
        val fabrics = ok("fabric", "list")
        assertTrue(fabrics.out.contains("demo-main-1") && fabrics.out.contains("running"))
        assertTrue(ok("fabric", "status", "m1", "e1", "demo-main-1").out.contains("b1"))
        assertTrue(ok("fabric", "stop", "m1", "e1", "demo-main-1").out.contains("stopped"))
        assertEquals("stopped", Files.readString(marker))
        assertTrue(ok("fabric", "start", "m1", "e1", "demo-main-1").out.contains("running"))
        assertEquals(2, cli("fabric", "list", "m1").code)
        ok("fabric", "remove", "m1", "e1", "demo-main-1")
        assertEquals("no fabrics", ok("fabric", "list").out)
        ok("deploy", "demo", "--no-start")
        assertTrue(ok("fabric", "list", "m1", "e1").out.contains("stopped"))
        assertTrue(ok("undeploy", "demo").out.contains("demo-main-1"))
        assertTrue(ok("cache", "cleanup", "m1").out.contains("acme-demo"))
    }

    @Test
    fun logsCanBeFilteredAndCacheAndUndeployWork() {
        machine()
        ok("engine", "create", "m1", "--id", "e1")
        ok("engine", "start", "m1", "e1")
        val t0 = Instant.now().minusSeconds(3600)
        val service = LoggingService(CringleHome.engineDir(home, "e1"))
        service.append(LogEntry(t0, "shop", "a", LogLevel.INFO, "one"))
        service.append(LogEntry(t0.plusSeconds(1), "shop", "b", LogLevel.ERROR, "two"))
        service.append(LogEntry(t0.plusSeconds(2), "other", "a", LogLevel.WARN, "three"))

        val all = ok("logs")
        assertEquals(listOf("one", "two", "three"), all.out.lines().map { it.substringAfter(": ") })
        assertTrue(all.out.lines()[1].contains("ERROR") && all.out.lines()[1].contains("m1/e1 shop/b"))
        assertEquals(listOf("one", "two"), ok("logs", "--fabric", "shop").out.lines().map { it.substringAfter(": ") })
        assertEquals(listOf("two"), ok("logs", "m1", "e1", "--level", "error").out.lines().map { it.substringAfter(": ") })
        assertEquals(listOf("three"), ok("logs", "--limit", "1").out.lines().map { it.substringAfter(": ") })
        assertEquals("no log entries", ok("logs", "--since", "1m").out)
        assertEquals(3, (json(ok("logs", "--json")) as JsonArray).size)
        assertEquals(2, cli("logs", "--level", "loud").code)
        assertEquals(2, cli("logs", "--since", "yesterday").code)

        assertTrue(ok("cache", "cleanup").out.contains("removed"))
        assertTrue(ok("undeploy", "nothing").out.contains("removed"))
        assertTrue(ok("recover").out.contains("enginesStarted"))
    }
}
