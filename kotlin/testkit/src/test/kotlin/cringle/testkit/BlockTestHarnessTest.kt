// SPDX-License-Identifier: Apache-2.0

package cringle.testkit

import cringle.contract.BlockContext
import cringle.contract.TetherType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BlockTestHarnessTest {
    private fun shout(): Triple<BlockTestHarness, InMemoryDwhDriver, TestDriverSet> {
        val dwh = InMemoryDwhDriver()
        val drivers = TestDriverSet().add(cringle.contract.DwhDriver::class, dwh)
        return Triple(BlockTestHarness.forProvider(SampleProvider, "shout", drivers), dwh, drivers)
    }

    @Test
    fun lifecycleAndMessageRoundTrip() = runTest {
        val (harness, dwh, drivers) = shout()
        drivers.startAll()
        val block = harness.block as ShoutBlock
        assertEquals(BlockTestHarness.State.CREATED, harness.state)
        harness.init()
        harness.start()
        assertTrue(block.started)
        harness.sendMessage("in", "hello")
        harness.sendMessage("in", "world")
        assertEquals(listOf<Any>("HELLO", "WORLD"), harness.ports.tether("out").sentMessages)
        assertEquals(listOf<Any>("HELLO", "WORLD"), dwh.entries.map { it.value })
        harness.stop()
        assertFalse(block.started)
        harness.destroy()
        drivers.stopAll()
        assertEquals(BlockTestHarness.State.DESTROYED, harness.state)
        assertEquals(listOf("start", "stop"), dwh.calls)
    }

    @Test
    fun runLifecycleStopsAndDestroysEvenWhenTheBodyFails() = runTest {
        val (harness, _, _) = shout()
        assertThrows<IllegalArgumentException> {
            harness.runLifecycle { throw IllegalArgumentException("boom") }
        }
        assertEquals(BlockTestHarness.State.DESTROYED, harness.state)
    }

    @Test
    fun lifecycleOrderIsEnforced() = runTest {
        val (harness, _, _) = shout()
        assertTrue(assertThrows<IllegalStateException> { harness.start() }.message!!.contains("state CREATED"))
        harness.init()
        assertThrows<IllegalStateException> { harness.init() }
        assertTrue(assertThrows<IllegalStateException> { harness.sendMessage("in", "x") }.message!!.contains("started block"))
        harness.start()
        assertThrows<IllegalStateException> { harness.destroy() }
        harness.stop()
        assertThrows<IllegalStateException> { harness.start() }
        harness.destroy()
        assertThrows<IllegalStateException> { harness.destroy() }
    }

    @Test
    fun requestResponseRoundTrip() = runTest {
        val harness = BlockTestHarness.forProvider(SampleProvider, "counter")
        harness.runLifecycle {
            assertEquals(1, request("ask", "a"))
            assertEquals(2, request("ask", "b"))
        }
    }

    @Test
    fun unansweredRequestFailsWithAssertionError() = runTest {
        val silent = object : cringle.contract.Block {}
        val harness = BlockTestHarness(silent)
        harness.init()
        harness.start()
        val e = assertThrows<AssertionError> { harness.request("ask", "x") }
        assertTrue(e.message!!.contains("did not respond"))
    }

    @Test
    fun streamsAreDeliveredAndAnsweredThroughTheTestSide() = runTest {
        val harness = BlockTestHarness.forProvider(SampleProvider, "counter")
        harness.init()
        harness.start()
        val stream = harness.withStream("ask") { s ->
            s.feed("a")
            s.feed("b")
            testScheduler.advanceUntilIdle()
            assertEquals(listOf<Any>("echo:a", "echo:b"), s.sent)
            s
        }
        assertEquals(2, stream.sent.size)
        harness.stop()
        harness.destroy()
    }

    @Test
    fun byteStreamRoundTrip() = runTest {
        val harness = BlockTestHarness(CounterBlock())
        harness.init()
        harness.start()
        harness.withByteStream("b") { bytes ->
            bytes.feed(byteArrayOf(1, 2, 3))
            testScheduler.advanceUntilIdle()
            assertArrayEquals(byteArrayOf(3, 2, 1), bytes.sent.single())
        }
    }

    @Test
    fun varArgPortsAreBuiltFromTheDefinition() = runTest {
        val definition = cringle.contract.BlockDefinition(
            "fan",
            emptyList(),
            listOf(
                cringle.contract.PortDefinition("in", cringle.contract.PortDirection.IN, setOf(TetherType.MESSAGE), orderSchema),
                cringle.contract.PortDefinition("fanout", cringle.contract.PortDirection.OUT, setOf(TetherType.MESSAGE), orderSchema, varArg = true),
            ),
            emptyList(),
        )
        val ports = TestBlockPorts.forDefinition(definition, varArgSizes = mapOf("fanout" to 3))
        lateinit var context: BlockContext
        val block = object : cringle.contract.Block {
            override suspend fun init(context: BlockContext) {
                this@BlockTestHarnessTest.holder = context
            }
        }
        val harness = BlockTestHarness(block, ports = ports)
        harness.init()
        context = holder
        assertEquals(3, context.ports.varArgPort("fanout").size)
        val fan = FanoutBlock { context }
        fan.onTetherEvent(cringle.contract.TetherEvent.Message(cringle.contract.PortRef("in"), 2 to "x"))
        assertEquals(listOf<Any>("x"), ports.varArgTethers("fanout")[2].sentMessages)
        assertTrue(ports.varArgTethers("fanout")[0].sentMessages.isEmpty())
    }

    private lateinit var holder: BlockContext

    @Test
    fun forProviderRejectsUnknownDefinition() {
        assertThrows<IllegalArgumentException> { BlockTestHarness.forProvider(SampleProvider, "nope") }
    }
}
