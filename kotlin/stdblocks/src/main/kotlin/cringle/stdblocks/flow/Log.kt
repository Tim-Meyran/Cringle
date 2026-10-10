// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.flow

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.LogLevel
import cringle.contract.LoggingDriver
import cringle.contract.TetherEvent

/** Writes every text that arrives at `in` to the log of the fabric, with the configured `level` (default `INFO`) and an optional `prefix`. */
internal class Log(private val logging: LoggingDriver) : Block {
    private var level = LogLevel.INFO
    private var prefix = ""

    override suspend fun init(context: BlockContext) {
        (context.config["level"] as? String)?.let { name ->
            level = LogLevel.entries.firstOrNull { it.name == name } ?: throw IllegalArgumentException("unknown log level '$name'")
        }
        prefix = context.config["prefix"] as? String ?: ""
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event is TetherEvent.Message && event.port.name == "in") logging.log(level, prefix + event.value)
    }
}
