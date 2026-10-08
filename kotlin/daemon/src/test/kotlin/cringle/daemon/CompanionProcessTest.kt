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
