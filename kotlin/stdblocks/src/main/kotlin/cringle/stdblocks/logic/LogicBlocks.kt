// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.logic

import cringle.contract.SchemaRef
import cringle.stdblocks.BOOLEAN
import cringle.stdblocks.DOUBLE
import cringle.stdblocks.Entry
import cringle.stdblocks.STRING
import cringle.stdblocks.asDouble
import cringle.stdblocks.handlerEntry
import cringle.stdblocks.inPort
import cringle.stdblocks.latest2Entry
import cringle.stdblocks.outPort

private val OPERATORS = setOf("EQ", "NE", "LT", "LE", "GT", "GE")

private fun operator(config: Map<String, Any?>): String {
    val name = config["operator"] as? String ?: "EQ"
    require(name in OPERATORS) { "the configuration 'operator' is one of ${OPERATORS.joinToString()}, not '$name'" }
    return name
}

private fun test(operator: String, order: Int): Boolean = when (operator) {
    "EQ" -> order == 0
    "NE" -> order != 0
    "LT" -> order < 0
    "LE" -> order <= 0
    "GT" -> order > 0
    else -> order >= 0
}

private fun bool(name: String, f: (Boolean, Boolean) -> Boolean) =
    latest2Entry(name, BOOLEAN, listOf(outPort("out", BOOLEAN))) { _ -> { a, b, emit -> emit.send("out", f(a as Boolean, b as Boolean)) } }

/** `if`: the latest `condition` decides which output a `value` goes to (no condition yet counts as false). */
private fun branch(name: String, type: SchemaRef) =
    handlerEntry(name, listOf(inPort("condition", BOOLEAN), inPort("value", type), outPort("then", type), outPort("else", type))) { _ ->
        var condition = false
        return@handlerEntry { port, v, emit ->
            if (port == "condition") condition = v as Boolean else emit.send(if (condition) "then" else "else", v)
        }
    }

/** `filter`: a `value` passes while the latest `condition` is true. */
private fun filter(name: String, type: SchemaRef) =
    handlerEntry(name, listOf(inPort("condition", BOOLEAN), inPort("value", type), outPort("out", type))) { _ ->
        var condition = false
        return@handlerEntry { port, v, emit ->
            if (port == "condition") condition = v as Boolean else if (condition) emit.send("out", v)
        }
    }

internal val LOGIC_ENTRIES: List<Entry> = listOf(
    latest2Entry("logic.compare", DOUBLE, listOf(outPort("out", BOOLEAN)), "CompareConfig") { config ->
        val op = operator(config)
        return@latest2Entry { a, b, emit -> emit.send("out", test(op, a.asDouble().compareTo(b.asDouble()))) }
    },
    latest2Entry("logic.compare-text", STRING, listOf(outPort("out", BOOLEAN)), "CompareConfig") { config ->
        val op = operator(config)
        return@latest2Entry { a, b, emit -> emit.send("out", test(op, (a as String).compareTo(b as String))) }
    },
    bool("logic.and") { a, b -> a && b },
    bool("logic.or") { a, b -> a || b },
    bool("logic.xor") { a, b -> a != b },
    handlerEntry("logic.not", listOf(inPort("in", BOOLEAN), outPort("out", BOOLEAN))) { _ -> { _, v, emit -> emit.send("out", !(v as Boolean)) } },
    branch("logic.if", STRING),
    branch("logic.if-number", DOUBLE),
    filter("logic.filter", STRING),
    filter("logic.filter-number", DOUBLE),
)
