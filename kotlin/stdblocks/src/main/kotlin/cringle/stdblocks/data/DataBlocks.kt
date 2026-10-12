// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.data

import cringle.stdblocks.ANY
import cringle.stdblocks.BOOLEAN
import cringle.stdblocks.DOUBLE
import cringle.stdblocks.Entry
import cringle.stdblocks.INT
import cringle.stdblocks.STRING
import cringle.stdblocks.handlerEntry
import cringle.stdblocks.inPort
import cringle.stdblocks.outPort
import cringle.stdblocks.toJson

private fun kind(value: Any): String = when (value) {
    is String -> "a text"
    is Boolean -> "a boolean"
    is Number -> "a number"
    is Map<*, *> -> "an object"
    is Iterable<*> -> "a list"
    else -> "something else"
}

/** A cast of the value at `in` (any type): `out` gets it as the wanted type, `error` says what it was when it does not fit. */
private fun cast(name: String, wanted: String, type: cringle.contract.SchemaRef, convert: (Any) -> Any?) =
    handlerEntry(name, listOf(inPort("in", ANY), outPort("out", type), outPort("error", STRING))) { _ ->
        return@handlerEntry { _, v, emit ->
            val converted = convert(v)
            if (converted != null) emit.send("out", converted) else emit.send("error", "expected $wanted, got ${kind(v)}: ${toJson(v)}")
        }
    }

internal val DATA_ENTRIES: List<Entry> = listOf(
    cast("data.as-string", "a text", STRING) { it as? String },
    cast("data.as-number", "a number", DOUBLE) { (it as? Number)?.toDouble() },
    cast("data.as-int", "a whole number", INT) { v ->
        when (v) {
            is Long -> v
            is Int -> v.toLong()
            is Number -> v.toDouble().takeIf { it.isFinite() && it == Math.rint(it) && Math.abs(it) < 9.0e18 }?.toLong()
            else -> null
        }
    },
    cast("data.as-boolean", "a boolean", BOOLEAN) { it as? Boolean },
)
