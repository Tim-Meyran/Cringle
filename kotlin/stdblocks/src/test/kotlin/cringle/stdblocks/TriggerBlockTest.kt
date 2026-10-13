// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import cringle.testkit.BlockTestHarness
import cringle.testkit.InMemoryTether
import cringle.testkit.TestBlockPorts
import cringle.testkit.TestDriverSet
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TriggerBlockTest {
    private fun TestBlockPorts.slots(name: String) = varArgPort(name).map { it as InMemoryTether }

    private fun harness(inputs: Int, requests: Int = inputs, outputs: Int = inputs): BlockTestHarness =
        BlockTestHarness.forProvider(StdBlockProvider(), "flow.trigger", TestDriverSet(), emptyMap(), mapOf("in" to inputs, "request" to requests, "out" to outputs))

    /** What the engine says for a request port that no tether is connected to. */
    private val notConnected: suspend (Any) -> Any = { throw IllegalStateException("port 'request[0]' of block 'trig' is not connected in the blueprint") }

    @Test
    fun theTriggerReleasesTheHeldValuesOnTheMatchingOutputsAndSignalsDone() = runTest {
        val h = harness(3)
        h.runLifecycle {
            val out = ports.slots("out")
            ports.slots("request").forEach { it.requestHandler = notConnected }
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
    fun theTriggerPullsTheValuesOfConnectedRequestPortsAndHoldsTheRest() = runTest {
        val h = harness(3)
        h.runLifecycle {
            val request = ports.slots("request")
            val out = ports.slots("out")
            request[0].requestHandler = { "pulled text" }
            request[1].requestHandler = notConnected
            request[2].requestHandler = { 4.5 }
            sendMessage("in", "held", 1)
            sendMessage("in", "ignored, the pull wins", 0)
            sendMessage("trigger", 7L)
            assertEquals(listOf<Any>(7L), request[0].requests, "the trigger value is the request")
            assertEquals(listOf<Any>("pulled text"), out[0].sentMessages)
            assertEquals(listOf<Any>("held"), out[1].sentMessages, "a slot without a request tether sends its held value")
            assertEquals(listOf<Any>(4.5), out[2].sentMessages)
            // a pull asks again every time
            request[0].requestHandler = { "second" }
            sendMessage("trigger", 8L)
            assertEquals(listOf<Any>("pulled text", "second"), out[0].sentMessages)
            assertEquals(listOf<Any>(1L, 2L), ports.tether("done").sentMessages)
            assertEquals(1, request[1].requests.size, "a request port that is not connected is asked once and then left alone")
        }
    }

    @Test
    fun aPullThatFailsDoesNotStopTheOtherSlotsOrDone() = runTest {
        val h = harness(2)
        h.init()
        h.start()
        val request = h.ports.slots("request")
        val out = h.ports.slots("out")
        request[0].requestHandler = { throw IllegalStateException("the input block does not answer") }
        request[1].requestHandler = { "fine" }
        val e = assertThrows<IllegalStateException> { h.sendMessage("trigger", 1L) }
        assertEquals("the input block does not answer", e.message)
        assertEquals(listOf<Any>("fine"), out[1].sentMessages)
        assertEquals(listOf<Any>(1L), h.ports.tether("done").sentMessages)
    }

    @Test
    fun theTypedTriggersHaveTypedSlotPortsAndBehaveLikeTheGenericOne() = runTest {
        val variants = mapOf(
            "flow.trigger-string" to Triple(STRING, "text", "pulled"),
            "flow.trigger-number" to Triple(DOUBLE, 2.5, 3.5),
            "flow.trigger-int" to Triple(INT, 7L, 8L),
            "flow.trigger-boolean" to Triple(BOOLEAN, true, false),
        )
        val neutral = mapOf("flow.trigger-string" to "", "flow.trigger-number" to 0.0, "flow.trigger-int" to 0L, "flow.trigger-boolean" to false)
        for ((name, sample) in variants) {
            val (type, held, pulled) = sample
            val definition = StdBlockProvider().definitions.single { it.name == name }
            for (port in listOf("in", "out", "request")) assertEquals(type, definition.ports.single { it.name == port }.schema, "$name $port")
            assertEquals(ANY, definition.ports.single { it.name == "trigger" }.schema, "$name trigger")
            assertEquals(INT, definition.ports.single { it.name == "done" }.schema, "$name done")
            assertEquals(setOf(cringle.contract.TetherType.REQUEST_RESPONSE), definition.ports.single { it.name == "request" }.tetherTypes)

            val h = BlockTestHarness.forProvider(StdBlockProvider(), name, TestDriverSet(), emptyMap(), mapOf("in" to 2, "request" to 2, "out" to 2))
            h.runLifecycle {
                val request = ports.slots("request")
                request[0].requestHandler = notConnected
                request[1].requestHandler = { pulled }
                sendMessage("in", held, 0)
                sendMessage("trigger", 1L)
                assertEquals(listOf<Any>(held), ports.slots("out")[0].sentMessages, "$name: the held value")
                assertEquals(listOf<Any>(pulled), ports.slots("out")[1].sentMessages, "$name: the pulled value")
                assertEquals(listOf<Any>(1L), ports.tether("done").sentMessages)
                // the request is of the type of the port: a neutral value, not the trigger signal (an Int that a text port would refuse)
                assertEquals(listOf<Any>(neutral.getValue(name)), request[1].requests, name)
            }
        }
    }

    @Test
    fun aTriggerWithoutValuesStillSignalsDone() = runTest {
        val h = harness(1)
        h.runLifecycle {
            ports.slots("request").single().requestHandler = notConnected
            sendMessage("trigger", 1L)
            assertEquals(emptyList<Any>(), ports.slots("out").single().sentMessages)
            assertEquals(listOf<Any>(1L), ports.tether("done").sentMessages)
        }
    }

    @Test
    fun theTriggerRefusesDifferentNumbersOfInputsRequestsAndOutputs() = runTest {
        val e = assertThrows<IllegalArgumentException> { harness(2, 2, 3).init() }
        assertEquals("the block has 2 inputs 'in', 2 requests 'request' and 3 outputs 'out': the numbers have to be equal", e.message)
        assertThrows<IllegalArgumentException> { harness(2, 1, 2).init() }
    }
}
