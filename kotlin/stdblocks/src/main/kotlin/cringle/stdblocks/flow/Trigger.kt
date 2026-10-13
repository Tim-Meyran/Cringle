// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.flow

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.Tether
import cringle.contract.TetherEvent

/**
 * Holds the latest value of every input `in[i]` and, for every message at `trigger`, sends the held values 1:1 on `out[i]`. A tether cannot be pulled (an input
 * never initiates), so the block remembers what arrived; an input that has no value yet sends nothing. The numbers of `in` and `out` have to be equal.
 * After the values it sends a new trigger signal on `done`: the number of triggers so far (an `Int`, like the tick of a timer), so that the next step of a flow
 * can be started by it.
 */
internal class Trigger : Block {
    private lateinit var outputs: List<Tether>
    private lateinit var done: Tether
    private var count = 0L
    private lateinit var held: Array<Any?>

    override suspend fun init(context: BlockContext) {
        val inputs = context.ports.varArgPort("in")
        outputs = context.ports.varArgPort("out")
        done = context.ports.port("done")
        require(inputs.size == outputs.size) { "the block has ${inputs.size} inputs 'in' but ${outputs.size} outputs 'out': the numbers have to be equal" }
        held = arrayOfNulls(inputs.size)
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event !is TetherEvent.Message) return
        when (event.port.name) {
            "in" -> held[event.port.index ?: return] = event.value
            "trigger" -> {
                for (i in held.indices) held[i]?.let { outputs[i].send(it) }
                done.send(++count)
            }
        }
    }
}
