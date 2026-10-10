// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.time

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.Tether
import cringle.stdblocks.long
import cringle.stdblocks.requireLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sends a tick every `intervalMs` on `tick`: the counter of the ticks, from 1. It starts after `initialDelayMs` (default 0) and stops after `count`
 * ticks if that is given.
 */
internal class TimerTrigger(private val dispatcher: CoroutineDispatcher = Dispatchers.Default) : Block {
    private var interval = 0L
    private var initialDelay = 0L
    private var count: Long? = null
    private lateinit var tick: Tether
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override suspend fun init(context: BlockContext) {
        interval = context.config.requireLong("intervalMs", 1)
        initialDelay = context.config.long("initialDelayMs") ?: 0L
        require(initialDelay >= 0) { "the configuration 'initialDelayMs' must not be negative" }
        count = context.config.long("count")?.also { require(it >= 1) { "the configuration 'count' must be at least 1" } }
        tick = context.ports.port("tick")
    }

    override suspend fun start() {
        val own = CoroutineScope(SupervisorJob() + dispatcher)
        scope = own
        job = own.launch {
            delay(initialDelay)
            var n = 0L
            while (isActive) {
                n++
                tick.send(n)
                val limit = count
                if (limit != null && n >= limit) break
                delay(interval)
            }
        }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
        scope?.coroutineContext?.get(Job)?.cancel()
        job = null
        scope = null
    }
}
