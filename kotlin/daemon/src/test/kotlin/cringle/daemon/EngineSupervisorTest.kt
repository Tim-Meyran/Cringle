// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EngineSupervisorTest {
    @TempDir
    lateinit var home: Path

    private fun waitFor(timeoutSeconds: Long, condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos()
        while (System.nanoTime() < end) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    @Test
    fun aFailingLogFileNeitherStopsTheEngineNorLeavesItsOutputUnread() {
        val warnings = CopyOnWriteArrayList<String>()
        val writes = AtomicInteger()
        val supervisor = EngineSupervisor(
            home,
            EngineCommand(mainClass = "cringle.daemon.FakeEngineMainKt"),
            appendLog = { _, _ ->
                writes.incrementAndGet()
                throw IOException("disk full")
            },
            onWarning = { warnings += it },
        )
        supervisor.use {
            it.add("e1", "E1")
            val started = it.start("e1")
            assertEquals(ProcessState.RUNNING, started.state)
            // the engine writes about 600 KB: it can only finish if the supervisor keeps reading after the first failure
            assertTrue(waitFor(60) { Files.exists(home.resolve("output-written")) }, "the engine is blocked because nobody reads its output")
            assertEquals(ProcessState.RUNNING, it.get("e1").state)
            assertEquals(1, warnings.size, "the failing log is reported once, not per line: $warnings")
            assertTrue(warnings.single().contains("e1") && warnings.single().contains("disk full"), warnings.single())
            assertEquals(1, writes.get(), "after the first failure the lines are discarded without further write attempts")
        }
    }
}
