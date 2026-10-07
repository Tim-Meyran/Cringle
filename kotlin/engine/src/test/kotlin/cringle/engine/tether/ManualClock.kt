// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/** A [TetherClock] that only moves when a test says so: waits end in [advance], and every wait is reported in [requested]. */
class ManualClock : TetherClock {
    private val lock = Any()
    private var now = 0L
    private val waiters = ArrayList<Pair<Long, CompletableDeferred<Unit>>>()

    /** The length of every wait that was started, in order. */
    val requested = Channel<Long>(Channel.UNLIMITED)

    override fun nowMillis(): Long = synchronized(lock) { now }

    override suspend fun delay(millis: Long) {
        val done = CompletableDeferred<Unit>()
        synchronized(lock) { waiters += (now + millis) to done }
        requested.trySend(millis)
        done.await()
    }

    /** Moves the time forward by [millis] and ends the waits that are due. */
    fun advance(millis: Long) {
        val due = synchronized(lock) {
            now += millis
            val ready = waiters.filter { it.first <= now }
            waiters.removeAll(ready.toSet())
            ready
        }
        due.forEach { it.second.complete(Unit) }
    }
}
