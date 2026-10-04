// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.contract.BlockDefinition
import cringle.contract.BlockId
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import cringle.engine.fabric.FabricException
import cringle.packaging.Backoff
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.DeliveryPolicy
import cringle.packaging.Endpoint
import cringle.packaging.RetryConfig
import cringle.packaging.TetherDef
import cringle.schema.SchemaRegistry
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PerTetherConfigTest {
    private val string = SchemaRef("cringle.std", "String")
    private val types = TetherType.entries.toSet()
    private val src = BlockDefinition(
        "src",
        emptyList(),
        listOf(
            PortDefinition("out1", PortDirection.OUT, types, string),
            PortDefinition("out2", PortDirection.OUT, types, string),
        ),
        emptyList(),
    )
    private val dst = BlockDefinition(
        "dst",
        emptyList(),
        listOf(
            PortDefinition("in1", PortDirection.IN, types, string),
            PortDefinition("in2", PortDirection.IN, types, string),
        ),
        emptyList(),
    )

    private fun out(n: TetherNetwork, port: String) = n.tether(BlockId("s"), src.ports.first { it.name == port }, null)

    private fun backoffNetwork(
        retry: RetryConfig,
        attempts: AtomicInteger,
        failures: AtomicInteger,
        dispatcher: CoroutineDispatcher,
    ): TetherNetwork {
        val blueprint = Blueprint(
            "bp",
            listOf(BlueprintBlock("s", "p/src"), BlueprintBlock("d", "p/dst")),
            listOf(TetherDef(TetherType.MESSAGE, Endpoint("s", "out1"), Endpoint("d", "in1"), DeliveryPolicy.BUFFER, retry = retry)),
        )
        return TetherNetwork.create(blueprint, mapOf("s" to src, "d" to dst), TetherConfig(SchemaRegistry(), dispatcher = dispatcher)) { _, _ -> failures.incrementAndGet() }
    }

    @Test
    fun perTetherBufferCapacityBlocksAtItsOwnCapacity() {
        val blueprint = Blueprint(
            "bp",
            listOf(BlueprintBlock("s", "p/src"), BlueprintBlock("d", "p/dst")),
            listOf(
                TetherDef(TetherType.MESSAGE, Endpoint("s", "out1"), Endpoint("d", "in1"), DeliveryPolicy.BUFFER, bufferCapacity = 2),
                TetherDef(TetherType.MESSAGE, Endpoint("s", "out2"), Endpoint("d", "in2"), DeliveryPolicy.BUFFER, bufferCapacity = 8),
            ),
        )
        val network = TetherNetwork.create(
            blueprint,
            mapOf("s" to src, "d" to dst),
            TetherConfig(SchemaRegistry(), bufferCapacity = 64),
        ) { _, _ -> }
        network.use { n ->
            runBlocking {
                val running = java.util.concurrent.atomic.AtomicBoolean(false)
                n.open { _, _ -> if (!running.get()) throw FabricException("down") }
                var sent1 = 0
                var sent2 = 0
                val producer1 = async(start = CoroutineStart.DEFAULT) { repeat(20) { out(n, "out1").send("m$it"); sent1++ } }
                val producer2 = async(start = CoroutineStart.DEFAULT) { repeat(20) { out(n, "out2").send("m$it"); sent2++ } }
                delay(500)
                // the pump holds one envelope in flight while it retries, so a tether of capacity n accepts n + 1
                assertEquals(3, sent1)
                assertEquals(9, sent2)
                running.set(true)
                withTimeout(10.seconds) {
                    producer1.await()
                    producer2.await()
                }
            }
        }
    }

    @Test
    fun perTetherRequestTimeoutIsUsed() {
        val blueprint = Blueprint(
            "bp",
            listOf(BlueprintBlock("s", "p/src"), BlueprintBlock("d", "p/dst")),
            listOf(
                TetherDef(TetherType.REQUEST_RESPONSE, Endpoint("s", "out1"), Endpoint("d", "in1"), DeliveryPolicy.BUFFER, requestTimeout = Duration.ofMillis(100)),
                TetherDef(TetherType.REQUEST_RESPONSE, Endpoint("s", "out2"), Endpoint("d", "in2"), DeliveryPolicy.BUFFER),
            ),
        )
        val network = TetherNetwork.create(
            blueprint,
            mapOf("s" to src, "d" to dst),
            TetherConfig(SchemaRegistry(), requestTimeout = Duration.ofSeconds(5)),
        ) { _, _ -> }
        network.use { n ->
            runBlocking {
                n.open { _, _ -> }
                assertThrows<TetherTimeoutException> { out(n, "out1").request("q") }
                val answer = async { runCatching { out(n, "out2").request("q") } }
                delay(500)
                assertTrue(!answer.isCompleted)
                n.close()
                answer.cancel()
            }
        }
    }

    @Test
    fun exhaustedRequestFailsWithTetherDeliveryException() {
        val blueprint = Blueprint(
            "bp",
            listOf(BlueprintBlock("s", "p/src"), BlueprintBlock("d", "p/dst")),
            listOf(
                TetherDef(
                    TetherType.REQUEST_RESPONSE,
                    Endpoint("s", "out1"),
                    Endpoint("d", "in1"),
                    DeliveryPolicy.BUFFER,
                    retry = RetryConfig(maxAttempts = 2, backoffMs = 10),
                ),
            ),
        )
        val network = TetherNetwork.create(
            blueprint,
            mapOf("s" to src, "d" to dst),
            TetherConfig(SchemaRegistry()),
        ) { _, _ -> }
        network.use { n ->
            runBlocking {
                n.open { _, _ -> throw FabricException("down") }
                val e = assertThrows<TetherDeliveryException> { out(n, "out1").request("q") }
                assertTrue(e.message!!.contains("after 2 attempts"), e.message)
            }
        }
    }

    @Test
    fun nonFabricExceptionDropsTheMessageWithoutRetry() {
        val blueprint = Blueprint(
            "bp",
            listOf(BlueprintBlock("s", "p/src"), BlueprintBlock("d", "p/dst")),
            listOf(
                TetherDef(TetherType.MESSAGE, Endpoint("s", "out1"), Endpoint("d", "in1"), DeliveryPolicy.BUFFER),
            ),
        )
        var attempts = AtomicInteger()
        var failures = AtomicInteger()
        val network = TetherNetwork.create(
            blueprint,
            mapOf("s" to src, "d" to dst),
            TetherConfig(SchemaRegistry()),
        ) { _, _ -> failures.incrementAndGet() }
        network.use { n ->
            runBlocking {
                n.open { _, _ ->
                    attempts.incrementAndGet()
                    throw IllegalStateException("boom")
                }
                out(n, "out1").send("m")
                withTimeout(10.seconds) { while (failures.get() == 0) delay(10) }
                delay(200)
                assertEquals(1, attempts.get())
                assertEquals(1, failures.get())
            }
        }
    }

    @Test
    fun retryBackoffIsObservedOnVirtualTime() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        suspend fun run(retry: RetryConfig): Pair<Int, Long> {
            val attempts = AtomicInteger()
            val failures = AtomicInteger()
            val n = backoffNetwork(retry, attempts, failures, dispatcher)
            val start = testScheduler.currentTime
            n.open { _, _ -> attempts.incrementAndGet(); throw FabricException("down") }
            n.tether(BlockId("s"), src.ports.first { it.name == "out1" }, null).send("m")
            advanceUntilIdle()
            n.close()
            return attempts.get() to (testScheduler.currentTime - start)
        }
        assertEquals(3 to 200L, run(RetryConfig(maxAttempts = 3, backoffMs = 100, backoff = Backoff.FIXED)))
        assertEquals(3 to 300L, run(RetryConfig(maxAttempts = 3, backoffMs = 100, backoff = Backoff.EXPONENTIAL, maxBackoffMs = 1000)))
        assertEquals(4 to 400L, run(RetryConfig(maxAttempts = 4, backoffMs = 100, backoff = Backoff.EXPONENTIAL, maxBackoffMs = 150)))
    }
}
