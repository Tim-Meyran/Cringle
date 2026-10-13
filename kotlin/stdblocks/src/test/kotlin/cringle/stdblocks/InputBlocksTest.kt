// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import cringle.testkit.BlockTestHarness
import cringle.testkit.TestDriverSet
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InputBlocksTest {
    private fun harness(name: String, config: Map<String, Any?>) = BlockTestHarness.forProvider(StdBlockProvider(), name, TestDriverSet(), config)

    @Test
    fun theTextInputAnswersARequestAndPushesOnATrigger() = runTest {
        harness("input.text", mapOf("value" to "hello")).runLifecycle {
            assertEquals("hello", request("get", 1L))
            assertEquals("hello", request("get", "anything"))
            sendMessage("trigger", 1L)
            sendMessage("trigger", 2L)
            assertEquals(listOf<Any>("hello", "hello"), ports.tether("out").sentMessages)
        }
        assertThrows<IllegalArgumentException> { harness("input.text", emptyMap()).init() }
        assertThrows<IllegalArgumentException> { harness("input.text", mapOf("value" to 5)).init() }
    }

    @Test
    fun theNumberInputAnswersWithADouble() = runTest {
        harness("input.number", mapOf("value" to 3)).runLifecycle {
            assertEquals(3.0, request("get", 1L), "an integer in the configuration is a number")
            sendMessage("trigger", 1L)
            assertEquals(listOf<Any>(3.0), ports.tether("out").sentMessages)
        }
        harness("input.number", mapOf("value" to -2.5)).runLifecycle { assertEquals(-2.5, request("get", 1L)) }
        assertThrows<IllegalArgumentException> { harness("input.number", emptyMap()).init() }
        assertThrows<IllegalArgumentException> { harness("input.number", mapOf("value" to "x")).init() }
        assertThrows<IllegalArgumentException> { harness("input.number", mapOf("value" to Double.POSITIVE_INFINITY)).init() }
    }
}
