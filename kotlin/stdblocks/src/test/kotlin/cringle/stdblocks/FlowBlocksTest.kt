// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FlowBlocksTest {
    @Test
    fun fanOutCopiesEveryValueToAllThreeOutputsForEachType() = runTest {
        val samples = mapOf("flow.fan-out" to "text", "flow.fan-out-int" to 7L, "flow.fan-out-number" to 2.5, "flow.fan-out-boolean" to true)
        for ((name, value) in samples) {
            std(name) {
                send("in", value)
                send("in", value)
                for (port in listOf("a", "b", "c")) assertEquals(listOf(value, value), out(port), "$name $port")
            }
        }
    }
}
