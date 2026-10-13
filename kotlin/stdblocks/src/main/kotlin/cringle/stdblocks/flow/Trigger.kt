// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.flow

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.Tether
import cringle.contract.TetherEvent
import kotlinx.coroutines.CancellationException

/**
 * Collects values for one trigger. Every slot `i` has three ports: `in[i]` takes values that arrive on their own (the block holds the latest one),
 * `request[i]` is a request tether that the block **pulls** with when the trigger comes (an input block answers it with its value), and `out[i]` gets the
 * result. For every message at `trigger` the block asks every connected `request[i]`; a slot whose `request[i]` is not connected sends its held `in[i]`
 * (nothing, if there is none). After the values it sends a new trigger signal on `done`: the number of triggers so far (an `Int`, like the tick of a
 * timer), so that the next step of a flow can be started by it. The numbers of `in`, `request` and `out` have to be equal.
 *
 * An output (`out[i]`, `done`) that nothing is connected to is skipped, not an error.
 *
 * The request carries the trigger value; a request and its answer are checked against the type of the port, so a typed variant sends a neutral value of its type
 * (`requestPayload`: an empty text, 0, `false`), which the input blocks ignore.
 *
 * A slot that cannot be pulled (the answer fails or times out) does not stop the others; the first failure is thrown after all slots and `done`.
 */
internal class Trigger(private val requestPayload: (Any) -> Any = { it }) : Block {
    private lateinit var requests: List<Tether>
    private lateinit var outputs: List<Tether>
    private lateinit var done: Tether
    private lateinit var held: Array<Any?>
    private lateinit var notPullable: BooleanArray
    private var count = 0L

    override suspend fun init(context: BlockContext) {
        val inputs = context.ports.varArgPort("in")
        requests = context.ports.varArgPort("request")
        outputs = context.ports.varArgPort("out")
        done = context.ports.port("done")
        require(inputs.size == outputs.size && requests.size == outputs.size) {
            "the block has ${inputs.size} inputs 'in', ${requests.size} requests 'request' and ${outputs.size} outputs 'out': the numbers have to be equal"
        }
        held = arrayOfNulls(inputs.size)
        notPullable = BooleanArray(inputs.size)
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event !is TetherEvent.Message) return
        when (event.port.name) {
            "in" -> held[event.port.index ?: return] = event.value
            "trigger" -> {
                var failure: Exception? = null
                for (i in held.indices) {
                    try {
                        (pull(i, event.value) ?: held[i])?.let { sendIfConnected(outputs[i], it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (failure == null) failure = e else failure.addSuppressed(e)
                    }
                }
                sendIfConnected(done, ++count)
                failure?.let { throw it }
            }
        }
    }

    /** An output that no tether is connected to is not an error here: `done` and the slots are optional parts of a flow. */
    private suspend fun sendIfConnected(tether: Tether, value: Any) {
        try {
            tether.send(value)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("is not connected") != true) throw e
        }
    }

    /** The answer of `request[i]`, or `null` if that port is not connected in the blueprint. */
    private suspend fun pull(i: Int, trigger: Any): Any? {
        if (notPullable[i]) return null
        return try {
            requests[i].request(requestPayload(trigger))
        } catch (e: IllegalStateException) {
            if (e.message?.contains("is not connected") != true) throw e
            notPullable[i] = true
            null
        }
    }
}
