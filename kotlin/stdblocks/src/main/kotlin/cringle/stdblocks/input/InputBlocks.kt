// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.input

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.Tether
import cringle.contract.TetherEvent
import cringle.contract.TetherType
import cringle.stdblocks.ANY
import cringle.stdblocks.DOUBLE
import cringle.stdblocks.Entry
import cringle.stdblocks.STRING
import cringle.stdblocks.entry
import cringle.stdblocks.inPort
import cringle.stdblocks.outPort
import cringle.stdblocks.parseJson

/**
 * A value that is set in the configuration. It answers a request at `get` (`flow.trigger` pulls it that way) and, as a push, sends the value on `out` for every
 * message at `trigger`.
 */
internal class ConfiguredValue(private val read: (Map<String, Any?>) -> Any) : Block {
    private lateinit var value: Any
    private lateinit var out: Tether

    override suspend fun init(context: BlockContext) {
        value = read(context.config)
        out = context.ports.port("out")
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        when {
            event is TetherEvent.Request && event.port.name == "get" -> event.respond(value)
            event is TetherEvent.Message && event.port.name == "trigger" -> out.send(value)
        }
    }
}

private fun input(name: String, configuration: String, type: SchemaRef, read: (Map<String, Any?>) -> Any) = entry(
    name,
    listOf(
        PortDefinition("get", PortDirection.IN, setOf(TetherType.REQUEST_RESPONSE), ANY),
        inPort("trigger", ANY),
        outPort("out", type),
    ),
    configuration,
) { ConfiguredValue(read) }

internal val INPUT_ENTRIES: List<Entry> = listOf(
    input("input.text", "TextInputConfig", STRING) { config -> config["value"] as? String ?: throw IllegalArgumentException("the configuration 'value' is missing") },
    input("input.json", "JsonInputConfig", ANY) { config ->
        val text = config["value"] as? String ?: throw IllegalArgumentException("the configuration 'value' is missing")
        try {
            parseJson(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("the configuration 'value' is not valid JSON: ${e.message}")
        }
    },
    input("input.number", "NumberInputConfig", DOUBLE) { config ->
        val number = (config["value"] as? Number)?.toDouble() ?: throw IllegalArgumentException("the configuration 'value' is missing")
        require(number.isFinite()) { "the configuration 'value' must be a finite number" }
        number
    },
)
