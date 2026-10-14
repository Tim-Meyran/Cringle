// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.log

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.BuiltinDriverTypes
import cringle.contract.LogLevel
import cringle.contract.LoggingDriver
import cringle.contract.Tether
import cringle.contract.TetherEvent
import cringle.stdblocks.ANY
import cringle.stdblocks.Entry
import cringle.stdblocks.entry
import cringle.stdblocks.inPort
import cringle.stdblocks.outPort
import cringle.stdblocks.toJson

/**
 * Writes the value at `in` to the log of the fabric and sends it on `out` unchanged, so that a log step can sit in the middle of a flow. The `level` is
 * configured (default `INFO`); a text is written as it is, any other value as JSON; `template` puts the text into a sentence (`{}`), `prefix` goes in front.
 */
internal class LogWrite(private val logging: LoggingDriver) : Block {
    private var level = LogLevel.INFO
    private var prefix = ""
    private var template: String? = null
    private lateinit var out: Tether

    override suspend fun init(context: BlockContext) {
        (context.config["level"] as? String)?.let { name ->
            level = LogLevel.entries.firstOrNull { it.name == name } ?: throw IllegalArgumentException("unknown log level '$name', expected one of ${LogLevel.entries.joinToString()}")
        }
        prefix = context.config["prefix"] as? String ?: ""
        template = (context.config["template"] as? String)?.also { require("{}" in it) { "the configuration 'template' needs a '{}' where the value goes" } }
        out = context.ports.port("out")
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event !is TetherEvent.Message || event.port.name != "in") return
        val text = event.value as? String ?: toJson(event.value)
        logging.log(level, prefix + (template?.replace("{}", text) ?: text))
        out.send(event.value)
    }
}

internal val LOG_ENTRIES: List<Entry> = listOf(
    entry("log.write", listOf(inPort("in", ANY), outPort("out", ANY)), "LogWriteConfig", listOf(BuiltinDriverTypes.LOGGING.id)) { LogWrite(it[LoggingDriver::class]) },
)
