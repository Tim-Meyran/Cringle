// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.BlockDefinition
import cringle.contract.BlockProvider
import cringle.contract.DriverSet
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.Tether
import cringle.contract.TetherEvent
import cringle.contract.TetherType
import cringle.engine.Engine
import cringle.engine.EngineArgs
import cringle.engine.fabric.BlockResolver
import cringle.engine.fabric.DriverFactory
import cringle.engine.fabric.FabricException
import cringle.engine.fabric.FabricLogger
import cringle.engine.fabric.FabricPaths
import cringle.engine.fabric.FabricRuntime
import cringle.engine.fabric.FabricSpec
import cringle.engine.fabric.PluginTrust
import cringle.engine.fabric.ResolvedBlock
import cringle.engine.fabric.WatchdogConfig
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.DeliveryPolicy
import cringle.packaging.Endpoint
import cringle.packaging.RemoteEndpoint
import cringle.packaging.TetherDef
import cringle.schema.SchemaRegistry
import cringle.wire.Message
import cringle.wire.TetherMode
import cringle.wire.WireFrame
import kotlinx.serialization.json.JsonPrimitive
import cringle.testkit.TestDriverSet
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * Tethers between engines (#146): two engines with their own generated identity, one fabric on each, and a `MESSAGE`
 * tether between them over mutual TLS. Both fabrics name each other, as the blueprints of a real deployment do.
 */
@Tag("integration")
class RemoteTetherTest : RemoteTetherTestBase() {
    @Test
    fun hundredMessagesArriveInOrderAndEqual(): Unit = runBlocking {
        val a = engine("a")
        val b = engine("b")
        val received = CopyOnWriteArrayList<Any>()
        val all = CompletableDeferred<Unit>()
        val receiver = Node(b, "fb", receiverDef(), receiverTether(a)) { event ->
            received += (event as TetherEvent.Message).value
            if (received.size == 100) all.complete(Unit)
        }
        receiver.start()
        val sender = Node(a, "fa", senderDef(), senderTether(b))
        sender.start()
        for (i in 0 until 100) sender.out().send("message-$i")
        withTimeout(30.seconds) { all.await() }
        assertEquals((0 until 100).map { "message-$it" }, received.toList())
    }

    @Test
    fun aSenderThatTheReceivingFabricDoesNotAllowIsRefusedAtTheHandshake() {
        val a = engine("a")
        val b = engine("b")
        val c = engine("c")
        Node(b, "fb", receiverDef(), receiverTether(a)).start()
        // c is not the engine that fb names as its sender
        val intruder = Node(c, "fa", senderDef(), senderTether(b))
        val failure = assertThrows<cringle.engine.fabric.FabricException> { intruder.start() }
        val text = generateSequence<Throwable>(failure) { it.cause }.joinToString(" / ") { it.message.orEmpty() }
        assertTrue("127.0.0.1:${b.tetherPort}" in text && b.identity.publicKeyFingerprint in text, text)
        assertFalse("BEGIN CERTIFICATE" in text, "no certificate material in the message")
        // the handshake failed: the engine of the receiver never saw a call (no ERROR answer, so no "refused (")
        assertFalse("refused (" in text, text)
    }

    @Test
    fun aCallThatNamesAnUnknownBlockOrPortIsRefusedLikeAnUnknownCaller() {
        val a = engine("a")
        val b = engine("b")
        Node(b, "fb", receiverDef(), receiverTether(a)).start()
        for (target in listOf(Triple("fb", "x", "in"), Triple("fb", "d", "nope"), Triple("nope", "d", "in"))) {
            val sender = Node(a, "fa-${target.first}${target.second}${target.third}", senderDef(), senderTether(b, target.first, target.second, target.third))
            val failure = assertThrows<cringle.engine.fabric.FabricException> { sender.start() }
            val text = generateSequence<Throwable>(failure) { it.cause }.joinToString(" / ") { it.message.orEmpty() }
            assertTrue("refused (unknown-target)" in text, text)
        }
    }

    @Test
    fun aSchemaViolationOnTheWireIsAnErrorFrameAndTheSenderLearnsIt(): Unit = runBlocking {
        val a = engine("a")
        val b = engine("b")
        val received = CopyOnWriteArrayList<Any>()
        // the receiving port wants an Int, the sending port sends a String: only the receiver can see it
        Node(b, "fb", receiverDef(int), receiverTether(a)) { received += (it as TetherEvent.Message).value }.start()
        val sender = Node(a, "fa", senderDef(string), senderTether(b))
        sender.start()
        sender.out().send("not a number")
        val failure = withTimeout(30.seconds) {
            while (sender.log.none { "violates schema" in it }) kotlinx.coroutines.yield()
            sender.log.first { "violates schema" in it }
        }
        assertTrue("tether" in failure, failure)
        assertTrue(received.isEmpty())
    }

    @Test
    fun theWireErrorCodesBecomeTheExceptionsOfALocalTether() {
        val a = engine("a")
        val b = engine("b")
        val errors = CopyOnWriteArrayList<Throwable>()
        val ports = b.remoteTethers.portsFor("fb")
        val registration = ports.register(
            listOf(
                RemoteReceiver("t", "d", "in", null, a.identity.publicKeyFingerprint, TetherMode.TYPED) {
                    object : RemoteInbound {
                        override suspend fun onFrame(frame: WireFrame) {
                            when (((frame as Message).value as JsonPrimitive).content) {
                                "invalid" -> throw TetherValidationException("bad value", listOf("$: wrong"))
                                "stopped" -> throw TetherDeliveryException("the fabric is stopping")
                            }
                        }

                        override fun onEnded(cause: Throwable?) {}
                    }
                },
            ),
        )
        try {
            runBlocking {
                val inbound = object : RemoteInbound {
                    override suspend fun onFrame(frame: WireFrame) {
                        errors += RemoteErrors.exception((frame as cringle.wire.Error).value)
                    }

                    override fun onEnded(cause: Throwable?) {}
                }
                val remote = RemoteEndpoint("127.0.0.1:${b.tetherPort}", b.identity.publicKeyFingerprint, "fb", "d", "in")
                val call = a.remoteTethers.portsFor("fa").connect(RemoteSender("t", remote, "cringle.std", TetherMode.TYPED, inbound))
                for (text in listOf("invalid", "stopped", "fine")) call.send(Message(0u, "cringle.std", JsonPrimitive(text)))
                withTimeout(30.seconds) { while (errors.size < 2) kotlinx.coroutines.yield() }
                call.close()
            }
            assertTrue(errors[0] is TetherValidationException && errors[0].message == "bad value" && (errors[0] as TetherValidationException).problems == listOf("$: wrong"), errors[0].toString())
            assertTrue(errors[1] is TetherDeliveryException && errors[1].message == "the fabric is stopping", errors[1].toString())
        } finally {
            registration.close()
        }
    }

    @Test
    fun aLostConnectionFailsAWaitingSenderAtOnce(): Unit = runBlocking {
        val a = engine("a")
        val b = engine("b")
        val stall = CompletableDeferred<Unit>()
        val taken = AtomicInteger()
        Node(b, "fb", receiverDef(), receiverTether(a), capacity = 1) { taken.incrementAndGet(); stall.await() }.start()
        val sender = Node(a, "fa", senderDef(), senderTether(b), capacity = 1)
        sender.start()
        val big = "x".repeat(512 * 1024)
        val first = CompletableDeferred<Unit>()
        // the receiver takes the first message and never answers: the buffers fill up, the transport stops, the sender waits
        val sending = async(kotlinx.coroutines.Dispatchers.Default) {
            try {
                repeat(400) {
                    sender.out().send(big)
                    first.complete(Unit)
                }
                null
            } catch (e: Throwable) {
                e
            }
        }
        withTimeout(30.seconds) { first.await() }
        b.stop()
        val failure = withTimeout(30.seconds) { sending.await() }
        assertNotNull(failure, "the sender has to fail, it cannot send 200 MB to a receiver that does not read")
        assertTrue(failure is TetherDeliveryException, failure.toString())
        // later sends fail at once, too
        assertThrows<TetherDeliveryException> { runBlocking { sender.out().send("later") } }
        stall.complete(Unit)
    }

    @Test
    fun bufferKeepsMessagesForAReceiverThatIsNotRunningYet(): Unit = runBlocking {
        val a = engine("a")
        val b = engine("b")
        val up = AtomicBoolean(false)
        val received = CopyOnWriteArrayList<Any>()
        val all = CompletableDeferred<Unit>()
        val receiver = Node(b, "fb", receiverDef(), receiverTether(a, DeliveryPolicy.BUFFER)) { event ->
            // a block that is not running yet refuses events, as the runtime of a fabric does
            if (!up.get()) throw FabricException("block d is not running")
            received += (event as TetherEvent.Message).value
            if (received.size == 3) all.complete(Unit)
        }
        receiver.start()
        val sender = Node(a, "fa", senderDef(), senderTether(b))
        sender.start()
        sender.out().send("one")
        sender.out().send("two")
        sender.out().send("three")
        up.set(true)
        withTimeout(30.seconds) { all.await() }
        assertEquals(listOf<Any>("one", "two", "three"), received.toList())
    }

    @Test
    fun theAllowedSenderIsRemovedWhenTheFabricIsRemoved() {
        val a = engine("a")
        val b = engine("b")
        val receiver = Node(b, "fb", receiverDef(), receiverTether(a))
        receiver.start()
        val first = Node(a, "fa", senderDef(), senderTether(b))
        first.start()
        // removing the fabric (close releases the registration) allows nobody
        receiver.fabric.close()
        val second = Node(a, "fa2", senderDef(), senderTether(b))
        val failure = assertThrows<FabricException> { second.start() }
        val text = generateSequence<Throwable>(failure) { it.cause }.joinToString(" / ") { it.message.orEmpty() }
        assertTrue("127.0.0.1:${b.tetherPort}" in text, text)
    }

    @Test
    fun theTetherPortFileHoldsThePortOfARunningEngine() {
        val a = engine("a")
        val file = dir.resolve("home-a/engines/a").resolve(Engine.TETHER_PORT_FILE)
        assertEquals(a.tetherPort, Files.readString(file).trim().toInt())
        a.stop()
        assertFalse(Files.exists(file))
    }
}
