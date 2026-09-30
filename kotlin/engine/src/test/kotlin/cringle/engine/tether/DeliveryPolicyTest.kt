// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.contract.BlockDefinition
import cringle.contract.BlockId
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherEvent
import cringle.contract.TetherType
import cringle.engine.fabric.FabricException
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.DeliveryPolicy
import cringle.packaging.Endpoint
import cringle.packaging.TetherDef
import cringle.schema.SchemaRegistry
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeliveryPolicyTest {
    private val string = SchemaRef("cringle.std", "String")
    private val types = TetherType.entries.toSet()
    private val src = BlockDefinition("src", emptyList(), listOf(PortDefinition("out", PortDirection.OUT, types, string)), emptyList())
    private val dst = BlockDefinition("dst", emptyList(), listOf(PortDefinition("in", PortDirection.IN, types, string)), emptyList())

    /** A receiver that can be switched on and off, like a block that is being restarted. */
    private class Receiver {
        @Volatile var running = false
        val events = CopyOnWriteArrayList<TetherEvent>()
        val failures = CopyOnWriteArrayList<String>()
    }

    private fun network(
        type: TetherType,
        policy: DeliveryPolicy,
        receiver: Receiver,
        capacity: Int = 64,
        timeout: Duration = Duration.ofSeconds(5),
        onEvent: suspend (TetherEvent) -> Unit = { if (it is TetherEvent.Request) it.respond("re:" + it.value) },
    ): TetherNetwork {
        val blueprint = Blueprint(
            "bp",
            listOf(BlueprintBlock("s", "p/src"), BlueprintBlock("d", "p/dst")),
            listOf(TetherDef(type, Endpoint("s", "out"), Endpoint("d", "in"), policy)),
        )
        val network = TetherNetwork.create(
            blueprint,
            mapOf("s" to src, "d" to dst),
            TetherConfig(SchemaRegistry(), capacity, timeout),
        ) { _, e -> receiver.failures += e.message.orEmpty() }
        runBlocking { network.open { _, event ->
            if (!receiver.running) throw FabricException("block 'd' is not running")
            receiver.events += event
            onEvent(event)
        } }
        return network
    }

    private fun out(n: TetherNetwork) = n.tether(BlockId("s"), src.ports.single(), null)

    private fun awaitTrue(cond: () -> Boolean) = runBlocking { withTimeout(10.seconds) { while (!cond()) delay(10) } }

    @Test
    fun dropDropsAndLogsWhenTheReceiverIsNotRunning() {
        val r = Receiver()
        network(TetherType.MESSAGE, DeliveryPolicy.DROP, r).use { n ->
            runBlocking { out(n).send("lost") }
            awaitTrue { r.failures.isNotEmpty() }
            r.running = true
            runBlocking { out(n).send("kept") }
            awaitTrue { r.events.isNotEmpty() }
            assertEquals(listOf<Any>("kept"), r.events.map { (it as TetherEvent.Message).value })
            assertTrue(r.failures.single().contains("s.out -> d.in"))
        }
    }

    @Test
    fun bufferKeepsMessagesInOrderUntilTheReceiverRuns() {
        val r = Receiver()
        network(TetherType.MESSAGE, DeliveryPolicy.BUFFER, r).use { n ->
            runBlocking { repeat(5) { out(n).send("m$it") } }
            Thread.sleep(300)
            assertTrue(r.events.isEmpty() && r.failures.isEmpty(), "nothing is delivered or dropped while the receiver is down")
            r.running = true
            awaitTrue { r.events.size == 5 }
            assertEquals((0 until 5).map { "m$it" }, r.events.map { (it as TetherEvent.Message).value })
        }
    }

    @Test
    fun bufferedTetherSuspendsTheSenderWhenTheBufferIsFull() {
        val r = Receiver()
        network(TetherType.MESSAGE, DeliveryPolicy.BUFFER, r, capacity = 2).use { n ->
            runBlocking {
                var sent = 0
                val producer = async(start = CoroutineStart.DEFAULT) { repeat(10) { out(n).send("m$it"); sent++ } }
                delay(500)
                assertTrue(sent < 10, "sender must be suspended, sent=$sent")
                r.running = true
                withTimeout(10.seconds) { producer.await() }
                awaitTrue { r.events.size == 10 }
            }
        }
    }

    @Test
    fun bufferedRequestIsAnsweredAfterTheReceiverRuns() {
        val r = Receiver()
        network(TetherType.REQUEST_RESPONSE, DeliveryPolicy.BUFFER, r).use { n ->
            runBlocking {
                val answer = async { out(n).request("q") }
                delay(300)
                r.running = true
                assertEquals("re:q", withTimeout(10.seconds) { answer.await() })
            }
        }
    }

    @Test
    fun requestWithDropFailsAtOnceAndAbandonedBufferedRequestIsSkipped() {
        val r = Receiver()
        network(TetherType.REQUEST_RESPONSE, DeliveryPolicy.DROP, r).use { n ->
            val e = assertThrows<TetherDeliveryException> { runBlocking { out(n).request("q") } }
            assertTrue(e.message!!.contains("not running"), e.message)
        }
        val r2 = Receiver()
        val net = TetherNetwork.create(
            Blueprint("bp", listOf(BlueprintBlock("s", "p/src"), BlueprintBlock("d", "p/dst")), listOf(TetherDef(TetherType.REQUEST_RESPONSE, Endpoint("s", "out"), Endpoint("d", "in"), DeliveryPolicy.BUFFER))),
            mapOf("s" to src, "d" to dst),
            TetherConfig(SchemaRegistry(), 8, Duration.ofMillis(200)),
        )
        runBlocking {
            net.open { _, event ->
                if (!r2.running) throw FabricException("not running")
                r2.events += event
            }
        }
        net.use { n ->
            assertThrows<TetherTimeoutException> { runBlocking { out(n).request("late") } }
            r2.running = true
            Thread.sleep(300)
            assertTrue(r2.events.isEmpty(), "the abandoned request must not be delivered later")
        }
    }

    @Test
    fun stoppingFailsAWaitingSenderWithTheReasonInsteadOfCancellingIt() {
        val r = Receiver()
        network(TetherType.MESSAGE, DeliveryPolicy.BUFFER, r, capacity = 2).use { n ->
            runBlocking {
                var sent = 0
                val sender = async { runCatching { repeat(10) { out(n).send("m$it"); sent++ } } }
                delay(500)
                assertTrue(sent < 10, "the sender must wait for buffer space, sent=$sent")
                n.close()
                val e = withTimeout(2.seconds) { sender.await() }.exceptionOrNull()
                assertTrue(e is TetherDeliveryException, "the sender must see the stop as its own failure, got $e")
                assertTrue(e!!.message!!.contains("the fabric is stopping"), e.message)
                assertTrue(sent < 10, "the sender must not have sent everything before the stop")
            }
        }
    }

    @Test
    fun stoppingFailsAWaitingRequestAtOnceAndNotWithItsTimeout() {
        val r = Receiver()
        r.running = true
        val taken = CompletableDeferred<Unit>()
        val n = network(TetherType.REQUEST_RESPONSE, DeliveryPolicy.BUFFER, r, timeout = Duration.ofSeconds(30)) {
            taken.complete(Unit)
        }
        n.use {
            runBlocking {
                val answer = async { runCatching { out(n).request("q") } }
                withTimeout(10.seconds) { taken.await() }
                val start = System.nanoTime()
                n.close()
                val e = withTimeout(2.seconds) { answer.await() }.exceptionOrNull()
                val millis = (System.nanoTime() - start) / 1_000_000
                assertTrue(e is TetherDeliveryException, "got $e")
                assertTrue(e!!.message!!.contains("the fabric is stopping"), e.message)
                assertTrue(millis < 5_000, "the request must fail at once, not after its 30 s timeout, took $millis ms")
            }
        }
    }

    @Test
    fun closedStreamsLeaveTheNetwork() {
        val r = Receiver()
        r.running = true
        network(TetherType.STREAM, DeliveryPolicy.BUFFER, r) { if (it is TetherEvent.StreamOpened) it.stream.close() }.use { n ->
            runBlocking {
                repeat(10_000) { out(n).openStream().close() }
                withTimeout(30.seconds) { while (n.openStreamChannelCount > 0) delay(5) }
            }
            assertEquals(0, n.openStreamChannelCount)
        }
    }
}
