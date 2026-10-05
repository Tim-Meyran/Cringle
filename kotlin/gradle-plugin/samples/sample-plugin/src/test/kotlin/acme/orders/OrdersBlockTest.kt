// SPDX-License-Identifier: Apache-2.0

package acme.orders

import cringle.contract.TetherType
import cringle.testkit.BlockTestHarness
import cringle.testkit.TestDriverSet
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the sample plugin's [OrdersBlock]. [OrdersBlock] is a stub: it does not override `init`, `start`,
 * `stop`, or `destroy`, and it does not forward tether events to any port. The test therefore asserts that the
 * lifecycle runs through to DESTROYED, that the ports are wired with the right types, and that a message delivered
 * to `in` is NOT echoed on `out` (because the stub does nothing).
 */
class OrdersBlockTest {

    @Test
    fun ordersBlockLifecycleAndStubBehavior() = runTest {
        val drivers = TestDriverSet().add(OrdersDriver::class, OrdersDriver())
        val harness = BlockTestHarness.forProvider(OrdersProvider(), "orders", drivers)
        harness.runLifecycle {
            assertEquals(BlockTestHarness.State.STARTED, state)
            assertEquals(TetherType.REQUEST_RESPONSE, ports.tether("in").type)
            assertEquals(TetherType.MESSAGE, ports.tether("out").type)
            sendMessage("in", mapOf("id" to "order-1", "total" to 42))
            assertTrue(ports.tether("out").sentMessages.isEmpty())
        }
        assertEquals(BlockTestHarness.State.DESTROYED, harness.state)
    }
}
