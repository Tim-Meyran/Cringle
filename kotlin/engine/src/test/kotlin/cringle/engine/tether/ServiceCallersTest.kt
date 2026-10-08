// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.contract.TetherEvent
import cringle.contract.TetherType
import cringle.engine.fabric.FabricException
import cringle.packaging.ProvidedService
import cringle.packaging.RemoteEndpoint
import cringle.packaging.TetherDef
import cringle.packaging.Endpoint
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A fabric accepts calls on its provided service ports from the engines it is told about (#177): service on engine b,
 * callers on a and c. The caller names the service port like any remote port, `svc/d/in`.
 */
@Tag("integration")
class ServiceCallersTest : RemoteTetherTestBase() {
    private val all = setOf(TetherType.MESSAGE, TetherType.REQUEST_RESPONSE)
    private val orders = ProvidedService("orders", "d", "in")

    private fun callerTether(service: cringle.engine.Engine, type: TetherType = TetherType.MESSAGE) = TetherDef(
        type, Endpoint("s", "out"), null,
        remote = RemoteEndpoint("127.0.0.1:${service.tetherPort}", service.identity.publicKeyFingerprint, "svc", "d", "in"),
    )

    @Test
    fun anAllowedEngineDeliversAnUnknownOneIsRefusedAtTheHandshake(): Unit = runBlocking {
        val a = engine("a")
        val b = engine("b")
        val c = engine("c")
        val received = CopyOnWriteArrayList<Any>()
        val arrived = CompletableDeferred<Unit>()
        Node(b, "svc", receiverDef(), null, provides = listOf(orders), serviceCallers = listOf(a.identity.publicKeyFingerprint)) { e ->
            received += (e as TetherEvent.Message).value
            arrived.complete(Unit)
        }.start()
        val caller = Node(a, "fa", senderDef(), callerTether(b))
        caller.start()
        caller.out().send("order-1")
        withTimeout(30.seconds) { arrived.await() }
        assertEquals(listOf<Any>("order-1"), received.toList())
        val intruder = Node(c, "fc", senderDef(), callerTether(b))
        val failure = assertThrows<FabricException> { intruder.start() }
        val text = generateSequence<Throwable>(failure) { it.cause }.joinToString(" / ") { it.message.orEmpty() }
        assertTrue("127.0.0.1:${b.tetherPort}" in text, text)
    }

    @Test
    fun twoCallersShareOnePort(): Unit = runBlocking {
        val a = engine("a")
        val b = engine("b")
        val c = engine("c")
        val received = CopyOnWriteArrayList<Any>()
        val both = CompletableDeferred<Unit>()
        Node(b, "svc", receiverDef(), null, provides = listOf(orders), serviceCallers = listOf(a.identity.publicKeyFingerprint, c.identity.publicKeyFingerprint)) { e ->
            received += (e as TetherEvent.Message).value
            if (received.size == 2) both.complete(Unit)
        }.start()
        val fa = Node(a, "fa", senderDef(), callerTether(b))
        val fc = Node(c, "fc", senderDef(), callerTether(b))
        fa.start()
        fc.start()
        fa.out().send("from-a")
        fc.out().send("from-c")
        withTimeout(30.seconds) { both.await() }
        assertEquals(setOf<Any>("from-a", "from-c"), received.toSet())
    }

    @Test
    fun aRemovedCallerIsRefusedAndItsOpenCallEnds(): Unit = runBlocking {
        val a = engine("a")
        val b = engine("b")
        val svc = Node(b, "svc", receiverDef(), null, provides = listOf(orders), serviceCallers = listOf(a.identity.publicKeyFingerprint))
        svc.start()
        val first = Node(a, "fa", senderDef(), callerTether(b))
        first.start()
        svc.fabric.setServiceCallers(emptyList())
        withTimeout(30.seconds) { while (first.log.none { "no longer" in it || "stopping" in it || "ended" in it || "lost" in it }) kotlinx.coroutines.yield() }
        val second = Node(a, "fa2", senderDef(), callerTether(b))
        assertThrows<FabricException> { second.start() }
        // adding the caller again lets it in
        svc.fabric.setServiceCallers(listOf(a.identity.publicKeyFingerprint))
        val third = Node(a, "fa3", senderDef(), callerTether(b))
        third.start()
    }

    @Test
    fun aRequestResponseServiceAnswersItsCaller(): Unit = runBlocking {
        val a = engine("a")
        val b = engine("b")
        val p = ProvidedService("echo", "d", "in", TetherType.REQUEST_RESPONSE)
        Node(b, "svc", receiverDef(string, all), null, provides = listOf(p), serviceCallers = listOf(a.identity.publicKeyFingerprint)) { e ->
            (e as TetherEvent.Request).respond("echo:" + e.value)
        }.start()
        val caller = Node(a, "fa", senderDef(string, all), callerTether(b, TetherType.REQUEST_RESPONSE))
        caller.start()
        assertEquals("echo:hi", withTimeout(30.seconds) { caller.out().request("hi") })
    }

    @Test
    fun aPortWithSeveralTypesNeedsAnExplicitServiceType() {
        val b = engine("b")
        val failure = assertThrows<FabricException> {
            Node(b, "svc", receiverDef(string, all), null, provides = listOf(orders)).start()
        }
        assertTrue("needs a tether type" in failure.message.orEmpty() || "needs a tether type" in (failure.cause?.message ?: ""), failure.toString())
    }
}
