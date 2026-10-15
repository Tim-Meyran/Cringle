// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.text

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.Tether
import cringle.contract.TetherEvent
import cringle.contract.TetherType
import cringle.stdblocks.ANY
import cringle.stdblocks.Entry
import cringle.stdblocks.STRING
import cringle.stdblocks.entry
import cringle.stdblocks.toJson

/**
 * `text.format`: a text from a template with named variables. The names come from the property `variables` (the block has one input slot at `args` for each name),
 * the `template` uses them as `{name}` (`{{` and `}}` are a `{` and a `}`). A value that is not a text is written as JSON.
 *
 * The block works both ways: a value that arrives at a slot (a message) is kept, and as soon as every variable has a value the text goes out on `out`; a message at
 * `trigger` sends the text with the values that are known. And when `out` is asked for its value (a pull tether) the block asks every slot that is connected as a
 * pull (the context of the request goes on), uses the kept value for a slot that is not, and answers the text.
 */
internal class TextFormat : Block {
    private lateinit var template: List<Part>
    private lateinit var names: List<String>
    private lateinit var slots: List<Tether>
    private lateinit var out: Tether
    private lateinit var values: Array<Any?>

    internal sealed interface Part {
        data class Literal(val text: String) : Part

        data class Variable(val index: Int) : Part
    }

    override suspend fun init(context: BlockContext) {
        val text = context.config["template"] as? String ?: throw IllegalArgumentException("the configuration 'template' is missing")
        names = (context.config["variables"] as? List<*>)?.map { it.toString() } ?: listOf("value")
        require(names.toSet().size == names.size) { "the names of the variables are used twice" }
        slots = context.ports.varArgPort("args")
        require(slots.size == names.size) { "the block has ${slots.size} slots but ${names.size} variables: the number of slots follows the names in the properties" }
        template = parse(text, names)
        out = context.ports.port("out")
        values = arrayOfNulls(names.size)
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        when {
            event is TetherEvent.Message && event.port.name == "args" -> {
                values[event.port.index ?: 0] = event.value
                if (values.none { it == null }) out.send(render(values.map { it!! }))
            }
            event is TetherEvent.Message && event.port.name == "trigger" -> out.send(render(known()))
            event is TetherEvent.Request && event.port.name == "out" -> event.respond(render(asked(event.value)))
        }
    }

    /** The kept values; a variable without a value is an error that names it. */
    private fun known(): List<Any> = values.mapIndexed { i, v -> v ?: throw IllegalStateException("the variable '${names[i]}' has no value yet") }

    /** The value of every variable: asked at a slot that is connected as a pull, else the kept one. */
    private suspend fun asked(context: Any): List<Any> = slots.mapIndexed { i, slot ->
        try {
            slot.request(context)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("is not connected") != true && e.message?.contains("cannot initiate") != true) throw e
            values[i] ?: throw IllegalStateException("the variable '${names[i]}' has no value: connect a block to its slot")
        }
    }

    private fun render(values: List<Any>): String = template.joinToString("") { part ->
        when (part) {
            is Part.Literal -> part.text
            is Part.Variable -> values[part.index].let { it as? String ?: toJson(it) }
        }
    }

    companion object {
        /** The parts of [text]: literals and variables; throws [IllegalArgumentException] for a `{name}` that is not in [names] or a brace that is alone. */
        fun parse(text: String, names: List<String>): List<Part> {
            val parts = ArrayList<Part>()
            val literal = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '{' && text.startsWith("{{", i) -> { literal.append('{'); i += 2 }
                    c == '}' && text.startsWith("}}", i) -> { literal.append('}'); i += 2 }
                    c == '{' -> {
                        val end = text.indexOf('}', i)
                        require(end > 0) { "the template has a '{' that is not closed (write {{ for a brace)" }
                        val name = text.substring(i + 1, end)
                        val index = names.indexOf(name)
                        require(index >= 0) { "the template uses {$name}, which is not one of the variables (${names.joinToString()})" }
                        if (literal.isNotEmpty()) parts += Part.Literal(literal.toString()).also { literal.clear() }
                        parts += Part.Variable(index)
                        i = end + 1
                    }
                    c == '}' -> throw IllegalArgumentException("the template has a '}' without a '{' (write }} for a brace)")
                    else -> { literal.append(c); i += 1 }
                }
            }
            if (literal.isNotEmpty()) parts += Part.Literal(literal.toString())
            return parts
        }
    }
}

internal val TEXT_FORMAT_ENTRY: Entry = entry(
    "text.format",
    listOf(
        // the values of the variables: pushed in, or asked for when the text is asked for; named by the property `variables`
        PortDefinition("args", PortDirection.IN, setOf(TetherType.MESSAGE, TetherType.PULL), ANY, varArg = true, namesFrom = "variables"),
        PortDefinition("trigger", PortDirection.IN, setOf(TetherType.MESSAGE), ANY),
        PortDefinition("out", PortDirection.OUT, setOf(TetherType.MESSAGE, TetherType.PULL), STRING),
    ),
    "TextFormatConfig",
) { TextFormat() }
