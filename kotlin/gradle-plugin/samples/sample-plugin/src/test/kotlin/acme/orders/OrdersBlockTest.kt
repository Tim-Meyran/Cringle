// SPDX-License-Identifier: Apache-2.0

package acme.orders

import cringle.contract.TetherType
import cringle.testkit.BlockTestHarness
import cringle.testkit.TestDriverSet
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OrdersBlockTest {

    @Test
    fun ordersBlockLifecycleAndPorts() = runTest {
        val drivers = TestDriverSet().add(OrdersDriver::class, OrdersDriver())
        val harness = BlockTestHarness.forProvider(OrdersProvider(), "orders", drivers)
        harness.runLifecycle {
            assertEquals(BlockTestHarness.State.STARTED, state)
            assertEquals(TetherType.REQUEST_RESPONSE, ports.tether("in").type)
            assertEquals(TetherType.MESSAGE, ports.tether("out").type)
            sendMessage("in", mapOf("id" to "order-1", "total" to 42))
            assertTrue(ports.tether("out").sentMessages.isEmpty())
        }
    }
}
