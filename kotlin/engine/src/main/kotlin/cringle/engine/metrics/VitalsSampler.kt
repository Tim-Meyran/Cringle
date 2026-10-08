// SPDX-License-Identifier: Apache-2.0

package cringle.engine.metrics

import cringle.common.v1.EngineMetrics
import java.time.Clock

/**
 * The few numbers an engine puts into its heartbeat (#190, Architecture 16.2): CPU, memory, fabric counts, errors, and the
 * tether throughput since the previous sample. [fabrics] gives the current numbers of the fabrics (no sampling thread of
 * its own), [running] the number of fabrics that run.
 */
public class VitalsSampler(
    private val fabrics: () -> List<FabricStats>,
    private val running: () -> Int,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var lastMessages = 0L
    private var lastAt: Long? = null

    /** The numbers now; the throughput is the messages since the previous call per second (0 for the first call). */
    @Synchronized
    public fun sample(): EngineMetrics {
        val engine = MetricsCollector.engine()
        val stats = fabrics()
        val messages = stats.sumOf { f -> f.tethers.sumOf { it.messages } }
        val now = clock.millis()
        val previous = lastAt
        val rate = if (previous == null || now <= previous) 0L else maxOf(0L, (messages - lastMessages) * 1000 / (now - previous))
        lastMessages = messages
        lastAt = now
        return EngineMetrics.newBuilder()
            .setCpuUsagePercent(if (engine.processCpuLoad < 0) -1.0 else engine.processCpuLoad * 100)
            .setMemoryUsedBytes(engine.heapUsedBytes)
            .setMemoryMaxBytes(engine.heapMaxBytes)
            .setTetherMessagesPerSec(rate)
            .setFabricCount(stats.size)
            .setRunningFabricCount(running())
            .setErrorCount(stats.sumOf { it.errors })
            .build()
    }
}
