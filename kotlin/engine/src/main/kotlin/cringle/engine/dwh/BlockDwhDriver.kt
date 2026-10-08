// SPDX-License-Identifier: Apache-2.0

package cringle.engine.dwh

import cringle.contract.BuiltinDriverTypes
import cringle.contract.DriverType
import cringle.contract.DwhDriver
import cringle.contract.DwhEntry
import cringle.engine.tether.fromJson
import cringle.engine.tether.toJson
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The [DwhDriver] of one block: it writes to and reads from the partition `(fabric, BLOCK, block)` of the engine's [Dwh] (#192).
 * A record is `{"key": <key>, "value": <value>}` with the timestamp and tags of the entry.
 */
internal class BlockDwhDriver(private val dwh: Dwh, fabric: String, block: String) : DwhDriver {
    override val type: DriverType = BuiltinDriverTypes.DWH

    private val partition = DwhPartition(fabric, DwhKind.BLOCK, block)

    override suspend fun write(entry: DwhEntry) {
        val value = try {
            toJson(entry.value)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("the value of '${entry.key}' is not JSON-like data: ${e.message}", e)
        }
        val payload = JsonObject(mapOf("key" to JsonPrimitive(entry.key), "value" to value))
        withContext(Dispatchers.IO) { dwh.append(partition, DwhRecord(entry.timestamp, payload, entry.tags)) }
    }

    override suspend fun read(since: Instant?, until: Instant?, limit: Int): List<DwhEntry> =
        withContext(Dispatchers.IO) { dwh.query(partition, since, until, limit) }.map {
            val o = it.payload as? JsonObject
            DwhEntry(o?.get("key")?.jsonPrimitive?.contentOrNull?.takeIf { k -> k.isNotBlank() } ?: "record", o?.get("value")?.let(::fromJson) ?: "", it.timestamp, it.tags)
        }
}
