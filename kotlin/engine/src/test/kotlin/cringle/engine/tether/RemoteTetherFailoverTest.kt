// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.TrustStore
import cringle.common.v1.FabricId
import cringle.common.v1.FabricLifecycleState
import cringle.common.v1.FabricStateSummary
import cringle.contract.TetherEvent
import cringle.contract.TetherType
import cringle.engine.Engine
import cringle.packaging.DeliveryPolicy
import cringle.packaging.Endpoint
import cringle.packaging.RemoteEndpoint
import cringle.packaging.TetherDef
import cringle.router.RouterServer
import cringle.router.RouterTls
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Tethers between engines whose target is found through the registry of a router (#148): two engines and a router on
 * loopback, all over mutual TLS. The sender names the fabric of its target, not an address.
 */
class RemoteTetherFailoverTest : RemoteTetherTestBase() {
    private val routers = CopyOnWriteArrayList<RouterServer>()

    @AfterEach
    fun stopRouters() {
        routers.forEach { runCatching { it.stop() } }
    }

    private fun router(): RouterServer {
        val identity = Identity.loadOrCreate(dir.resolve("router"), ComponentKind.ROUTER.commonName("router"))
        val trust = TrustStore(dir.resolve("router-trust.json"))
        return RouterServer(dir.resolve("router-registry.json"), refreshInterval = Duration.ofHours(1), tls = RouterTls(identity, trust)).start().also { routers += it }
    }

    private val fast = RemoteTetherOptions(backoffStart = Duration.ofMillis(20), backoffCap = Duration.ofMillis(100))

    /**
     * An engine that registers at [router] and tells it that it runs [fabric]. The first start of an id enrolls it with a
     * one-time secret; a later start of the same id (the same home, so the same key) needs none.
     */
    private fun routedEngine(id: String, router: RouterServer, fabric: String? = null, enroll: Boolean = true): Engine {
        val home = dir.resolve("home-$id")
        val engineDir = home.resolve("engines").resolve(id)
        Identity.loadOrCreate(engineDir, ComponentKind.ENGINE.commonName(id))
        TrustStore(engineDir.resolve("trust.json")).add(
            TrustEntry(router.identity!!.publicKeyFingerprint, "router", TrustKind.ROUTER, address = "127.0.0.1:${router.port}"),
        )
        val env = if (enroll) {
            val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            router.enrollment!!.prepare(id, MessageDigest.getInstance("SHA-256").digest(secret))
            mapOf("CRINGLE_ENROLLMENT_SECRET" to secret.joinToString("") { "%02x".format(it) })
        } else {
            emptyMap()
        }
        val engine = engine(id, home, env, Duration.ofMillis(50), fast)
        if (fabric != null) {
            engine.fabricStates = {
                listOf(
                    FabricStateSummary.newBuilder()
                        .setFabricId(FabricId.newBuilder().setValue(fabric))
                        .setBlueprintName(fabric)
                        .setState(FabricLifecycleState.FABRIC_LIFECYCLE_STATE_RUNNING)
                        .build(),
                )
            }
        }
        engine.setRouterAddress("127.0.0.1:${router.port}")
        return engine
    }

    @Test
    fun twoFabricsRecoverAfterTheTargetEngineIsStoppedAndStartedElsewhere(): Unit = runBlocking {
        val router = router()
        val a = routedEngine("a", router)
        val b = routedEngine("b", router, fabric = "fb")
        val atFirst = CopyOnWriteArrayList<Any>()
        val gotFirst = CompletableDeferred<Unit>()
        val receiver = Node(b, "fb", receiverDef(), receiverTether(a, DeliveryPolicy.BUFFER)) { event ->
            atFirst += (event as TetherEvent.Message).value
            gotFirst.complete(Unit)
        }
        receiver.start()
        // the sender names the fabric of its target and the key of its engine, no address
        val tether = TetherDef(
            TetherType.MESSAGE, Endpoint("s", "out"), null, delivery = DeliveryPolicy.BUFFER,
            remote = RemoteEndpoint(null, b.identity.publicKeyFingerprint, "fb", "d", "in"),
        )
        val sender = Node(a, "fa", senderDef(), tether)
        sender.start()
        // the fabric starts although the target may not be in the registry yet; the message is kept until it is found
        sender.out().send("one")
        withTimeout(60.seconds) { gotFirst.await() }
        assertEquals(listOf<Any>("one"), atFirst.toList())

        // the engine of the target is stopped; the message is kept while the target is gone
        val oldPort = b.tetherPort
        receiver.fabric.close()
        b.stop()
        sender.out().send("two")

        // started elsewhere: the same engine (the same key) at another port, running the same fabric id
        val b2 = routedEngine("b", router, fabric = "fb", enroll = false)
        assertNotEquals(oldPort, b2.tetherPort, "the engine is at another address")
        val atSecond = CopyOnWriteArrayList<Any>()
        val gotSecond = CompletableDeferred<Unit>()
        Node(b2, "fb", receiverDef(), receiverTether(a, DeliveryPolicy.BUFFER)) { event ->
            atSecond += (event as TetherEvent.Message).value
            gotSecond.complete(Unit)
        }.start()
        withTimeout(60.seconds) { gotSecond.await() }
        assertEquals(listOf<Any>("two"), atSecond.toList())
        assertEquals(listOf<Any>("one"), atFirst.toList())
    }
}
