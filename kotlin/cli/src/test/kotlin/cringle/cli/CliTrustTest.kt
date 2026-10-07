// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.contract.UserRole
import cringle.management.ManagementServer
import cringle.management.ManagementStore
import cringle.management.test.ManagementTls
import cringle.router.RouterServer
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
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

/**
 * `cringle trust` against a management server on TLS with two routers (#6): the router of the management server (a) and
 * another router (b). The CLI pins the fingerprint of the management server, the management server trusts by fingerprints.
 */
class CliTrustTest {
    private lateinit var dir: Path
    private lateinit var cliHome: Path
    private lateinit var routerA: RouterServer
    private lateinit var routerB: RouterServer
    private lateinit var server: ManagementServer
    private lateinit var users: UserManager
    private lateinit var adminToken: String
    private val closeables = ArrayList<AutoCloseable>()

    private class Result(val code: Int, val out: String, val err: String)

    private val fingerprintB get() = routerB.identity!!.publicKeyFingerprint
    private val addressB get() = "127.0.0.1:${routerB.port}"

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("cringle-cli-trust-test")
        cliHome = dir.resolve("cli-home")
        val tls = ManagementTls(dir.resolve("tls"))
        routerA = tls.startRouter(dir.resolve("a.json"))
        closeables += AutoCloseable { routerA.stop() }
        routerB = tls.startRouter(dir.resolve("b.json"), trustedByManagement = false)
        closeables += AutoCloseable { routerB.stop() }
        // b trusts a as a router, so that a may ask it for its engines
        routerB.tls!!.trustStore.add(TrustEntry(routerA.identity!!.publicKeyFingerprint, "a", TrustKind.ROUTER, address = "127.0.0.1:${routerA.port}"))
        users = UserManager(FileUserStore(dir.resolve("users.json")))
        adminToken = users.bootstrap()!!
        val core = tls.core(ManagementStore(dir.resolve("state.json")), null, null, "127.0.0.1:${routerA.port}")
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

    private fun cli(vararg args: String, token: String = adminToken): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val env = mapOf(
            "CRINGLE_HOME" to cliHome.toString(),
            "CRINGLE_TOKEN" to token,
            "CRINGLE_FINGERPRINT" to server.core.identity.publicKeyFingerprint,
        )
        val code = Cli(PrintStream(out, true), PrintStream(err, true), ByteArrayInputStream(ByteArray(0)), env)
            .run(listOf("--server", "127.0.0.1:${server.port}", "--json") + args.toList())
        return Result(code, out.toString().trim(), err.toString().trim())
    }

    private fun entries(token: String = adminToken): List<JsonObject> {
        val listed = cli("trust", "list", token = token)
        assertEquals(0, listed.code, listed.err)
        return (Json.parseToJsonElement(listed.out) as JsonArray).map { it as JsonObject }
    }

    private fun field(o: JsonObject, name: String) = (o[name] as JsonPrimitive).content

    private fun viewerToken(): String = users.createToken(users.createUser("viewer", setOf(UserRole.VIEWER)).user.id, "t", null).secret

    @Test
    fun trustAddWithoutAFingerprintShowsItAndAddsNothing() {
        val shown = cli("trust", "add", addressB)
        assertEquals(2, shown.code)
        assertTrue(shown.err.contains(fingerprintB), "the message has to show the fingerprint of the router:\n${shown.err}")
        assertTrue(entries().none { field(it, "fingerprint") == fingerprintB })
    }

    @Test
    fun trustAddWithAWrongFingerprintFailsAndNamesBothValues() {
        val wrong = "ab".repeat(32)
        val failed = cli("trust", "add", addressB, "--fingerprint", wrong)
        assertEquals(1, failed.code)
        assertTrue(failed.err.contains(fingerprintB) && failed.err.contains(wrong), failed.err)
        assertTrue(entries().none { field(it, "fingerprint") == fingerprintB })
        assertEquals(2, cli("trust", "add", addressB, "--fingerprint", "xyz").code)
    }

    @Test
    fun trustAddListAndRevokeOfARouterTakeItsEnginesAlong() {
        val enginePrint = "cd".repeat(32)
        routerB.registry.register("eb", "EB", "127.0.0.1:1", enginePrint)

        val added = cli("trust", "add", addressB, "--fingerprint", fingerprintB)
        assertEquals(0, added.code, added.err)
        runBlocking { routerA.remoteRouters.refresh(addressB) }

        val listed = entries()
        val router = listed.single { field(it, "fingerprint") == fingerprintB }
        assertEquals("ROUTER", field(router, "kind"))
        assertEquals(addressB, field(router, "address"))
        assertEquals("", field(router, "origin"))
        val engine = listed.single { field(it, "fingerprint") == enginePrint }
        assertEquals("ENGINE", field(engine, "kind"))
        assertEquals(fingerprintB, field(engine, "origin"), "the engine is trusted through the router")
        for (name in listOf("fingerprint", "kind", "name", "address", "origin", "addedAt")) assertTrue(name in router, "the entry has to show '$name'")

        // an engine that came through a router cannot be removed alone
        val alone = cli("trust", "revoke", enginePrint)
        assertEquals(1, alone.code)
        assertTrue(alone.err.contains("revoke that router"), alone.err)

        val revoked = cli("trust", "revoke", fingerprintB)
        assertEquals(0, revoked.code, revoked.err)
        assertTrue(entries().none { field(it, "fingerprint") == fingerprintB || field(it, "fingerprint") == enginePrint }, "the router and its engines are gone")
    }

    @Test
    fun trustAddWithYesAcceptsWhatTheRouterShows() {
        assertEquals(0, cli("trust", "add", addressB, "--yes").code)
        assertTrue(entries().any { field(it, "fingerprint") == fingerprintB })
    }

    @Test
    fun aComponentIsTrustedByItsFingerprintAndRevoked() {
        val component = "ef".repeat(32)
        val added = cli("trust", "add-component", component, "--name", "repo-1", "--kind", "SERVER", "--address", "10.0.0.5:7600")
        assertEquals(0, added.code, added.err)
        val entry = entries().single { field(it, "fingerprint") == component }
        assertEquals("SERVER", field(entry, "kind"))
        assertEquals("repo-1", field(entry, "name"))
        assertEquals("10.0.0.5:7600", field(entry, "address"))

        assertEquals(1, cli("trust", "add-component", "1".repeat(64), "--name", "x", "--kind", "ENGINE").code, "only COMPONENT and SERVER")
        assertEquals(1, cli("trust", "add-component", "abc", "--name", "x").code, "not a fingerprint")
        assertEquals(2, cli("trust", "add-component", component).code, "the name is required")

        assertEquals(0, cli("trust", "revoke", component).code)
        assertFalse(entries().any { field(it, "fingerprint") == component })
        assertEquals(1, cli("trust", "revoke", component).code, "it is not trusted any more")
    }

    @Test
    fun aViewerMayListButNotChangeTrust() {
        val viewer = viewerToken()
        assertEquals(0, cli("trust", "list", token = viewer).code)
        for (command in listOf(
            arrayOf("trust", "add", addressB, "--yes"),
            arrayOf("trust", "add-component", "ef".repeat(32), "--name", "x"),
            arrayOf("trust", "revoke", "ef".repeat(32)),
        )) {
            val denied = cli(*command, token = viewer)
            assertEquals(1, denied.code, command.joinToString(" "))
            assertTrue(denied.err.contains("role does not allow"), denied.err)
        }
        assertTrue(entries().none { field(it, "fingerprint") == fingerprintB })
    }
}
