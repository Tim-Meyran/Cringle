// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.contract.TetherEvent
import cringle.contract.TetherType
import cringle.engine.fabric.ServiceBinding
import cringle.engine.fabric.bindServices
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.Endpoint
import cringle.packaging.ProvidedService
import cringle.packaging.RemoteEndpoint
import cringle.packaging.TetherDef
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The consumer side of a service (#178): a service tether that a deploy request binds delivers to the service fabric (#177). */
@Tag("integration")
class ServiceBindingDeployTest : RemoteTetherTestBase() {
    private fun consumerBlueprint(): Blueprint =
        Blueprint("consumer", listOf(BlueprintBlock("s", "p/src")), listOf(TetherDef(TetherType.MESSAGE, Endpoint("s", "out"), null, service = "orders")))

    @Test
    fun bindingReplacesTheServiceByTheConcreteRemoteAndKeepsTheRest() {
        val fingerprint = "ab".repeat(32)
        val bound = consumerBlueprint().bindServices(listOf(ServiceBinding("orders", "svc", "d", "in", fingerprint), ServiceBinding("unused", "x", "y", "z", fingerprint)))
        val tether = bound.tethers.single()
        assertNull(tether.service)
        assertEquals(RemoteEndpoint(null, fingerprint, "svc", "d", "in"), tether.remote)
        assertEquals(Endpoint("s", "out"), tether.from)
        // without a binding nothing changes
        assertEquals(consumerBlueprint(), consumerBlueprint().bindServices(emptyList()))
        assertEquals(consumerBlueprint(), consumerBlueprint().bindServices(listOf(ServiceBinding("other", "x", "y", "z", fingerprint))))
    }

    @Test
    fun aBoundConsumerDeliversToTheServiceFabric(): Unit = runBlocking {
        val a = engine("a")
        val b = engine("b")
        val received = CopyOnWriteArrayList<Any>()
        val arrived = CompletableDeferred<Unit>()
        Node(b, "svc", receiverDef(), null, provides = listOf(ProvidedService("orders", "d", "in")), serviceCallers = listOf(a.identity.publicKeyFingerprint)) { e ->
            received += (e as TetherEvent.Message).value
            arrived.complete(Unit)
        }.start()
        // the engine of the service is found through its address; the binding carries no address, a fixed one is added here
        val bound = consumerBlueprint().bindServices(listOf(ServiceBinding("orders", "svc", "d", "in", b.identity.publicKeyFingerprint)))
        val tether = bound.tethers.single().let { it.copy(remote = it.remote!!.copy(address = "127.0.0.1:${b.tetherPort}")) }
        val caller = Node(a, "fa", senderDef(), tether)
        caller.start()
        caller.out().send("order-7")
        withTimeout(30.seconds) { arrived.await() }
        assertEquals(listOf<Any>("order-7"), received.toList())
    }
}
