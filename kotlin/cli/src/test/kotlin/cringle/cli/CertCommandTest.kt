// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import cringle.common.ComponentKind
import cringle.common.Identity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CertCommandTest {
    @TempDir
    lateinit var home: Path

    private class Result(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Cli(PrintStream(out, true), PrintStream(err, true), ByteArrayInputStream(ByteArray(0)), mapOf())
            .run(listOf("--json") + args.toList())
        return Result(code, out.toString().trim(), err.toString().trim())
    }

    private fun rows(r: Result) = (Json.parseToJsonElement(r.out) as JsonArray).map { it as JsonObject }

    private fun field(o: JsonObject, name: String) = (o[name] as JsonPrimitive).content

    private fun identities() {
        Identity.loadOrCreate(home.resolve("daemon"), ComponentKind.DAEMON.commonName("daemon"), Duration.ofDays(10))
        Identity.loadOrCreate(home.resolve("router"), ComponentKind.ROUTER.commonName("router"), Duration.ofDays(100))
    }

    @Test
    fun statusShowsTheIdentitiesOfTheHomeWithTheDaysLeft() {
        identities()
        val r = cli("--home", home.toString(), "cert", "status")
        assertEquals(0, r.code, r.err)
        val rows = rows(r)
        assertEquals(listOf("daemon", "router"), rows.map { field(it, "component") })
        assertTrue(field(rows[0], "daysLeft").toLong() in 8..10, rows[0].toString())
        assertTrue(field(rows[1], "daysLeft").toLong() in 98..100, rows[1].toString())
        assertEquals(64, field(rows[0], "fingerprint").length)
    }

    @Test
    fun renewRenewsTheDueOneAndLeavesTheValidOneAndTheKey() {
        identities()
        val fingerprint = Identity.loadOrCreate(home.resolve("daemon"), "x", renew = false).publicKeyFingerprint
        val r = cli("--home", home.toString(), "cert", "renew")
        assertEquals(0, r.code, r.err)
        assertEquals(listOf("daemon"), rows(r).map { field(it, "component") })
        assertTrue(r.err.contains("restart"), r.err)
        val after = rows(cli("--home", home.toString(), "cert", "status"))
        assertTrue(field(after[0], "daysLeft").toLong() > 3000, after[0].toString())
        assertEquals(fingerprint, field(after[0], "fingerprint"), "the key and so the fingerprint stay")
        assertTrue(field(after[1], "daysLeft").toLong() in 98..100)
    }

    @Test
    fun componentAndForceSelectWhatIsRenewed() {
        identities()
        val only = cli("--home", home.toString(), "cert", "renew", "--component", "router")
        assertEquals(0, only.code, only.err)
        assertEquals("[]", only.out.replace(Regex("\\s"), ""), "the router is valid for 100 days: nothing to renew")
        val forced = rows(cli("--home", home.toString(), "cert", "renew", "--component", "router", "--force"))
        assertEquals(listOf("router"), forced.map { field(it, "component") })
        assertTrue(field(forced[0], "daysLeft").toLong() > 3000)
    }

    @Test
    fun anUnknownComponentIsAUsageError() {
        identities()
        val r = cli("--home", home.toString(), "cert", "status", "--component", "engine")
        assertEquals(2, r.code, r.out + r.err)
        assertTrue(r.err.contains("unknown component"), r.err)
    }
}
