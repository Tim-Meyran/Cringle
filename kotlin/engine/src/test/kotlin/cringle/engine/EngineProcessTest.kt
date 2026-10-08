// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import cringle.engine.v1.EngineManagementServiceGrpcKt.EngineManagementServiceCoroutineStub
import cringle.engine.v1.GetStatusRequest
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Starts real engine processes and talks to them over gRPC. */
@Tag("integration")
class EngineProcessTest {
    @TempDir
    lateinit var home: Path

    private class Running(val process: Process, val port: Int)

    private fun start(vararg extra: String): Running {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val command = listOf(java, "-cp", System.getProperty("java.class.path"), "cringle.engine.MainKt") + extra
        val builder = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment()[CringleHome.ENV] = home.toString()
        val process = builder.start()
        val reader = process.inputStream.bufferedReader()
        val line = CompletableFuture.supplyAsync { reader.readLine() }.get(90, TimeUnit.SECONDS)
        assertTrue(line != null && line.startsWith("management-port="), "unexpected first output line: $line")
        return Running(process, line.removePrefix("management-port=").trim().toInt())
    }

    private val client by lazy { TestClient(home.resolveSibling(home.fileName.toString() + "-client")) }

    private fun status(r: Running) = client.channel(r.port).let { channel ->
        try {
            runBlocking { EngineManagementServiceCoroutineStub(channel).getStatus(GetStatusRequest.getDefaultInstance()) }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    /** Starts the engine [id] after it was made to trust the client of this test. */
    private fun started(id: String, vararg extra: String): Running {
        client.allow(home, id)
        return start("--id", id, *extra)
    }

    private fun stop(r: Running) {
        r.process.destroy()
        assertTrue(r.process.waitFor(60, TimeUnit.SECONDS), "engine did not terminate")
    }

    @Test
    fun engineProcessStartsIsQueriedAndRestartsWithSameIdentity() {
        val first = started("proc1", "--name", "Proc")
        val s1 = try {
            status(first)
        } finally {
            stop(first)
        }
        assertEquals("proc1", s1.engineId.value)
        assertEquals("Proc", s1.name)
        // graceful shutdown ran: SIGTERM ends a JVM with 143 after its hooks, the port file is removed by the hook
        if (!System.getProperty("os.name").startsWith("Windows")) {
            assertTrue(first.process.exitValue() == 0 || first.process.exitValue() == 143, "exit ${first.process.exitValue()}")
            assertFalse(Files.exists(home.resolve("engines/proc1/${Engine.PORT_FILE}")))
        }

        val second = started("proc1")
        val s2 = try {
            status(second)
        } finally {
            stop(second)
        }
        assertEquals(s1.certificate.fingerprint, s2.certificate.fingerprint)
        assertEquals("Proc", s2.name)
    }

    @Test
    fun twoEngineProcessesOnOneMachineDoNotInterfere() {
        val a = started("pa")
        val b = started("pb")
        try {
            assertNotEquals(a.port, b.port)
            val sa = status(a)
            val sb = status(b)
            assertEquals("pa", sa.engineId.value)
            assertEquals("pb", sb.engineId.value)
            assertNotEquals(sa.certificate.fingerprint, sb.certificate.fingerprint)
        } finally {
            stop(a)
            stop(b)
        }
    }

    @Test
    fun invalidArgumentsExitWithCode2() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "cringle.engine.MainKt", "--id", "x", "--bogus-flag")
            .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        assertTrue(p.waitFor(90, TimeUnit.SECONDS))
        assertEquals(2, p.exitValue())
    }
}
