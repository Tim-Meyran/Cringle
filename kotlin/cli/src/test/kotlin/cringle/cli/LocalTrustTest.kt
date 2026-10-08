// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.common.LocalTrust
import cringle.common.TrustStore
import cringle.daemon.Daemon
import cringle.management.LocalTrustSync
import cringle.management.ManagementCore
import cringle.management.ManagementServer
import cringle.management.ManagementStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
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

/**
 * The trust between the programs of one Cringle home, as the Gradle tasks `runDaemon`, `runManagementServer` and `runCli` set it
 * up (`CRINGLE_TRUST_LOCAL=1`): no fingerprint is copied, no trust entry is made and nobody logs in, and a machine, an engine
 * on it, its status over mutual TLS and the trust list all work. The management server is built the way its `main` builds it.
 */
@Tag("integration")
class LocalTrustTest {
    private lateinit var home: Path
    private lateinit var daemon: Daemon
    private lateinit var server: ManagementServer
    private val closeables = ArrayList<AutoCloseable>()

    @BeforeEach
    fun setUp() {
        home = Files.createTempDirectory("cringle-local-trust-test")
        // the daemon starts first and creates the identity of the management server of the home
        daemon = Daemon(home, combined = true, trustLocal = true).start()
        closeables += daemon
        // the management server: identity and trust store of its folder, the local trust, no users (no login)
        val base = home.resolve("management")
        val identity = Identity.loadOrCreate(base, ComponentKind.MANAGEMENT.commonName("management"))
        val trustStore = TrustStore(base.resolve("trust.json"))
        val sync = LocalTrustSync(home, trustStore)
        sync.sync(force = true)
        val core = ManagementCore(ManagementStore(base.resolve("state.json")), identity, trustStore, null, null, "127.0.0.1:${daemon.router!!.port}", beforeConnect = { sync.sync() })
        server = ManagementServer(core, recoverOnStart = false).start()
        closeables += server
    }

    @AfterEach
    fun tearDown() {
        closeables.reversed().forEach { runCatching { it.close() } }
        repeat(10) {
            if (runCatching { home.toFile().deleteRecursively() }.getOrDefault(false) || !Files.exists(home)) return
            Thread.sleep(200)
        }
    }

    private class Result(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String, trustLocal: Boolean = true): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val env = buildMap {
            put("CRINGLE_HOME", home.toString())
            put("CRINGLE_SERVER", "127.0.0.1:${server.port}")
            if (trustLocal) put(LocalTrust.ENV, "1")
        }
        val code = Cli(PrintStream(out, true), PrintStream(err, true), ByteArrayInputStream(ByteArray(0)), env).run(listOf("--json") + args.toList())
        return Result(code, out.toString().trim(), err.toString().trim())
    }

    private fun ok(vararg args: String): Result = cli(*args).also { assertEquals(0, it.code, "cringle ${args.joinToString(" ")}: ${it.err}") }

    private fun field(o: JsonObject, name: String) = (o[name] as JsonPrimitive).content

    @Test
    fun withoutTheLocalTrustTheCommandLineStillNeedsAFingerprint() {
        val refused = cli("machine", "list", trustLocal = false)
        assertEquals(2, refused.code)
        assertTrue(refused.err.contains("fingerprint"), refused.err)
    }

    @Test
    fun aMachineAndAnEngineWorkWithoutLoginFingerprintOrTrustEntry() {
        ok("machine", "add", "m1", "127.0.0.1:${daemon.port}")
        val machine = Json.parseToJsonElement(ok("machine", "list").out) as JsonArray
        assertEquals("true", field(machine.single() as JsonObject, "reachable"), "the management server reaches the daemon over mutual TLS")

        ok("engine", "create", "m1", "--id", "e1", "--no-autostart")
        val started = Json.parseToJsonElement(ok("engine", "start", "m1", "e1").out) as JsonObject
        assertEquals("RUNNING", field(started, "state").uppercase())
        assertTrue(field(started, "certificate").isNotEmpty(), "the management server reaches the engine over mutual TLS: its status has a certificate")

        val listed = Json.parseToJsonElement(ok("trust", "list").out) as JsonArray
        val kinds = listed.map { field(it as JsonObject, "kind") to field(it, "name") }
        assertTrue("COMPONENT" to "daemon" in kinds, kinds.toString())
        assertTrue("ENGINE" to "e1" in kinds, kinds.toString())
        assertTrue(listed.any { field(it as JsonObject, "kind") == "ROUTER" }, "the router of the daemon is trusted: $kinds")
        assertFalse(Files.exists(home.resolve("cli.json")), "nothing was stored: no login")
    }
}
