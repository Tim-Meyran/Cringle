// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.common.TrustStore
import cringle.packaging.RemoteEndpoint
import cringle.wire.Message
import cringle.wire.TetherMode
import cringle.wire.WireFrame
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * Resolution, cache, supervision and reconnecting of the tethers between engines (#148). Time is a [ManualClock]: nothing
 * here sleeps, and every wait of the code under test ends when the test says so.
 */
class RemoteTetherResolutionTest {
    @TempDir
    lateinit var dir: Path

    private val drivers = CopyOnWriteArrayList<RemoteTetherDriver>()
    private val calls = CopyOnWriteArrayList<RemoteCall>()

    @AfterEach
    fun stopAll() {
        calls.forEach { runCatching { it.close() } }
        drivers.forEach { runCatching { it.close() } }
    }

    // ---- the cache ----

    private class CountingResolver(var address: String = "10.0.0.1:7000") : FabricResolver {
        val asked = AtomicInteger()
        var failure: String? = null

        override suspend fun resolve(fabricId: String): String {
            asked.incrementAndGet()
            failure?.let { throw FabricNotResolvedException(it) }
            return address
        }
    }

    @Test
    fun aSecondResolutionWithinTheTtlDoesNotAskTheRegistryAndOneAfterItDoes(): Unit = runBlocking {
        val clock = ManualClock()
        val registry = CountingResolver()
        val cache = CachingFabricResolver(registry, Duration.ofSeconds(30), clock)
        assertEquals("10.0.0.1:7000", cache.resolve("fb"))
        clock.advance(29_999)
        assertEquals("10.0.0.1:7000", cache.resolve("fb"))
        assertEquals(1, registry.asked.get())
        registry.address = "10.0.0.2:7000"
        clock.advance(1)
        assertEquals("10.0.0.2:7000", cache.resolve("fb"))
        assertEquals(2, registry.asked.get())
        // other fabrics have entries of their own
        cache.resolve("other")
        assertEquals(3, registry.asked.get())
    }

    @Test
    fun invalidatingDropsTheEntryAndAFailedResolutionIsNotKept(): Unit = runBlocking {
        val clock = ManualClock()
        val registry = CountingResolver()
        val cache = CachingFabricResolver(registry, Duration.ofSeconds(30), clock)
        cache.resolve("fb")
        cache.invalidate("fb")
        cache.resolve("fb")
        assertEquals(2, registry.asked.get())
        cache.invalidate("fb")
        registry.failure = "unknown"
        assertThrows<FabricNotResolvedException> { runBlocking { cache.resolve("fb") } }
        registry.failure = null
        assertEquals("10.0.0.1:7000", cache.resolve("fb"))
    }

    @Test
    fun theBackoffStartsAt250msDoublesAndStopsAt10s() {
        val options = RemoteTetherOptions()
        assertEquals(listOf(250L, 500L, 1000L, 2000L, 4000L, 8000L, 10_000L, 10_000L), (0..7).map { options.backoff(it) })
    }

    // ---- the health check ----

    @Test
    fun threeFailedChecksInARowMarkTheConnectionDownAndASuccessMarksItUp(): Unit = runBlocking {
        val clock = ManualClock()
        val results = ArrayDeque(listOf(true, false, false, true, false, false, false, false, true))
        val events = CopyOnWriteArrayList<String>()
        val probes = AtomicInteger()
        val job = launch(Dispatchers.Default) {
            superviseHealth(clock, Duration.ofSeconds(5), 3, probe = { probes.incrementAndGet(); results.removeFirst() }, onDown = { events += "down" }, onUp = { events += "up" })
        }
        repeat(9) {
            assertEquals(5000L, withTimeout(30.seconds) { clock.requested.receive() })
            clock.advance(5000)
        }
        // wait for the ninth check to be done: the loop asks for the next wait
        assertEquals(5000L, withTimeout(30.seconds) { clock.requested.receive() })
        // true,false,false,true: not down (reset by the success); false,false,false: down once; false: no second report; true: up
        assertEquals(listOf("down", "up"), events.toList())
        assertEquals(9, probes.get())
        job.cancel()
    }

    @Test
    fun theSupervisionStopsWhenItsCoroutineIsCancelled(): Unit = runBlocking {
        val clock = ManualClock()
        val probes = AtomicInteger()
        val job = launch(Dispatchers.Default) {
            superviseHealth(clock, Duration.ofSeconds(5), 3, probe = { probes.incrementAndGet(); true }, onDown = {})
        }
        assertEquals(5000L, withTimeout(30.seconds) { clock.requested.receive() })
        job.cancel()
        job.join()
        clock.advance(60_000)
        yield()
        assertEquals(0, probes.get(), "no check after the cancellation")
        assertTrue(job.isCancelled && job.isCompleted)
    }

    // ---- reconnecting ----

    private fun identity(name: String) = Identity.loadOrCreate(dir.resolve("id-$name"), ComponentKind.ENGINE.commonName(name))

    private fun driver(name: String, identity: Identity, resolver: FabricResolver? = null, clock: TetherClock = TetherClock.SYSTEM) =
        RemoteTetherDriver(
            identity, TrustStore(dir.resolve("trust-$name.json")), dir.resolve("peers-$name"), 0,
            resolver = resolver,
            options = RemoteTetherOptions(clock = clock),
        ).start().also { drivers += it }

    /** Records the messages that reach a receiving tether of [d]. */
    private fun receive(d: RemoteTetherDriver, fabric: String, senderFingerprint: String, into: MutableList<String>, got: CompletableDeferred<Unit>): AutoCloseable =
        d.portsFor(fabric).register(
            listOf(
                RemoteReceiver("t", "d", "in", null, senderFingerprint, TetherMode.TYPED) {
                    object : RemoteInbound {
                        override suspend fun onFrame(frame: WireFrame) {
                            into += ((frame as Message).value as JsonPrimitive).content
                            got.complete(Unit)
                        }

                        override fun onEnded(cause: Throwable?) {}
                    }
                },
            ),
        )

    private class Interruptions : RemoteInbound {
        val interrupted = AtomicInteger()
        val ended = AtomicInteger()

        override suspend fun onFrame(frame: WireFrame) {}

        override fun onEnded(cause: Throwable?) {
            ended.incrementAndGet()
        }

        override fun onInterrupted(cause: Throwable?) {
            interrupted.incrementAndGet()
        }
    }

    @Test
    @Tag("integration")
    fun aLostConnectionInvalidatesTheCacheAndTheTargetIsFoundAgainUnderItsNewAddress(): Unit = runBlocking {
        val clock = ManualClock()
        val a = identity("a")
        val b = identity("b")
        // two servers with the key of one engine: the engine "moved"
        val first = driver("b1", b)
        val second = driver("b2", b)
        val atFirst = CopyOnWriteArrayList<String>()
        val atSecond = CopyOnWriteArrayList<String>()
        val gotFirst = CompletableDeferred<Unit>()
        val gotSecond = CompletableDeferred<Unit>()
        receive(first, "fb", a.publicKeyFingerprint, atFirst, gotFirst)
        receive(second, "fb", a.publicKeyFingerprint, atSecond, gotSecond)
        val registry = CountingResolver("127.0.0.1:${first.port}")
        val sender = driver("a", a, registry, clock)
        val inbound = Interruptions()
        val remote = RemoteEndpoint(null, b.publicKeyFingerprint, "fb", "d", "in")
        val call = sender.portsFor("fa").connect(RemoteSender("t", remote, "cringle.std", TetherMode.TYPED, inbound)).also { calls += it }

        suspend fun sendWhenConnected(text: String) {
            withTimeout(30.seconds) {
                while (true) {
                    try {
                        call.send(Message(0u, "cringle.std", JsonPrimitive(text)))
                        return@withTimeout
                    } catch (e: RemoteUnavailableException) {
                        clock.advance(10_000) // a wait of the supervision ends
                        yield()
                    }
                }
            }
        }
        sendWhenConnected("one")
        withTimeout(30.seconds) { gotFirst.await() }
        assertEquals(listOf("one"), atFirst.toList())
        assertEquals(1, registry.asked.get())

        // the first engine goes away and the registry names the new address
        registry.address = "127.0.0.1:${second.port}"
        first.close()
        // the sender notices that the connection is gone (a server that shuts down gracefully lets a call live a while)
        withTimeout(30.seconds) { while (inbound.interrupted.get() < 1) yield() }
        sendWhenConnected("two")
        withTimeout(30.seconds) { gotSecond.await() }
        assertEquals(listOf("two"), atSecond.toList())
        assertTrue(registry.asked.get() >= 2, "the lost connection made the cache ask the registry again")
        assertTrue(inbound.interrupted.get() >= 1 && inbound.ended.get() == 0, "the tether was interrupted, not ended")
    }

    @Test
    fun anUnresolvableTargetIsTriedAgainWithABackoffAndBlocksNothing(): Unit = runBlocking {
        val clock = ManualClock()
        val a = identity("a")
        val registry = CountingResolver().also { it.failure = "the router does not know the fabric 'fb'" }
        val sender = driver("a", a, registry, clock)
        val remote = RemoteEndpoint(null, "ab".repeat(32), "fb", "d", "in")
        // the tether is established at once: a target that is not there yet does not fail the fabric
        val call = withTimeout(30.seconds) {
            sender.portsFor("fa").connect(RemoteSender("t", remote, "cringle.std", TetherMode.TYPED, Interruptions()))
        }.also { calls += it }
        val waits = ArrayList<Long>()
        repeat(9) {
            waits += withTimeout(30.seconds) { clock.requested.receive() }
            clock.advance(waits.last())
        }
        assertEquals(listOf(250L, 500L, 1000L, 2000L, 4000L, 8000L, 10_000L, 10_000L, 10_000L), waits)
        assertTrue(registry.asked.get() >= 9)
        // meanwhile a send fails at once instead of waiting
        assertThrows<RemoteUnavailableException> { runBlocking { call.send(Message(0u, "cringle.std", JsonPrimitive("x"))) } }
        assertFalse(registry.asked.get() == 0)
    }

    @Test
    fun aBindingNamesTheConcreteFabricThatIsLookedUp(): Unit = runBlocking {
        val clock = ManualClock()
        val a = identity("a")
        val asked = CopyOnWriteArrayList<String>()
        val bindings = InMemoryBindingResolver(mapOf("fb" to "fb-instance-2"))
        val sender = RemoteTetherDriver(
            a, TrustStore(dir.resolve("trust-bind.json")), dir.resolve("peers-bind"), 0,
            resolver = FabricResolver { asked += it; throw FabricNotResolvedException("not here") },
            bindings = bindings,
            options = RemoteTetherOptions(clock = clock),
        ).start().also { drivers += it }
        val remote = RemoteEndpoint(null, "ab".repeat(32), "fb", "d", "in")
        calls += sender.portsFor("fa").connect(RemoteSender("t", remote, "cringle.std", TetherMode.TYPED, Interruptions()))
        withTimeout(30.seconds) { clock.requested.receive() }
        assertEquals(listOf("fb-instance-2"), asked.toList())
        assertEquals(TetherBinding("fb", "fb-instance-2"), bindings.bind("t", "fb"))
        assertEquals(TetherBinding("x", "x"), BindingResolver.IDENTITY.bind("t", "x"))
    }
}
