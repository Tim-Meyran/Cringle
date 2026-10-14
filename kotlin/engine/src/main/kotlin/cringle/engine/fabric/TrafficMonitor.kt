// SPDX-License-Identifier: Apache-2.0

package cringle.engine.fabric

import cringle.contract.TetherType
import cringle.engine.tether.TetherInfo
import cringle.engine.tether.TetherObserver
import cringle.engine.tether.TrafficKind
import java.util.concurrent.ConcurrentHashMap

/**
 * The recent traffic of the tethers of one fabric, for the monitor (#399): the last [capacity] values of every tether that lets them be seen. It only
 * records while somebody looks: [touch] (every read of the monitor) keeps the capture on for [LEASE_MILLIS], so a fabric nobody watches pays nothing.
 * Which tethers can be seen: messages, requests with their responses, streams and byte streams (as a dump of the first bytes); the values of a
 * TCP or a serial tether are a resource of the machine and are not copied.
 */
public class TrafficMonitor(private val capacity: Int = 100) : TetherObserver {
    /** One value that crossed a tether. [payload] is its JSON text (bytes: a hex dump), shortened to [PayloadText.MAX]. */
    public data class Record(val seq: Long, val timeMillis: Long, val kind: TrafficKind, val payload: String)

    private class Ring {
        val items = ArrayDeque<Record>()
        var seq = 0L
    }

    private val rings = ConcurrentHashMap<String, Ring>()

    @Volatile private var captureUntil = 0L

    /** Keeps the capture on for the next [LEASE_MILLIS]. */
    public fun touch(now: Long = System.currentTimeMillis()) {
        captureUntil = now + LEASE_MILLIS
    }

    /** True while somebody looks. */
    public fun capturing(now: Long = System.currentTimeMillis()): Boolean = now < captureUntil

    override fun observe(tether: TetherInfo, kind: TrafficKind, payload: Any?) {
        if (!capturing() || !canBeSeen(tether.type)) return
        val ring = rings.computeIfAbsent(tether.id) { Ring() }
        val text = PayloadText.of(payload)
        synchronized(ring) {
            ring.items.addLast(Record(++ring.seq, System.currentTimeMillis(), kind, text))
            while (ring.items.size > capacity) ring.items.removeFirst()
        }
    }

    /** The newest [limit] records of [tether], newest first. */
    public fun recent(tether: String, limit: Int = capacity): List<Record> {
        val ring = rings[tether] ?: return emptyList()
        return synchronized(ring) { ring.items.toList() }.asReversed().take(limit.coerceAtLeast(0))
    }

    public companion object {
        /** How long a read keeps the capture on. */
        public const val LEASE_MILLIS: Long = 60_000

        /** Whether the values of a tether of [type] are copied. */
        public fun canBeSeen(type: TetherType): Boolean = type != TetherType.TCP && type != TetherType.SERIAL
    }
}
