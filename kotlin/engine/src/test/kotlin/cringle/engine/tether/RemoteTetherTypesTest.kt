// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.contract.TetherEvent
import cringle.contract.TetherStream
import cringle.contract.TetherType
import cringle.wire.Response
import cringle.wire.TetherMode
import cringle.wire.WireFrame
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `REQUEST_RESPONSE`, `STREAM` and `BYTE_STREAM` tethers between engines (#147): the semantics of the local tethers over
 * the wire, between two real engines with a fabric on each.
 */
class RemoteTetherTypesTest : RemoteTetherTestBase() {
    private val all = setOf(TetherType.MESSAGE, TetherType.REQUEST_RESPONSE, TetherType.STREAM, TetherType.BYTE_STREAM)

    private class Pair(val sender: Node, val receiver: Node)

    /** A receiving fabric `fb` on engine b and a sending fabric `fa` on engine a, both started. */
    private fun connect(
        type: TetherType,
        capacity: Int = 64,
        requestTimeout: Duration = Duration.ofSeconds(30),
        handler: suspend (TetherEvent) -> Unit,
    ): Pair {
        val a = engine("a")
        val b = engine("b")
        val receiver = Node(b, "fb", receiverDef(string, all), receiverTether(a, type = type), capacity, requestTimeout, handler = handler)
        receiver.start()
        val sender = Node(a, "fa", senderDef(string, all), senderTether(b, type = type), capacity, requestTimeout)
        sender.start()
        return Pair(sender, receiver)
    }

    @Test
    fun aRequestGetsTheResponseOfTheReceiver(): Unit = runBlocking {
        val p = connect(TetherType.REQUEST_RESPONSE) { event -> (event as TetherEvent.Request).respond("pong:" + event.value) }
        assertEquals("pong:ping", withTimeout(30.seconds) { p.sender.out().request("ping") })
        // many requests at once are matched to their responses by correlation id
        val answers = (0 until 50).map { i -> async(Dispatchers.Default) { p.sender.out().request("r$i") } }
        assertEquals((0 until 50).map { "pong:r$it" }, withTimeout(30.seconds) { answers.map { it.await() } })
    }

    @Test
    fun aResponseThatViolatesTheSchemaIsATetherValidationException(): Unit = runBlocking {
        val a = engine("a")
        val b = engine("b")
        // a receiver that answers with an Int where the port wants a String, as an engine with another schema would
        val registration = b.remoteTethers.portsFor("fb").register(
            listOf(
                RemoteReceiver("t", "d", "in", null, a.identity.publicKeyFingerprint, TetherMode.TYPED) { session ->
                    object : RemoteInbound {
                        override suspend fun onFrame(frame: WireFrame) {
                            session.reply(Response(frame.correlationOrStreamId, "cringle.std", JsonPrimitive(42)))
                        }

                        override fun onEnded(cause: Throwable?) {}
                    }
                },
            ),
        )
        try {
            val sender = Node(a, "fa", senderDef(string, all), senderTether(b, type = TetherType.REQUEST_RESPONSE))
            sender.start()
            val failure = assertThrows<TetherValidationException> { runBlocking { withTimeout(30.seconds) { sender.out().request("ask") } } }
            assertTrue(failure.message!!.contains("response violates schema"), failure.message)
        } finally {
            registration.close()
        }
    }

    @Test
    fun aRequestWithoutResponseEndsWithATimeout(): Unit = runBlocking {
        val p = connect(TetherType.REQUEST_RESPONSE, requestTimeout = Duration.ofMillis(300)) { /* never answers */ }
        val failure = assertThrows<TetherTimeoutException> { runBlocking { p.sender.out().request("ask") } }
        assertTrue(failure.message!!.contains("no response within"), failure.message)
    }

    @Test
    fun aStreamCarriesAThousandValuesInEachDirectionInOrder(): Unit = runBlocking {
        val opened = CompletableDeferred<TetherStream>()
        val p = connect(TetherType.STREAM) { event -> opened.complete((event as TetherEvent.StreamOpened).stream) }
        val local = p.sender.out().openStream()
        val remote = withTimeout(30.seconds) { opened.await() }
        val expected = (0 until 1000).map { "v$it" }
        val atRemote = async(Dispatchers.Default) { remote.incoming.take(1000).toList() }
        val atLocal = async(Dispatchers.Default) { local.incoming.take(1000).toList() }
        val sending = launch(Dispatchers.Default) { expected.forEach { local.send(it) } }
        val answering = launch(Dispatchers.Default) { expected.forEach { remote.send(it) } }
        assertEquals(expected, withTimeout(60.seconds) { atRemote.await() })
        assertEquals(expected, withTimeout(60.seconds) { atLocal.await() })
        sending.join()
        answering.join()
    }

    @org.junit.jupiter.api.RepeatedTest(20)
    fun closingEitherSideClosesBoth(): Unit = runBlocking {
        val opened = CompletableDeferred<TetherStream>()
        val p = connect(TetherType.STREAM) { event -> opened.complete((event as TetherEvent.StreamOpened).stream) }
        val local = p.sender.out().openStream()
        val remote = withTimeout(30.seconds) { opened.await() }
        local.send("last")
        local.close()
        // the item sent before the close arrives, then the remote side sees the end of the stream
        assertEquals(listOf<Any>("last"), withTimeout(30.seconds) { remote.incoming.toList() })
        assertEquals(emptyList<Any>(), withTimeout(30.seconds) { local.incoming.toList() })
        // the remote side cannot write any more
        assertNotNull(runCatching { remote.send("late") }.exceptionOrNull())
    }

    @Test
    fun aByteStreamMovesAMebibyteUnchanged(): Unit = runBlocking {
        val opened = CompletableDeferred<cringle.contract.TetherByteStream>()
        val p = connect(TetherType.BYTE_STREAM) { event -> opened.complete((event as TetherEvent.ByteStreamOpened).stream) }
        val local = p.sender.out().openByteStream()
        val remote = withTimeout(30.seconds) { opened.await() }
        val data = Random(7).nextBytes(1024 * 1024)
        var total = 0
        val chunks = async(Dispatchers.Default) {
            remote.incoming.transformWhile { chunk ->
                total += chunk.size
                emit(chunk)
                total < data.size
            }.toList()
        }
        for (offset in data.indices step 300_000) local.write(data.copyOfRange(offset, minOf(data.size, offset + 300_000)))
        val received = withTimeout(60.seconds) { chunks.await() }
        assertTrue(data.contentEquals(received.fold(ByteArray(0)) { all, chunk -> all + chunk }), "the bytes arrive unchanged and in order")
    }

    @Test
    fun stoppingTheReceivingFabricFailsAWaitingRequestAtOnce(): Unit = runBlocking {
        val asked = CompletableDeferred<Unit>()
        val p = connect(TetherType.REQUEST_RESPONSE) { asked.complete(Unit) /* never answers */ }
        val waiting = async(Dispatchers.Default) { runCatching { p.sender.out().request("ask") }.exceptionOrNull() }
        withTimeout(30.seconds) { asked.await() }
        p.receiver.stop()
        val failure = withTimeout(30.seconds) { waiting.await() }
        assertTrue(failure is TetherDeliveryException && failure.message!!.contains("stopping"), failure.toString())
    }

    @Test
    fun stoppingTheReceivingFabricFailsAWaitingStreamSenderAtOnce(): Unit = runBlocking {
        val opened = CompletableDeferred<TetherStream>()
        val p = connect(TetherType.STREAM, capacity = 1) { event -> opened.complete((event as TetherEvent.StreamOpened).stream) }
        val local = p.sender.out().openStream()
        withTimeout(30.seconds) { opened.await() }
        val big = "x".repeat(512 * 1024)
        val first = CompletableDeferred<Unit>()
        // nobody reads on the other side: the buffers fill up and the sender waits
        val sending = async(Dispatchers.Default) {
            try {
                repeat(400) {
                    local.send(big)
                    first.complete(Unit)
                }
                null
            } catch (e: Throwable) {
                e
            }
        }
        withTimeout(30.seconds) { first.await() }
        p.receiver.stop()
        val failure = withTimeout(30.seconds) { sending.await() }
        assertTrue(failure is TetherDeliveryException && failure.message!!.contains("stopping"), failure.toString())
    }

    @Test
    fun stoppingTheSendingFabricEndsTheStreamOnBothSides(): Unit = runBlocking {
        val opened = CompletableDeferred<TetherStream>()
        val p = connect(TetherType.STREAM) { event -> opened.complete((event as TetherEvent.StreamOpened).stream) }
        val local = p.sender.out().openStream()
        val remote = withTimeout(30.seconds) { opened.await() }
        val reading = async(Dispatchers.Default) { runCatching { remote.incoming.toList() }.exceptionOrNull() }
        p.sender.stop()
        assertTrue(runCatching { runBlocking { withTimeout(30.seconds) { local.incoming.toList() } } }.exceptionOrNull() is TetherDeliveryException)
        assertTrue(withTimeout(30.seconds) { reading.await() } is TetherDeliveryException)
    }

    @Test
    fun aSenderCannotRunAheadOfAReceiverThatDoesNotRead(): Unit = runBlocking {
        val opened = CompletableDeferred<TetherStream>()
        val p = connect(TetherType.STREAM, capacity = 4) { event -> opened.complete((event as TetherEvent.StreamOpened).stream) }
        val local = p.sender.out().openStream()
        val remote = withTimeout(30.seconds) { opened.await() }
        val big = "y".repeat(256 * 1024)
        val sent = AtomicInteger()
        val total = 200
        val sending = launch(Dispatchers.Default) {
            repeat(total) { i ->
                local.send(big + i)
                sent.incrementAndGet()
            }
        }
        // 50 MiB cannot be in flight: while nobody reads, the sender suspends after a bounded number of values
        val received = remote.incoming.take(1).toList()
        assertEquals(1, received.size)
        assertTrue(sent.get() < total, "the sender ran ahead of a receiver that did not read: ${sent.get()} of $total")
        // reading goes on, the sender goes on: all values arrive in order
        val rest = withTimeout(60.seconds) { remote.incoming.take(total - 1).toList() }
        assertEquals((1 until total).map { big + it }, rest.map { it as String })
        sending.join()
        assertEquals(total, sent.get())
    }
}
