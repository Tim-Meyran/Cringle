// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The enrollment secret of an engine (docs/trust.md): announced as a hash before the start, given in the environment. */
class EngineSupervisorEnrollmentTest {
    @TempDir
    lateinit var home: Path

    private class Call(val engine: String, val hash: ByteArray)

    private val calls = CopyOnWriteArrayList<Call>()

    private fun supervisor(router: String? = "127.0.0.1:1", announce: (String, ByteArray) -> Unit = { id, hash ->
        calls += Call(id, hash)
        Files.writeString(home.resolve("announced-$id"), "yes")
    }) = EngineSupervisor(
        home,
        EngineCommand(mainClass = "cringle.daemon.EnrollmentProbeMainKt"),
        routerAddress = { router },
        announce = announce,
    )

    private fun probe(id: String, file: String): String = Files.readString(home.resolve("probe").resolve(id).resolve(file))

    private fun sha256(hex: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })

    @Test
    fun theSecretIsAnnouncedBeforeTheEngineStartsAndMatchesTheEnvironment() {
        supervisor().use {
            it.add("e1", "E1")
            it.start("e1")
            assertEquals(1, calls.size)
            assertEquals("e1", calls.single().engine)
            assertEquals(32, calls.single().hash.size)
            assertEquals("true", probe("e1", "announced-before-start"))
            val secret = probe("e1", "secret")
            assertTrue(Regex("[0-9a-f]{64}").matches(secret), secret)
            assertTrue(calls.single().hash.contentEquals(sha256(secret)))
        }
    }

    @Test
    fun theSecretIsNotOnTheCommandLine() {
        supervisor().use {
            it.add("e1", "E1")
            it.start("e1")
            assertFalse(probe("e1", "args").contains(probe("e1", "secret")))
        }
    }

    @Test
    fun everyStartUsesANewSecret() {
        supervisor().use {
            it.add("e1", "E1")
            it.add("e2", "E2")
            it.start("e1")
            it.start("e2")
            assertNotEquals(probe("e1", "secret"), probe("e2", "secret"))
            it.stop("e1")
            val first = probe("e1", "secret")
            it.start("e1")
            assertNotEquals(first, probe("e1", "secret"))
            assertEquals(3, calls.size)
        }
    }

    @Test
    fun aFailedAnnouncementStopsTheStart() {
        supervisor(announce = { _, _ -> throw IllegalStateException("router refuses") }).use {
            it.add("e1", "E1")
            val e = assertThrows(DaemonException::class.java) { it.start("e1") }
            assertTrue(e.message!!.contains("router refuses"), e.message)
            assertEquals(ProcessState.STOPPED, it.get("e1").state)
            assertFalse(Files.exists(home.resolve("probe").resolve("e1")), "the engine process must not have started")
        }
    }

    @Test
    fun withoutARouterThereIsNoSecret() {
        supervisor(router = null).use {
            it.add("e1", "E1")
            it.start("e1")
            assertEquals("none", probe("e1", "secret"))
            assertTrue(calls.isEmpty())
        }
    }
}
