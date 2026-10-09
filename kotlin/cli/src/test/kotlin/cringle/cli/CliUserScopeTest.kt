// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import cringle.management.ManagementServer
import cringle.management.ManagementStore
import cringle.management.test.ManagementTls
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** `cringle user|group grant|revoke --scope ...` against a management server with user management (#270). */
class CliUserScopeTest {
    private lateinit var dir: Path
    private lateinit var server: ManagementServer
    private lateinit var users: UserManager
    private lateinit var adminToken: String
    private val closeables = ArrayList<AutoCloseable>()

    private class Result(val code: Int, val out: String, val err: String)

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("cringle-cli-scope-test")
        val tls = ManagementTls(dir.resolve("tls"))
        users = UserManager(FileUserStore(dir.resolve("users.json")))
        adminToken = users.bootstrap()!!
        server = ManagementServer(tls.core(ManagementStore(dir.resolve("state.json"))), users = users, recoverOnStart = false).start()
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

    private fun cli(vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val env = mapOf("CRINGLE_HOME" to dir.resolve("cli-home").toString(), "CRINGLE_TOKEN" to adminToken, "CRINGLE_FINGERPRINT" to server.core.identity.publicKeyFingerprint)
        val code = Cli(PrintStream(out, true), PrintStream(err, true), ByteArrayInputStream(ByteArray(0)), env)
            .run(listOf("--server", "127.0.0.1:${server.port}", "--json") + args.toList())
        return Result(code, out.toString().trim(), err.toString().trim())
    }

    private fun scoped(json: String): List<String> =
        ((Json.parseToJsonElement(json) as JsonObject)["scopedRoles"] as JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content }

    @Test
    fun aUserIsGivenAndLosesAScopedRole() {
        val id = users.createUser("bob", emptySet()).user.id
        val granted = cli("user", "grant", id, "operator", "--scope", "machine:m1")
        assertEquals(0, granted.code, granted.err)
        assertEquals(listOf("operator@machine:m1"), scoped(granted.out))
        assertEquals(setOf(cringle.router.users.RoleAssignment(cringle.contract.UserRole.OPERATOR, cringle.router.users.Scope.parse("machine:m1"))), users.listUsers().single { it.user.id == id }.user.scoped)

        val listed = cli("user", "list")
        assertTrue(listed.out.contains("operator@machine:m1"), listed.out)

        val revoked = cli("user", "revoke", id, "operator", "--scope", "machine:m1")
        assertEquals(0, revoked.code, revoked.err)
        assertEquals(emptyList<String>(), scoped(revoked.out))
        assertEquals(1, cli("user", "revoke", id, "operator", "--scope", "machine:m1").code) // it is not there any more
    }

    @Test
    fun aGroupGivesItsMembersAScopedRole() {
        users.createGroup("shop-ops", emptySet())
        val granted = cli("group", "grant", "shop-ops", "viewer", "--scope", "project:shop")
        assertEquals(0, granted.code, granted.err)
        assertEquals(listOf("viewer@project:shop"), scoped(granted.out))
        val member = users.createUser("gina", emptySet(), setOf("shop-ops")).user.id
        assertEquals(listOf("viewer@project:shop"), scoped(cli("user", "list").out.let { Json.parseToJsonElement(it) as JsonArray }.map { it as JsonObject }.single { (it["id"] as kotlinx.serialization.json.JsonPrimitive).content == member }.let { Json.encodeToString(JsonObject.serializer(), it) }))
        assertEquals(0, cli("group", "revoke", "shop-ops", "viewer", "--scope", "project:shop").code)
    }

    @Test
    fun invalidInputIsRefusedWithAMessage() {
        val id = users.createUser("kim", emptySet()).user.id
        val global = cli("user", "grant", id, "operator", "--scope", "global")
        assertEquals(2, global.code)
        assertTrue(global.err.contains("user create --role"), global.err)
        assertEquals(2, cli("user", "grant", id, "operator", "--scope", "galaxy:x").code)
        assertEquals(2, cli("user", "grant", id, "operator").code) // --scope is required
        assertEquals(2, cli("user", "grant", id, "boss", "--scope", "machine:m1").code)
        assertEquals(1, cli("user", "grant", id, "operator", "--scope", "function:everything").code) // the server checks the name
        assertEquals(1, cli("user", "grant", id, "end-user", "--scope", "machine:m1").code)
    }
}
