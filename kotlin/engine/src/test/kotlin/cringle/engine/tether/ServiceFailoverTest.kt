// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.contract.TetherEvent
import cringle.contract.TetherType
import cringle.engine.Engine
import cringle.packaging.DeliveryPolicy
import cringle.packaging.Endpoint
import cringle.packaging.ProvidedService
import cringle.packaging.RemoteEndpoint
import cringle.packaging.TetherDef
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Several instances of a service (#173): the consumer on engine a calls the service on b, and on c when b is not there.
 * The instances are given by fixed addresses here, so no router is needed; the order is the order of preference.
 */
@Tag("integration")
class ServiceFailoverTest : RemoteTetherTestBase() {
    private val fast = RemoteTetherOptions(backoffStart = Duration.ofMillis(20), backoffCap = Duration.ofMillis(100), healthInterval = Duration.ofMillis(100))
    private val orders = ProvidedService("orders", "d", "in")

    private fun remote(instance: Engine, fabric: String, vararg more: Pair<Engine, String>) = RemoteEndpoint(
        "127.0.0.1:${instance.tetherPort}", instance.identity.publicKeyFingerprint, fabric, "d", "in", null,
        more.map { (e, f) -> RemoteEndpoint("127.0.0.1:${e.tetherPort}", e.identity.publicKeyFingerprint, f, "d", "in", null, emptyList(), "orders") },
        "orders",
    )

    private fun consumer(remote: RemoteEndpoint) = TetherDef(TetherType.MESSAGE, Endpoint("s", "out"), null, delivery = DeliveryPolicy.BUFFER, remote = remote)

    private class Instance(val received: CopyOnWriteArrayList<Any> = CopyOnWriteArrayList())

    private fun Instance.node(engine: Engine, fabric: String, caller: Engine): Node =
        Node(engine, fabric, receiverDef(), null, provides = listOf(orders), serviceCallers = listOf(caller.identity.publicKeyFingerprint)) { e ->
            received += (e as TetherEvent.Message).value
        }

    private var watched: Node? = null

    private suspend fun await(what: String, condition: () -> Boolean) {
        try {
            withTimeout(60.seconds) { while (!condition()) delay(20) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out: $what\n" + watched?.log?.takeLast(10)?.joinToString("\n"))
        }
    }

    @Test
    fun theConsumerMovesToTheNextInstanceAndBackWhenTheFirstReturns(): Unit = runBlocking {
        val a = engine("a", options = fast)
        val b = engine("b", options = fast)
        val c = engine("c", options = fast)
        val atB = Instance()
        val atC = Instance()
        val svcB = atB.node(b, "svcb", a).also { it.start() }
        atC.node(c, "svcc", a).start()
        val caller = Node(a, "fa", senderDef(), consumer(remote(b, "svcb", c to "svcc")))
        caller.start()
        watched = caller

        caller.out().send("m1")
        await("m1 at the first instance") { atB.received.contains("m1") }
        assertTrue(atC.received.isEmpty())

        // the first instance goes away: the consumer calls the second one (a message that was on the way to the first one
        // when it went is lost, as with any lost connection, so the test sends until one arrives)
        svcB.fabric.close()
        var j = 0
        await("a message at the second instance") {
            runBlocking { caller.out().send("m2-${j++}") }
            Thread.sleep(50)
            atC.received.any { it.toString().startsWith("m2-") }
        }

        // the first instance is back: new messages go there again
        atB.node(b, "svcb", a).start()
        var i = 0
        await("a message at the first instance again") {
            runBlocking { caller.out().send("m3-${i++}") }
            Thread.sleep(50)
            atB.received.any { it.toString().startsWith("m3-") }
        }
    }

    @Test
    fun aNewListOfInstancesSwitchesTheOpenTetherWithoutARedeploy(): Unit = runBlocking {
        val a = engine("a", options = fast)
        val b = engine("b", options = fast)
        val c = engine("c", options = fast)
        val atB = Instance()
        val atC = Instance()
        atB.node(b, "svcb", a).start()
        atC.node(c, "svcc", a).start()
        val caller = Node(a, "fa", senderDef(), consumer(remote(b, "svcb")))
        caller.start()
        watched = caller
        caller.out().send("one")
        await("one at b") { atB.received.contains("one") }

        // the binding now names only c
        caller.fabric.updateServiceBindings(mapOf("orders" to remote(c, "svcc")))
        var i = 0
        await("a message at c") {
            runBlocking { caller.out().send("two-${i++}") }
            Thread.sleep(50)
            atC.received.any { it.toString().startsWith("two-") }
        }
        assertEquals(listOf<Any>("one"), atB.received.toList().filter { it == "one" })
    }
}
