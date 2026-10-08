// SPDX-License-Identifier: Apache-2.0

package cringle.engine.metrics

import cringle.contract.BlockDefinition
import cringle.contract.BlockId
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import cringle.engine.fabric.FabricException
import cringle.engine.tether.TetherConfig
import cringle.engine.tether.TetherNetwork
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.DeliveryPolicy
import cringle.packaging.Endpoint
import cringle.packaging.TetherDef
import cringle.schema.SchemaRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The numbers of the engine and of the tethers (#187). */
class MetricsTest {
    private val string = SchemaRef("cringle.std", "String")
    private val types = TetherType.entries.toSet()
    private val src = BlockDefinition("src", emptyList(), listOf(PortDefinition("out", PortDirection.OUT, types, string)), emptyList())
    private val dst = BlockDefinition("dst", emptyList(), listOf(PortDefinition("in", PortDirection.IN, types, string)), emptyList())

    @Test
    fun theEngineNumbersArePlausible() {
        val e = MetricsCollector.engine()
        assertTrue(e.heapUsedBytes > 0 && e.heapUsedBytes <= e.heapMaxBytes, e.toString())
        assertTrue(e.threadCount >= 1)
        assertTrue(e.processCpuLoad == -1.0 || e.processCpuLoad in 0.0..1.0, e.toString())
        assertTrue(MetricsCollector.cpuTimeOf(Thread.currentThread()) >= 0)
        assertEquals(-1, MetricsCollector.cpuTimeOf(null))
    }

    private fun network(failing: Boolean): TetherNetwork {
        val blueprint = Blueprint(
            "bp",
            listOf(BlueprintBlock("s", "p/src"), BlueprintBlock("d", "p/dst")),
            listOf(TetherDef(TetherType.MESSAGE, Endpoint("s", "out"), Endpoint("d", "in"), DeliveryPolicy.DROP)),
        )
        val network = TetherNetwork.create(blueprint, mapOf("s" to src, "d" to dst), TetherConfig(SchemaRegistry())) { _, _ -> }
        runBlocking { network.open { _, _ -> if (failing) throw FabricException("block 'd' is not running") } }
        return network
    }

    @Test
    fun aTetherCountsTheMessagesThatCrossIt() {
        network(failing = false).use { n ->
            runBlocking { repeat(7) { n.tether(BlockId("s"), src.ports.single(), null).send("m$it") } }
            runBlocking { withTimeout(10.seconds) { while (n.stats().single().messages < 7) delay(10) } }
            val s = n.stats().single()
            assertEquals(TetherType.MESSAGE, s.type)
            assertEquals(7, s.messages)
            assertEquals(0, s.errors)
        }
    }

    @Test
    fun failedDeliveriesAndRejectedValuesAreErrors() {
        network(failing = true).use { n ->
            val out = n.tether(BlockId("s"), src.ports.single(), null)
            runBlocking { repeat(3) { out.send("m$it") } }
            runBlocking { withTimeout(10.seconds) { while (n.stats().single().errors < 3) delay(10) } }
            val rejected = AtomicInteger()
            runCatching { runBlocking { out.send(42) } }.onFailure { rejected.incrementAndGet() }
            assertEquals(1, rejected.get(), "an Int is not a String")
            assertEquals(4, n.stats().single().errors)
        }
    }
}
