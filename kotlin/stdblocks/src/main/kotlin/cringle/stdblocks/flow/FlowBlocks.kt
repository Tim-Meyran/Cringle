// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.flow

import cringle.contract.SchemaRef
import cringle.stdblocks.BOOLEAN
import cringle.stdblocks.DOUBLE
import cringle.stdblocks.Entry
import cringle.stdblocks.INT
import cringle.stdblocks.STRING
import cringle.stdblocks.handlerEntry
import cringle.stdblocks.inPort
import cringle.stdblocks.outPort

/**
 * An output port has one tether, so a value that has to go to several blocks needs a block that copies it: every message at `in` goes out on `a`, `b` and
 * `c` (connect what you need). One block for every type, because the ports have fixed types.
 */
private fun fanOut(name: String, type: SchemaRef) =
    handlerEntry(name, listOf(inPort("in", type), outPort("a", type), outPort("b", type), outPort("c", type))) { _ ->
        return@handlerEntry { _, v, emit ->
            emit.send("a", v)
            emit.send("b", v)
            emit.send("c", v)
        }
    }

internal val FLOW_ENTRIES: List<Entry> = listOf(
    fanOut("flow.fan-out", STRING),
    fanOut("flow.fan-out-int", INT),
    fanOut("flow.fan-out-number", DOUBLE),
    fanOut("flow.fan-out-boolean", BOOLEAN),
)
