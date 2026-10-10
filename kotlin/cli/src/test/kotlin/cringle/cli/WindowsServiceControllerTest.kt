// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WindowsServiceControllerTest {
    private fun controller(timeout: Duration = Duration.ofSeconds(5), run: (List<String>) -> Pair<Int, String>) =
        WindowsServiceController({ run(it.toList()) }, timeout, Duration.ofMillis(1))

    @Test
    fun stopWaitsForStoppedNotJustLeavingRunning() {
        val states = ArrayDeque(listOf("RUNNING", "STOP_PENDING", "STOP_PENDING", "STOPPED"))
        val calls = ArrayList<String>()
        controller { c ->
            calls += c[1]
            if (c[1] == "query") 0 to "STATE : ${states.removeFirst()}" else 0 to ""
        }.stop("cringle-daemon")
        assertEquals(0, states.size, "waited until the last state, STOPPED")
        assertEquals("stop", calls.first())
    }

    @Test
    fun stopFailsWhenTheServiceStaysStopPending() {
        val e = assertThrows<IllegalStateException> {
            controller(Duration.ofMillis(30)) { c -> 0 to if (c[1] == "query") "STATE : STOP_PENDING" else "" }.stop("cringle-daemon")
        }
        assertTrue(e.message!!.contains("did not stop"))
    }

    @Test
    fun startFailsWithTheOutputOfSc() {
        val e = assertThrows<IllegalStateException> { controller { 1061 to "The service cannot accept control messages" }.start("cringle-daemon") }
        assertTrue(e.message!!.contains("1061") && e.message!!.contains("cannot accept control messages"))
    }

    @Test
    fun startOfARunningServiceIsNotAnError() {
        controller { 1056 to "already running" }.start("cringle-daemon")
    }
}
