// SPDX-License-Identifier: Apache-2.0

package cringle.engine.fabric

import cringle.engine.tether.TetherInfo
import cringle.engine.tether.TetherInterceptor
import cringle.engine.tether.TrafficKind
import cringle.engine.tether.toJson
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred

/**
 * Remote debugging of one fabric (#323, Architecture 16.4). A breakpoint is set on a tether (by the id the data warehouse also uses, for
 * example `c.out -> s.in`): every value that is about to be delivered on it is held back before the receiving block gets it. While values
 * are held, the receiver does not see them and the sender goes on until the bounded buffer of the tether is full, then it waits: the fabric is
 * paused at that tether and the buffering of the messages is the buffer of the tether (backpressure), nothing is dropped. A held value shows the
 * output of the sending block and the input of the receiving one. [resume] releases them, one at a time or all; removing the breakpoint
 * releases everything that it held.
 */
public class FabricDebugger : TetherInterceptor {
    /** A value that is held back at a breakpoint. [payload] is its JSON text, shortened to [MAX_PAYLOAD] characters. */
    public data class Held(val id: Long, val tether: String, val from: String, val to: String, val kind: TrafficKind, val payload: String, val since: Instant)

    /** What is set and what is held at one moment. */
    public data class State(val breakpoints: List<String>, val held: List<Held>)

    private class Gate(val held: Held) {
        val released = CompletableDeferred<Unit>()
    }

    private val breakpoints = ConcurrentHashMap.newKeySet<String>()
    private val gates = ConcurrentHashMap<Long, Gate>()
    private val ids = AtomicLong()

    @Volatile private var closed = false

    override suspend fun beforeDelivery(tether: TetherInfo, kind: TrafficKind, payload: Any?) {
        if (closed || tether.id !in breakpoints) return
        val gate = Gate(Held(ids.incrementAndGet(), tether.id, "${tether.from.block}.${tether.from.port}", "${tether.to.block}.${tether.to.port}", kind, describe(payload), Instant.now()))
        gates[gate.held.id] = gate
        try {
            gate.released.await()
        } finally {
            gates.remove(gate.held.id)
        }
    }

    /** Sets or removes the breakpoint on [tether]; removing it releases the values it held. */
    public fun setBreakpoint(tether: String, enabled: Boolean) {
        require(tether.isNotBlank()) { "the tether must not be empty" }
        if (enabled) {
            breakpoints += tether
        } else {
            breakpoints -= tether
            resume(tether, one = false)
        }
    }

    /** The breakpoints and the held values, oldest first. */
    public fun state(): State = State(breakpoints.sorted(), gates.values.map { it.held }.sortedBy { it.id })

    /**
     * Releases held values: those of [tether] (all tethers if `null`), the oldest only if [one], else all. Returns how many were released.
     * The breakpoints stay: the next value is held again, so `one` steps through the traffic.
     */
    public fun resume(tether: String? = null, one: Boolean = false): Int {
        val candidates = gates.values.filter { tether == null || it.held.tether == tether }.sortedBy { it.held.id }
        val chosen = if (one) candidates.take(1) else candidates
        chosen.forEach { it.released.complete(Unit) }
        return chosen.size
    }

    /** Releases everything and holds nothing from now on (the fabric is stopping). */
    public fun close() {
        closed = true
        breakpoints.clear()
        resume()
    }

    private fun describe(payload: Any?): String {
        val text = when (payload) {
            is ByteArray -> "${payload.size} bytes: " + payload.take(32).joinToString(" ") { "%02x".format(it) } + if (payload.size > 32) " ..." else ""
            else -> try {
                toJson(payload).toString()
            } catch (e: IllegalArgumentException) {
                payload.toString()
            }
        }
        return if (text.length > MAX_PAYLOAD) text.take(MAX_PAYLOAD) + "..." else text
    }

    public companion object {
        /** The longest payload text a held value carries. */
        public const val MAX_PAYLOAD: Int = 4096
    }
}
