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
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Runs the commands against a real ManagementServer with user management, a Daemon, a Repository and a Router. */
@Tag("integration")
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
        daemon = Daemon(home, config = cringle.common.config.ConfigStore(home.resolve("config").resolve("cringle.conf"))).start()
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

    /** The fingerprint of the key of the management server, which the CLI is pinned to. */
    private val fingerprint get() = server.core.identity.publicKeyFingerprint

    private fun cli(
        vararg args: String,
        token: String? = adminToken,
        stdin: String = "",
        server: Boolean = true,
        pinned: Boolean = true,
        extraEnvironment: Map<String, String> = emptyMap(),
    ): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val env = buildMap {
            put("CRINGLE_HOME", cliHome.toString())
            if (token != null) put("CRINGLE_TOKEN", token)
            if (pinned) put("CRINGLE_FINGERPRINT", fingerprint)
            putAll(extraEnvironment)
        }
        // a login pins the server too, unless the test wants to see how it asks for the fingerprint
        val pin = if (pinned && args.firstOrNull() == "login" && "--fingerprint" !in args && "--yes" !in args) listOf("--fingerprint", fingerprint) else emptyList()
        val list = (if (server) listOf("--server", address) else emptyList()) + args.toList() + pin
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
    fun theCliConnectsOnlyPinnedToTheFingerprintOfTheServer() {
        val noPin = cli("machine", "list", pinned = false)
        assertEquals(2, noPin.code)
        assertTrue(noPin.err.contains("fingerprint"), noPin.err)
        assertEquals(0, cli("machine", "list").code, "with the fingerprint it connects")
        val wrong = cli("machine", "list", pinned = false, extraEnvironment = mapOf("CRINGLE_FINGERPRINT" to "ab".repeat(32)))
        assertEquals(1, wrong.code, "a server with another key is not accepted")
        assertTrue(wrong.err.contains("cannot reach"), wrong.err)
        val malformed = cli("machine", "list", pinned = false, extraEnvironment = mapOf("CRINGLE_FINGERPRINT" to "not-a-fingerprint"))
        assertEquals(2, malformed.code)
        // commands that do not connect need no fingerprint
        assertEquals(0, cli("--help", server = false, pinned = false).code)
        assertEquals(0, cli("logout", server = false, token = null, pinned = false).code)
    }

    @Test
    fun loginAsksForTheFingerprintAndStoresNothingWhenItDoesNotMatch() {
        val file = cliHome.resolve("cli.json")
        Files.deleteIfExists(file)

        // without --fingerprint and --yes the login shows what the server presents and stops
        val shown = cli("login", token = null, stdin = "$adminToken\n", pinned = false)
        assertEquals(2, shown.code)
        assertTrue(shown.err.contains(fingerprint), "the message has to show the fingerprint of the server:\n${shown.err}")
        assertFalse(Files.exists(file))

        // another key than the one the operator expects: exit code 2, both values, nothing stored
        val other = "cd".repeat(32)
        val mismatch = cli("login", "--fingerprint", other, token = null, stdin = "$adminToken\n", pinned = false)
        assertEquals(2, mismatch.code)
        assertTrue(mismatch.err.contains(fingerprint) && mismatch.err.contains(other), mismatch.err)
        assertFalse(Files.exists(file))

        // a malformed value is a usage error as well
        assertEquals(2, cli("login", "--fingerprint", "xyz", token = null, stdin = "$adminToken\n", pinned = false).code)
        assertFalse(Files.exists(file))

        // the right fingerprint stores server, token and fingerprint; the profile alone is enough afterwards
        assertEquals(0, cli("login", "--fingerprint", fingerprint, token = null, stdin = "$adminToken\n", pinned = false).code)
        assertTrue(Files.readString(file).contains("\"fingerprint\": \"$fingerprint\""))
        assertEquals(0, cli("machine", "list", server = false, token = null, pinned = false).code)
        // logging out removes the token, not the pin
        assertEquals(0, cli("logout", server = false, token = null, pinned = false).code)
        assertTrue(Files.readString(file).contains("\"fingerprint\": \"$fingerprint\""))

        // --yes accepts the fingerprint the server shows
        Files.deleteIfExists(file)
        assertEquals(0, cli("login", "--yes", token = null, stdin = "$adminToken\n", pinned = false).code)
        assertTrue(Files.readString(file).contains(fingerprint))
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

    /** #295: a registry trusts the public key of another one; here the server trusts its own key under another name, which is what two sites do with each other's. */
    @Test
    fun aTokenOfATrustedRegistryLogsInAsUserAtRegistry() {
        val key = json(ok("registry", "key", "--json")) as JsonObject
        val pem = field(key, "publicKey")
        assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----"))
        val file = dir.resolve("site-b.pem")
        Files.writeString(file, pem)
        assertTrue(ok("registry", "list").out.contains("no trusted registry"))
        val trusted = json(ok("registry", "trust", "site-b", "--key-file", file.toString(), "--role", "viewer", "--json")) as JsonObject
        assertEquals(field(key, "fingerprint"), field(trusted, "fingerprint"))
        assertTrue(ok("registry", "list").out.contains("site-b"))

        val token = field(json(ok("registry", "issue-token", "alice", "--as", "site-b", "--ttl", "2h", "--json")) as JsonObject, "token")
        assertTrue(token.startsWith("fed1."))
        assertEquals("alice@site-b", field(json(ok("whoami", "--json", token = token)) as JsonObject, "name"))
        // the rights of the registry: a viewer may read and may not change
        assertTrue(ok("machine", "list", token = token).out.isNotEmpty())
        assertTrue(cli("machine", "add", "x", "127.0.0.1:1", token = token).code != 0)
        ok("registry", "grant", "site-b", "operator", "--scope", "machine:m9")
        assertTrue(ok("registry", "list").out.contains("operator@machine:m9"))
        ok("registry", "revoke", "site-b", "operator", "--scope", "machine:m9")

        // a name that was not trusted, a bad key and a wrong lifetime
        val bogus = field(json(ok("registry", "issue-token", "alice", "--as", "other", "--json")) as JsonObject, "token")
        assertTrue(cli("whoami", token = bogus).code != 0)
        Files.writeString(dir.resolve("bad.pem"), "not a key")
        assertTrue(cli("registry", "trust", "x", "--key-file", dir.resolve("bad.pem").toString(), token = adminToken).code != 0)
        assertTrue(cli("registry", "issue-token", "alice", "--as", "site-b", "--ttl", "90d", token = adminToken).code != 0)

        ok("registry", "untrust", "site-b")
        assertTrue(cli("whoami", token = token).code != 0, "the token of a registry that is not trusted any more")
        assertTrue(ok("registry", "list").out.contains("no trusted registry"))
    }

    @Test
    fun aTokenCanBePrintedAsLoginLinkAndQrCodeInTheTerminal() {
        val id = field(json(ok("user", "create", "quentin", "--role", "viewer", "--json")) as JsonObject, "id")
        val plain = ok("token", "create", id).out
        assertFalse(plain.contains("loginLink") || plain.contains("▀") || plain.contains("█"), "without the options the output is unchanged")

        val shown = ok("token", "create", id, "--qr", "--web-url", "https://h.example:1/").out
        val token = Regex("token: (crt_\\S+)").find(shown)!!.groupValues[1]
        assertTrue(shown.contains("loginLink: https://h.example:1/login#token=$token"), shown)
        val block = shown.lines().filter { l -> l.isNotEmpty() && l.all { it in "▀▄█ " } }
        assertTrue(block.size >= 10 && block.all { it.length == block.first().length }, shown)

        val linkOnly = ok("token", "create", id, "--web-url", "https://h.example:1").out
        assertTrue(linkOnly.contains("loginLink: https://h.example:1/login#token=") && !linkOnly.contains("█"))
        val asJson = json(ok("token", "create", id, "--qr", "--web-url", "https://h.example:1", "--json")).let { (it as? JsonArray)?.get(0) as? JsonObject ?: it as JsonObject }
        assertTrue(field(asJson, "loginLink").startsWith("https://h.example:1/login#token=crt_"))

        val noUrl = cli("token", "create", id, "--qr", token = adminToken)
        assertTrue(noUrl.code != 0 && noUrl.err.contains("--qr needs --web-url"), noUrl.err)
        assertTrue(cli("token", "create", id, "--web-url", "http://h.example", token = adminToken).code != 0)
        assertTrue(cli("token", "create", id, "--web-url", "https://h.example/path", token = adminToken).code != 0)
    }

    /** #316: the settings of a machine through the management server, with the rights of the caller. */
    @Test
    fun theSettingsOfAMachineAreReadAndChangedThroughTheManagementServer() {
        machine()
        val list = ok("config", "list", "m1").out
        assertTrue(list.contains("management.web.port") && list.contains("8443") && list.contains("repository.port"), list)
        val set = json(ok("config", "set", "m1", "management.web.port", "9443", "--json")) as JsonObject
        assertEquals("9443", field(set, "value"))
        assertEquals("false", field(set, "restartRequired"))
        assertTrue(ok("config", "get", "m1", "management.web.port").out.contains("9443"))
        // without the machine the settings of the machine local are meant: it is not registered here
        assertTrue(cli("config", "get", "management.web.port", token = adminToken).code != 0)
        val daemonPort = json(ok("config", "set", "m1", "daemon.port", "7411", "--json")) as JsonObject
        assertEquals("true", field(daemonPort, "restartRequired"))
        assertTrue(field(daemonPort, "note").contains("daemon has to be restarted"))
        val reset = json(ok("config", "unset", "m1", "management.web.port", "--json")) as JsonObject
        assertEquals("8443", field(reset, "value"))
        assertEquals("false", field(reset, "set"))
        assertTrue(cli("config", "set", "m1", "daemon.port", "70000", token = adminToken).code != 0)
        assertTrue(cli("config", "set", "m1", "no.such.key", "1", token = adminToken).code != 0)

        // a viewer reads and does not change; the role admin for the function config only changes the settings
        val viewer = json(ok("user", "create", "vera", "--role", "viewer", "--json")) as JsonObject
        val viewerToken = field(json(ok("token", "create", field(viewer, "id"), "--json")) as JsonObject, "token")
        assertTrue(ok("config", "list", "m1", token = viewerToken).out.contains("bind"))
        assertTrue(cli("config", "set", "m1", "bind", "all", token = viewerToken).code != 0)
        val keeper = json(ok("user", "create", "karl", "--role", "viewer", "--json")) as JsonObject
        ok("user", "grant", field(keeper, "id"), "admin", "--scope", "function:config")
        val keeperToken = field(json(ok("token", "create", field(keeper, "id"), "--json")) as JsonObject, "token")
        assertEquals("all", field(json(ok("config", "set", "m1", "bind", "all", "--json", token = keeperToken)) as JsonObject, "value"))
        assertTrue(cli("machine", "add", "x", "127.0.0.1:1", token = keeperToken).code != 0, "that role does not reach beyond the settings")
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
        assertTrue(Regex("fingerprint\\W+[0-9a-f]{64}").containsMatchIn(status.out), status.out)
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
            .blueprint(
                Blueprint(
                    "main", listOf(MarkerFixture.block("b1", marker)), emptyList(),
                    assertions = listOf(cringle.packaging.FabricRunning(), cringle.packaging.BlockRunning("b1")),
                ),
            )
            .fabric(FabricConfig("main", 1, listOf("worker"), emptyMap()))
            .build(work, listOf(plugin.pkg))
        ok("repo", "publish", plugin.file.toString())
        ok("repo", "publish", project.file.toString())
        ok("repo", "trust", "acme-demo", "trusted")

        val deployed = json(ok("deploy", "demo", "--json")) as JsonObject
        assertEquals("0.1.0", field(deployed, "version"))
        assertEquals("first deploy", field(deployed, "strategy"))
        assertEquals("started", Files.readString(marker))
        val fabrics = ok("fabric", "list")
        assertTrue(fabrics.out.contains("demo-main-1") && fabrics.out.contains("running"))
        assertTrue(ok("fabric", "status", "m1", "e1", "demo-main-1").out.contains("b1"))
        // assertions (#293): the list says it in one word, the status lists them, --json has them
        assertTrue(Regex("CHECKS").containsMatchIn(fabrics.out) && fabrics.out.lines().any { it.contains("demo-main-1") && it.trimEnd().endsWith("ok") }, fabrics.out)
        val status = ok("fabric", "status", "m1", "e1", "demo-main-1").out
        assertTrue(status.contains("fabric-running") && status.contains("block-running:b1") && status.contains("block b1 is running"), status)
        val assertions = (json(ok("fabric", "status", "m1", "e1", "demo-main-1", "--json")) as JsonObject)["assertions"] as JsonArray
        assertEquals(listOf("fabric-running", "block-running:b1"), assertions.map { field(it as JsonObject, "assertion") })
        assertEquals(listOf("ok", "ok"), assertions.map { field(it as JsonObject, "state") })
        // metrics (#191)
        assertTrue(ok("metrics").out.contains("e1") && ok("metrics").out.contains("HEAPUSEDMB"))
        assertTrue(ok("metrics", "--fabrics").out.contains("demo-main-1"))
        assertTrue(!ok("metrics", "--fabrics", "--fabric", "other").out.contains("demo-main-1"))
        val metrics = json(ok("metrics", "m1", "e1", "--fabrics", "--json")) as JsonArray
        assertEquals("demo-main-1", field(metrics.single() as JsonObject, "fabric"))
        assertEquals(2, cli("metrics", "m1").code)
        assertEquals(2, cli("metrics", "--fabrics", "--tethers").code)
        // bindings of service dependencies (#171)
        assertTrue(ok("bind", "other", "orders", "demo-main-1").out.contains("demo-main-1"))
        assertTrue(ok("bindings").out.contains("orders") && ok("bindings", "other").out.contains("demo-main-1"))
        assertEquals("no bindings", ok("bindings", "demo").out)
        assertTrue(cli("bind", "other", "orders", "nope").code != 0, "an unknown fabric is refused")
        assertEquals(2, cli("bind", "other", "orders").code)
        assertTrue(ok("unbind", "other", "orders").out.contains("unbound"))
        assertEquals("no bindings", ok("bindings").out)
        assertTrue(cli("unbind", "other", "orders").code != 0)
        assertTrue(ok("fabric", "stop", "m1", "e1", "demo-main-1").out.contains("stopped"))
        assertEquals("stopped", Files.readString(marker))
        assertTrue(ok("fabric", "start", "m1", "e1", "demo-main-1").out.contains("running"))
        assertEquals(2, cli("fabric", "list", "m1").code)
        ok("fabric", "remove", "m1", "e1", "demo-main-1")
        assertEquals("no fabrics", ok("fabric", "list").out)
        ok("deploy", "demo", "--no-start")
        assertTrue(ok("fabric", "list", "m1", "e1").out.contains("stopped"))
        // an update (#226): stop-then-start on request, otherwise the new fabric runs next to the old one and replaces it
        val stopFirst = json(ok("deploy", "demo", "--no-blue-green", "--json")) as JsonObject
        assertTrue(field(stopFirst, "strategy").startsWith("stop-then-start") && field(stopFirst, "strategy").contains("switched off"), stopFirst.toString())
        val blueGreen = json(ok("deploy", "demo", "--json")) as JsonObject
        assertEquals("blue-green", field(blueGreen, "strategy"))
        assertTrue(ok("fabric", "list").out.contains("demo-main-1b"))
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

    @Test
    fun theLoggingCollectorKeepsTheLogsOfAStoppedEngine() {
        machine()
        ok("engine", "create", "m1", "--id", "e1")
        ok("engine", "start", "m1", "e1")
        LoggingService(CringleHome.engineDir(home, "e1")).append(LogEntry(Instant.parse("2026-01-01T10:00:00Z"), "shop", "a", LogLevel.INFO, "kept for later"))
        assertEquals(2, cli("engine", "collect", "m1", "e1").code)
        assertEquals(2, cli("engine", "collect", "m1", "e1", "maybe").code)
        assertTrue(ok("engine", "collect", "m1", "e1", "on").out.contains("on"))
        assertEquals(1, daemon.collectLogsNow())
        ok("engine", "stop", "m1", "e1")
        assertTrue(ok("logs", "m1", "e1").out.contains("(collected): kept for later"))
        assertEquals(true, ((json(ok("logs", "m1", "e1", "--json")) as JsonArray).single() as JsonObject)["collected"].let { (it as JsonPrimitive).content.toBoolean() })
        assertTrue(ok("engine", "collect", "m1", "e1", "off").out.contains("off"))
        assertTrue(cli("logs", "m1", "e1").code != 0)
    }

    @Test
    fun logFilesOfForeignProcessesShowTheirSource() {
        machine()
        ok("engine", "create", "m1", "--id", "e1")
        ok("engine", "start", "m1", "e1")
        val folder = Files.createDirectories(CringleHome.engineDir(home, "e1").resolve("fabrics/shop/logs/a"))
        Files.writeString(folder.resolve("proc.log"), "2026-01-01T00:00:00Z WARN from a process\n")
        val out = ok("logs", "--fabric", "shop").out
        assertTrue(out.contains("WARN  m1/e1 shop/a [proc.log]: 2026-01-01T00:00:00Z WARN from a process"), out)
        val entry = (json(ok("logs", "--json")) as JsonArray).single() as JsonObject
        assertEquals("proc.log", field(entry, "source"))
    }

    @Test
    fun dwhCommandsReportEmptyUnknownAndWrongInput() {
        machine()
        ok("engine", "create", "m1", "--id", "e1")
        ok("engine", "start", "m1", "e1")
        assertEquals("no partitions", ok("dwh", "list").out)
        assertEquals("[]", ok("dwh", "list", "--json").out.replace("\\s".toRegex(), ""))
        // an unknown fabric is refused by the server, wrong input by the CLI (exit code 2)
        assertTrue(cli("dwh", "list", "nope").code != 0 && cli("dwh", "list", "nope").code != 2)
        assertTrue(cli("dwh", "record", "nope", "on").code != 0 && cli("dwh", "record", "nope", "on").code != 2)
        assertTrue(cli("dwh", "query", "nope", "tether", "t").code != 0 && cli("dwh", "query", "nope", "tether", "t").code != 2)
        assertEquals(2, cli("debug", "break", "f1", "c.out -> s.in", "maybe").code)
        assertEquals(2, cli("debug", "break", "f1").code)
        assertTrue(cli("debug", "state", "nope").code != 0 && cli("debug", "state", "nope").code != 2)
        assertTrue(cli("debug", "resume", "nope").code != 0 && cli("debug", "resume", "nope").code != 2)
        assertEquals(2, cli("dwh", "record", "f1", "maybe").code)
        assertEquals(2, cli("dwh", "record", "f1", "on", "--max-age", "5x").code)
        assertEquals(2, cli("dwh", "retention", "f1", "block", "b", "--max-size", "big").code)
        assertEquals(2, cli("dwh", "query", "f1", "flavor", "t").code)
        assertEquals(2, cli("dwh", "query", "f1", "tether").code)
    }

    @Test
    fun theShellRunsOneCommandPerLineUntilExit() {
        val session = cli("shell", stdin = "whoami\nmachine list\nexit\nwhoami\n")
        assertEquals(0, session.code, session.err)
        assertTrue(session.out.contains("admin"), "the first command ran:\n${session.out}")
        assertTrue(session.out.contains("no machines"), "the second command ran:\n${session.out}")
        assertEquals(3, Regex("cringle>").findAll(session.out).count(), "a prompt per line, none after exit:\n${session.out}")
        assertEquals(1, Regex("name +: admin").findAll(session.out).count(), "the whoami after exit did not run")
    }

    @Test
    fun aFailingCommandDoesNotEndTheShell() {
        val session = cli("shell", stdin = "nonsense\nmachine list --nope\nmachine add\n\nwhoami\n")
        assertEquals(0, session.code, session.err)
        assertTrue(session.err.contains("unknown command 'nonsense'"), session.err)
        assertTrue(session.err.contains("usage: cringle machine add"), session.err)
        assertTrue(session.out.contains("admin"), "the last command still ran:\n${session.out}")
    }

    @Test
    fun theShellAppliesTheGlobalOptionsToEveryCommandAndEndsAtTheEndOfTheInput() {
        val json = cli("--json", "shell", stdin = "whoami")
        assertEquals(0, json.code, json.err)
        assertTrue(json.out.contains("\"name\""), "--json applies to the command:\n${json.out}")
        assertEquals(0, cli("shell", stdin = "").code, "the end of the input leaves the shell")
    }

    @Test
    fun theShellRefusesANestedShellAndAnUnclosedQuote() {
        val session = cli("shell", stdin = "shell\nmachine add \"m1\nwhoami\n")
        assertEquals(0, session.code, session.err)
        assertTrue(session.err.contains("you are in the shell already"), session.err)
        assertTrue(session.err.contains("is not closed"), session.err)
        assertTrue(session.out.contains("admin"), session.out)
    }
}
