// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import cringle.testkit.BlockTestHarness
import cringle.testkit.TestDriverSet
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TriggerBlockTest {
    private fun cringle.testkit.TestBlockPorts.outs(name: String) = varArgPort(name).map { it as cringle.testkit.InMemoryTether }

    private fun harness(inputs: Int, outputs: Int): BlockTestHarness =
        BlockTestHarness.forProvider(StdBlockProvider(), "flow.trigger", TestDriverSet(), emptyMap(), mapOf("in" to inputs, "out" to outputs))

    @Test
    fun theTriggerReleasesTheHeldValuesOnTheMatchingOutputsAndSignalsDone() = runTest {
        val h = harness(3, 3)
        h.runLifecycle {
            val out = ports.outs("out")
            // nothing is sent before a trigger, whatever arrives at the inputs
            sendMessage("in", "a", 0)
            sendMessage("in", 2L, 2)
            assertEquals(emptyList<Any>(), out.flatMap { it.sentMessages })
            sendMessage("trigger", 1L)
            assertEquals(listOf<Any>("a"), out[0].sentMessages)
            assertEquals(emptyList<Any>(), out[1].sentMessages, "an input without a value sends nothing")
            assertEquals(listOf<Any>(2L), out[2].sentMessages)
            assertEquals(listOf<Any>(1L), ports.tether("done").sentMessages, "the new trigger signal follows the values")
            // the values are held: a second trigger sends them again; a newer value replaces the older one
            sendMessage("in", mapOf("k" to listOf(1L)), 1)
            sendMessage("in", "b", 0)
            sendMessage("trigger", "any message starts it")
            assertEquals(listOf<Any>("a", "b"), out[0].sentMessages)
            assertEquals(listOf<Any>(mapOf("k" to listOf(1L))), out[1].sentMessages)
            assertEquals(listOf<Any>(2L, 2L), out[2].sentMessages)
            assertEquals(listOf<Any>(1L, 2L), ports.tether("done").sentMessages)
        }
    }

    @Test
    fun aTriggerWithoutInputsStillSignalsDone() = runTest {
        val h = harness(1, 1)
        h.runLifecycle {
            sendMessage("trigger", 1L)
            assertEquals(emptyList<Any>(), ports.outs("out").single().sentMessages)
            assertEquals(listOf<Any>(1L), ports.tether("done").sentMessages)
        }
    }

    @Test
    fun theTriggerRefusesDifferentNumbersOfInputsAndOutputs() = runTest {
        val e = assertThrows<IllegalArgumentException> { harness(2, 3).init() }
        assertEquals("the block has 2 inputs 'in' but 3 outputs 'out': the numbers have to be equal", e.message)
    }
}
