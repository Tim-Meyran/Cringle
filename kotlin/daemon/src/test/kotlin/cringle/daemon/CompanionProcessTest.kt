// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CompanionProcessTest {
    @TempDir
    lateinit var logs: Path

    private fun probe(vararg arguments: String) = CompanionProcess(
        "probe", EngineCommand(), "cringle.daemon.CompanionProbeMainKt", arguments.toList(), emptyMap(), logs,
        firstDelay = Duration.ofMillis(20), maxDelay = Duration.ofMillis(100),
    )

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timeout: $what" }
            Thread.onSpinWait()
            Thread.sleep(20)
        }
    }

    @Test
    fun aProgramThatEndsIsStartedAgain() {
        probe().use { companion ->
            companion.start()
            await("second start") { companion.startCount >= 2 }
        }
    }

    @Test
    fun theReasonThatAProgramEndedIsTheTailOfItsErrorOutput() {
        probe().use { companion ->
            assertEquals(null, companion.errorTail())
            val err = logs.resolve("probe.err.log")
            Files.writeString(err, "from an earlier run\n")
            val started = Files.size(err)
            // a start sets the position; here the test sets the file as the program would have written it after it
            Files.writeString(err, "from an earlier run\nException in thread \"main\" java.net.BindException: Permission denied\n\tat sun.nio.ch.Net.bind0(Native Method)\n", java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
            assertTrue(started > 0)
            assertTrue(companion.errorTail()!!.contains("BindException: Permission denied"), companion.errorTail())
        }
    }

    @Test
    fun theTailKeepsTheLastLinesAndTheExceptionOfALongTrace() {
        assertEquals(null, tailOf(" \n\n"))
        val trace = "Exception in thread \"main\" java.net.BindException: Permission denied\n" + (1..30).joinToString("\n") { "\tat frame$it" }
        val tail = tailOf(trace)!!
        assertTrue(tail.startsWith("Exception in thread \"main\" java.net.BindException: Permission denied | "), tail)
        assertTrue(tail.endsWith("at frame30") && !tail.contains("at frame1 |"), tail)
        assertEquals("a | b", tailOf("a\n\nb\n"))
        assertTrue(tailOf("x".repeat(5000))!!.length <= 1500)
    }

    @Test
    fun theOutputIsLoggedWithoutTheSecretLines() {
        probe("stay").use { companion ->
            companion.start()
            val out = logs.resolve("probe.out.log")
            await("output") { Files.exists(out) && Files.readString(out).contains("hello from probe") }
            assertFalse(Files.readString(out).contains("secret"))
        }
    }

    @Test
    fun closeStopsTheProgramAndTheSupervision() {
        val companion = probe("stay")
        companion.start()
        await("running") { companion.isRunning }
        companion.close()
        assertFalse(companion.isRunning)
        assertEquals(1, companion.startCount)
        assertTrue(Files.exists(logs.resolve("probe.err.log")))
    }
}
