// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.text

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.Tether
import cringle.contract.TetherEvent

/** Turns the integer that arrives at `in` into its decimal text on `out`. */
internal class ToString : Block {
    private lateinit var out: Tether

    override suspend fun init(context: BlockContext) {
        out = context.ports.port("out")
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event is TetherEvent.Message && event.port.name == "in") out.send((event.value as Number).toLong().toString())
    }
}
