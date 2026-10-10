// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.flow

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.Tether
import cringle.contract.TetherEvent

/** Sends the configured `value` on `out` every time a message arrives at `trigger`. */
internal class Constant : Block {
    private lateinit var value: String
    private lateinit var out: Tether

    override suspend fun init(context: BlockContext) {
        value = context.config["value"] as? String ?: throw IllegalArgumentException("the configuration 'value' is missing")
        out = context.ports.port("out")
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event is TetherEvent.Message && event.port.name == "trigger") out.send(value)
    }
}
