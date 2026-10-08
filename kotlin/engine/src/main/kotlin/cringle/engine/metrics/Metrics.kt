// SPDX-License-Identifier: Apache-2.0

package cringle.engine.metrics

import cringle.contract.TetherType
import java.lang.management.ManagementFactory

/** The numbers of one tether since its fabric was created (cumulative; a reader computes rates). */
public data class TetherStats(
    val id: String,
    val type: TetherType,
    /** Values that crossed the tether: messages, requests, responses, stream openings and stream items. */
    val messages: Long,
    /** Bytes that crossed a tether of bytes. */
    val bytes: Long,
    /** Validation and delivery failures. */
    val errors: Long,
)

/** The numbers of one block: how often it failed (crashes and failed starts, restarts included). */
public data class BlockStats(val id: String, val errors: Long)

/** The numbers of one fabric. [cpuTimeNanos] is the CPU time of its thread, `-1` if the JVM cannot tell. */
public data class FabricStats(
    val id: String,
    val cpuTimeNanos: Long,
    val errors: Long,
    val blocks: List<BlockStats>,
    val tethers: List<TetherStats>,
)

/** The numbers of the engine process. [processCpuLoad] is 0 to 1, `-1` if unknown. */
public data class EngineStats(
    val processCpuLoad: Double,
    val heapUsedBytes: Long,
    val heapMaxBytes: Long,
    val threadCount: Int,
)

/** Reads the numbers of the engine process from the JVM (#187). */
public object MetricsCollector {
    /** The state of the engine process now. */
    public fun engine(): EngineStats {
        val memory = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val os = ManagementFactory.getOperatingSystemMXBean()
        val load = (os as? com.sun.management.OperatingSystemMXBean)?.processCpuLoad?.takeIf { it >= 0.0 } ?: -1.0
        return EngineStats(load, memory.used, if (memory.max > 0) memory.max else memory.committed, ManagementFactory.getThreadMXBean().threadCount)
    }

    /** The CPU time of [thread] in nanoseconds, or `-1` if it is not known (thread gone, or not supported). */
    public fun cpuTimeOf(thread: Thread?): Long {
        thread ?: return -1
        val bean = ManagementFactory.getThreadMXBean()
        return if (bean.isThreadCpuTimeSupported) bean.getThreadCpuTime(thread.id) else -1
    }
}
