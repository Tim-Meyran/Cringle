// SPDX-License-Identifier: Apache-2.0

package cringle.engine.dwh

import cringle.contract.TetherType
import cringle.engine.tether.TetherInfo
import cringle.engine.tether.TetherObserver
import cringle.engine.tether.TrafficKind
import cringle.engine.tether.toJson
import cringle.packaging.RecordConfig
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Writes the values that cross the tethers of one fabric into the data warehouse (#193, Architecture 16.5). A tether is
 * recorded if its definition in the blueprint has a `record`, or if the fabric is in the recording mode ([setMode]) and the
 * tether is typed (messages, requests and responses, stream items; tethers of bytes are not recorded). The records go to the
 * partition `(fabric, TETHER, tether id)`, whose retention is taken from the `record` of the tether or from the default of the mode.
 *
 * Recording never slows a tether down: [observe] only puts the record into a bounded queue, a writer coroutine appends it. When the
 * queue is full the record is dropped, counted in [dropped], and [warn] is called once.
 */
public class TetherRecorder(
    private val dwh: Dwh,
    private val fabric: String,
    capacity: Int = 1024,
    private val warn: (String) -> Unit = {},
) : TetherObserver, AutoCloseable {
    private class Pending(val partition: DwhPartition, val record: DwhRecord, val retention: Retention?)

    private val queue = Channel<Pending>(capacity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configured = ConcurrentHashMap.newKeySet<String>()
    private val warned = AtomicBoolean(false)
    private val inFlight = AtomicLong()

    @Volatile private var mode: RecordConfig? = null

    @Volatile private var all = false

    /** How many records were dropped because the queue was full. */
    public val dropped: AtomicLong = AtomicLong()

    init {
        scope.launch {
            for (p in queue) {
                try {
                    p.retention?.let { dwh.setRetention(p.partition, it) }
                    dwh.append(p.partition, p.record)
                } catch (e: Exception) {
                    // a record that cannot be written is lost; the tether is not affected
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
    }

    /** Records every typed tether of the fabric with [default] as its retention ([all]), or only those with their own definition. */
    public fun setMode(all: Boolean, default: RecordConfig?) {
        this.all = all
        mode = if (all) default ?: RecordConfig() else null
        configured.clear()
    }

    override fun observe(tether: TetherInfo, kind: TrafficKind, payload: Any?) {
        if (kind != TrafficKind.MESSAGE && kind != TrafficKind.REQUEST && kind != TrafficKind.RESPONSE && kind != TrafficKind.STREAM_ITEM) return
        if (tether.type == TetherType.BYTE_STREAM || tether.type == TetherType.TCP || tether.type == TetherType.SERIAL) return
        val config = tether.record ?: (if (all) mode else null) ?: return
        val json = try {
            toJson(payload)
        } catch (e: IllegalArgumentException) {
            return
        }
        val partition = DwhPartition(fabric, DwhKind.TETHER, tether.id)
        val retention = if (configured.add(tether.id)) Retention(config.maxAge, config.maxBytes) else null
        inFlight.incrementAndGet()
        if (queue.trySend(Pending(partition, DwhRecord(Instant.now(), json, mapOf("kind" to kind.name.lowercase())), retention)).isFailure) {
            inFlight.decrementAndGet()
            if (retention != null) configured.remove(tether.id)
            dropped.incrementAndGet()
            if (warned.compareAndSet(false, true)) warn("recording of tether ${tether.id}: the writer is too slow, records are dropped")
        }
    }

    /** Waits until everything that was observed so far is written (for tests and for the shutdown of a fabric). */
    public suspend fun flush(timeoutMillis: Long = 10_000) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (inFlight.get() > 0 && System.nanoTime() < deadline) kotlinx.coroutines.delay(5)
    }

    override fun close() {
        queue.close()
        scope.cancel()
    }
}
