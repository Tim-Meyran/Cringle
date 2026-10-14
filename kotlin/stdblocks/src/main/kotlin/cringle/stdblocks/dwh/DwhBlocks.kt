// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.dwh

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.BuiltinDriverTypes
import cringle.contract.DwhDriver
import cringle.contract.DwhEntry
import cringle.contract.Tether
import cringle.contract.TetherEvent
import cringle.stdblocks.ANY
import cringle.stdblocks.Entry
import cringle.stdblocks.STRING
import cringle.stdblocks.entry
import cringle.stdblocks.inPort
import cringle.stdblocks.long
import cringle.stdblocks.outPort
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/**
 * Writes every value at `in` as an entry (`key` and `tags` from the configuration) of the own partition of the block in the data warehouse and sends it on `out`.
 * A block reads **its own partition only** (`docs/standard-blocks.md`), so a block that has to read back what it wrote is `dwh.store`: with [canRead] it also
 * answers every message at `read` with the entries as a list on `entries`, oldest first (objects `key`, `value`, `timestamp`, `tags`; `limit` and `lastMs` narrow it
 * down). A driver that cannot read answers on `error`. What `dwh.write` stores is read through the CLI and the WebUI (`cringle dwh query`).
 */
internal class DwhStore(private val dwh: DwhDriver, private val canRead: Boolean) : Block {
    private lateinit var key: String
    private var tags: Map<String, String> = emptyMap()
    private var limit = 100
    private var lastMs: Long? = null
    private lateinit var out: Tether
    private var entries: Tether? = null
    private var error: Tether? = null

    override suspend fun init(context: BlockContext) {
        key = (context.config["key"] as? String)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("the configuration 'key' is missing")
        tags = (context.config["tags"] as? Map<*, *>).orEmpty().entries.associate { (k, v) ->
            k.toString() to (v as? String ?: throw IllegalArgumentException("the tag '$k' is not a text"))
        }
        out = context.ports.port("out")
        if (canRead) {
            limit = context.config.long("limit")?.toInt() ?: 100
            require(limit >= 1) { "the configuration 'limit' must be at least 1" }
            lastMs = context.config.long("lastMs")?.also { require(it >= 1) { "the configuration 'lastMs' must be at least 1" } }
            entries = context.ports.port("entries")
            error = context.ports.port("error")
        }
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event !is TetherEvent.Message) return
        when (event.port.name) {
            "in" -> {
                dwh.write(DwhEntry(key, event.value, Instant.now(), tags))
                out.send(event.value)
            }
            "read" -> read()
        }
    }

    private suspend fun read() {
        val found = try {
            dwh.read(lastMs?.let { Instant.now().minusMillis(it) }, null, limit)
        } catch (e: UnsupportedOperationException) {
            error?.send(e.message ?: "the data warehouse cannot read")
            return
        }
        entries?.send(
            found.sortedBy { it.timestamp }.map { mapOf("key" to it.key, "value" to it.value, "timestamp" to TIMESTAMP.format(it.timestamp), "tags" to it.tags) },
        )
    }
}

internal val DWH_ENTRIES: List<Entry> = listOf(
    entry("dwh.write", listOf(inPort("in", ANY), outPort("out", ANY)), "DwhWriteConfig", listOf(BuiltinDriverTypes.DWH.id)) { DwhStore(it[DwhDriver::class], canRead = false) },
    entry(
        "dwh.store",
        listOf(inPort("in", ANY), inPort("read", ANY), outPort("out", ANY), outPort("entries", ANY), outPort("error", STRING)),
        "DwhStoreConfig",
        listOf(BuiltinDriverTypes.DWH.id),
    ) { DwhStore(it[DwhDriver::class], canRead = true) },
)
