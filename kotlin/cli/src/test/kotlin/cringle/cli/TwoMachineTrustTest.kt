// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.daemon.Daemon
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
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The end of milestone M5 (#5): two machines trust each other and every link of a component is mutual TLS. Each machine has
 * a daemon with its router (combined mode) and an engine; one management server belongs to machine 1. The engine of machine
 * 2 is not visible to machine 1 before the trust, is visible and addressable after `cringle trust add` with the
 * fingerprint, and is gone again after `cringle trust revoke`. Everything, certificates included, is created by the test in
 * temporary folders.
 */
class TwoMachineTrustTest {
    private lateinit var dir: Path
    private lateinit var cliHome: Path
    private lateinit var daemon1: Daemon
    private lateinit var daemon2: Daemon
    private lateinit var router1: RouterServer
    private lateinit var router2: RouterServer
    private lateinit var server: ManagementServer
    private lateinit var adminToken: String
    private val closeables = ArrayList<AutoCloseable>()

    private val address2 get() = "127.0.0.1:${router2.port}"
    private val fingerprint2 get() = router2.identity!!.publicKeyFingerprint

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("cringle-two-machines-test")
        cliHome = dir.resolve("cli-home")
        daemon1 = Daemon(dir.resolve("home1"), combined = true).start()
        closeables += daemon1
        daemon2 = Daemon(dir.resolve("home2"), combined = true).start()
        closeables += daemon2
        router1 = daemon1.router!!
        router2 = daemon2.router!!

        // one engine on each machine, registered at the router of its machine over mTLS
        daemon1.createEngine("e1", "Engine One")
        daemon1.supervisor.start("e1")
        daemon2.createEngine("e2", "Engine Two")
        daemon2.supervisor.start("e2")
        awaitTrue("e1 at router 1") { router1.registry.engines().any { it.record.id == "e1" } }
        awaitTrue("e2 at router 2") { router2.registry.engines().any { it.record.id == "e2" } }

        // the management server of machine 1 and its router trust each other
        val tls = ManagementTls(dir.resolve("tls"))
        val users = UserManager(FileUserStore(dir.resolve("users.json")))
        adminToken = users.bootstrap()!!
        val core = tls.core(ManagementStore(dir.resolve("state.json")), null, null, "127.0.0.1:${router1.port}")
        core.trustStore.add(TrustEntry(router1.identity!!.publicKeyFingerprint, "router-1", TrustKind.ROUTER, "127.0.0.1:${router1.port}"))
        router1.tls!!.trustStore.add(TrustEntry(tls.identity.publicKeyFingerprint, "management", TrustKind.COMPONENT))
        server = ManagementServer(core, users = users, recoverOnStart = false).start()
        closeables += server

        // the operator of machine 2 trusts router 1, by hand and by fingerprint, as the operator of machine 1 will do for router 2
        router2.tls!!.trustStore.add(TrustEntry(router1.identity!!.publicKeyFingerprint, "router-1", TrustKind.ROUTER, "127.0.0.1:${router1.port}"))
    }

    @AfterEach
    fun tearDown() {
        closeables.reversed().forEach { runCatching { it.close() } }
        repeat(10) {
            if (runCatching { dir.toFile().deleteRecursively() }.getOrDefault(false) || !Files.exists(dir)) return
            Thread.sleep(200)
        }
    }

    private fun awaitTrue(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!cond()) {
            check(System.nanoTime() < end) { "timed out waiting for $what" }
            Thread.sleep(50)
        }
    }

    private fun cli(vararg args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val env = mapOf(
            "CRINGLE_HOME" to cliHome.toString(),
            "CRINGLE_TOKEN" to adminToken,
            "CRINGLE_FINGERPRINT" to server.core.identity.publicKeyFingerprint,
        )
        val code = Cli(PrintStream(out, true), PrintStream(err, true), ByteArrayInputStream(ByteArray(0)), env)
            .run(listOf("--server", "127.0.0.1:${server.port}", "--json") + args.toList())
        return code to (out.toString().trim() + err.toString().trim())
    }

    private fun trusted(): List<JsonObject> {
        val (code, text) = cli("trust", "list")
        assertEquals(0, code, text)
        return (Json.parseToJsonElement(text) as JsonArray).map { it as JsonObject }
    }

    private fun field(o: JsonObject, name: String) = (o[name] as JsonPrimitive).content

    /** The engines machine 1 knows about, including those of remote routers it trusts. */
    private fun visibleToMachine1(): List<String> = router1.registry.engines(includeRemote = true).map { it.record.id }.sorted()

    @Test
    fun theEngineOfTheOtherMachineIsVisibleOnlyAfterTheTrustAndGoneAfterTheRevoke() {
        val engine2 = daemon2.trustStore.list().single { it.kind == TrustKind.ENGINE && it.name == "e2" }.fingerprint

        // before the trust: machine 1 knows its own engine and nothing of machine 2
        assertEquals(listOf("e1"), visibleToMachine1())
        assertTrue(trusted().none { field(it, "fingerprint") == fingerprint2 || field(it, "fingerprint") == engine2 })

        // trust by fingerprint: the router is connected and its engine is trusted through it
        val added = cli("trust", "add", address2, "--fingerprint", fingerprint2)
        assertEquals(0, added.first, added.second)
        awaitTrue("engine e2 at router 1") { visibleToMachine1() == listOf("e1", "e2") }
        val remote = router1.registry.engines(includeRemote = true).single { it.record.id == "e2" }
        assertTrue(remote.record.managementAddress.isNotEmpty(), "the engine of the other machine is addressable")
        val entries = trusted()
        val router = entries.single { field(it, "fingerprint") == fingerprint2 }
        assertEquals("ROUTER", field(router, "kind"))
        val engine = entries.single { field(it, "fingerprint") == engine2 }
        assertEquals("ENGINE", field(engine, "kind"))
        assertEquals(fingerprint2, field(engine, "origin"), "the engine is trusted through router 2")

        // revoke: the router, its engine and its cached engines vanish
        val revoked = cli("trust", "revoke", fingerprint2)
        assertEquals(0, revoked.first, revoked.second)
        assertEquals(listOf("e1"), visibleToMachine1())
        assertTrue(trusted().none { field(it, "fingerprint") == fingerprint2 || field(it, "fingerprint") == engine2 })
    }
}
