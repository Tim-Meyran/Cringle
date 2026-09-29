// SPDX-License-Identifier: Apache-2.0

package acme.orders

import cringle.contract.Driver
import cringle.contract.DriverType
import cringle.contract.IsolationLevel

/** The driver of the sample plugin. Its name and its type go into the manifest. */
public class OrdersDriver : Driver {

    override val type: DriverType = DriverType("acme.orders.orders", IsolationLevel.SHARED)

    override suspend fun start() {
        // Nothing to start; the sample only has to compile.
    }

    override suspend fun stop() {
        // Nothing to stop; the sample only has to compile.
    }
}
