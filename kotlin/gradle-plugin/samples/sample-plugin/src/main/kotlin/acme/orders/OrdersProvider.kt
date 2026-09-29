// SPDX-License-Identifier: Apache-2.0

package acme.orders

import cringle.contract.Block
import cringle.contract.BlockDefinition
import cringle.contract.BlockProvider
import cringle.contract.DriverSet
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType

/**
 * The block provider of the sample plugin. Its name goes into the `providers` list of the manifest, the definitions
 * below are declared a second time in the `cringle { }` block of the build script, which is what ends up in the
 * package.
 */
public class OrdersProvider : BlockProvider {

    override val definitions: List<BlockDefinition> = listOf(ORDERS)

    override fun createBlock(definitionName: String, drivers: DriverSet): Block {
        require(definitionName == ORDERS.name) { "this provider has no definition named '$definitionName'" }
        return OrdersBlock(drivers[OrdersDriver::class])
    }

    private companion object {

        /** The block the sample plugin contributes. */
        val ORDERS = BlockDefinition(
            name = "orders",
            schemas = listOf(SchemaRef("acme.orders", "Order")),
            configSchema = SchemaRef("acme.orders", "OrdersConfig"),
            ports = listOf(
                PortDefinition("in", PortDirection.IN, setOf(TetherType.REQUEST_RESPONSE), SchemaRef("acme.orders", "Order")),
                PortDefinition("out", PortDirection.OUT, setOf(TetherType.MESSAGE), SchemaRef("acme.orders", "Order")),
            ),
            requiredDrivers = listOf("acme.orders.orders"),
        )
    }
}

/** The block of the sample plugin. It only has to exist, the sample has no behaviour. */
private class OrdersBlock(private val driver: OrdersDriver) : Block
