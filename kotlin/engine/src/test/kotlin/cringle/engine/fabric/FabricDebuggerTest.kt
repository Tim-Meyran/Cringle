// SPDX-License-Identifier: Apache-2.0

package cringle.engine.fabric

import cringle.engine.tether.TetherInfo
import cringle.engine.tether.TrafficKind
import cringle.packaging.Endpoint
import cringle.contract.TetherType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FabricDebuggerTest {
    private val tether = TetherInfo("c.out -> s.in", TetherType.MESSAGE, Endpoint("c", "out"), Endpoint("s", "in"))
    private val other = TetherInfo("a.out -> b.in", TetherType.MESSAGE, Endpoint("a", "out"), Endpoint("b", "in"))
    private val scope = CoroutineScope(Dispatchers.Default)

    /** Delivers [payload] through the debugger on another thread; the latch opens when the delivery is let through. */
    private fun deliver(d: FabricDebugger, t: TetherInfo, payload: Any?): CountDownLatch {
        val through = CountDownLatch(1)
        scope.launch {
            d.beforeDelivery(t, TrafficKind.MESSAGE, payload)
            through.countDown()
        }
        return through
    }

    private fun awaitHeld(d: FabricDebugger, count: Int) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (d.state().held.size != count) {
            check(System.nanoTime() < deadline) { "timed out waiting for $count held values, have ${d.state().held.size}" }
            Thread.sleep(10)
        }
    }

    @Test
    fun withoutABreakpointNothingIsHeld() {
        val d = FabricDebugger()
        assertTrue(deliver(d, tether, "x").await(5, TimeUnit.SECONDS))
        assertTrue(d.state().held.isEmpty())
    }

    @Test
    fun aBreakpointHoldsTheValueAndShowsItWithSenderAndReceiver() {
        val d = FabricDebugger()
        d.setBreakpoint(tether.id, true)
        val through = deliver(d, tether, mapOf("n" to 1, "text" to "hello"))
        awaitHeld(d, 1)
        assertEquals(1, through.count, "the receiver did not get it")
        val held = d.state().held.single()
        assertEquals("c.out", held.from)
        assertEquals("s.in", held.to)
        assertEquals(TrafficKind.MESSAGE, held.kind)
        assertEquals("""{"n":1,"text":"hello"}""", held.payload)
        assertEquals(1, d.resume())
        assertTrue(through.await(5, TimeUnit.SECONDS))
        assertTrue(d.state().held.isEmpty())
        assertEquals(listOf(tether.id), d.state().breakpoints, "the breakpoint stays")
    }

    @Test
    fun stepReleasesTheOldestOnlyAndTheNextValueIsHeldAgain() {
        val d = FabricDebugger()
        d.setBreakpoint(tether.id, true)
        val first = deliver(d, tether, "one")
        awaitHeld(d, 1)
        val second = deliver(d, tether, "two")
        awaitHeld(d, 2)
        assertEquals(1, d.resume(one = true))
        assertTrue(first.await(5, TimeUnit.SECONDS))
        assertEquals(1, second.count)
        assertEquals("\"two\"", d.state().held.single().payload)
        d.resume()
        assertTrue(second.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun resumeCanBeLimitedToOneTetherAndRemovingABreakpointReleasesWhatItHeld() {
        val d = FabricDebugger()
        d.setBreakpoint(tether.id, true)
        d.setBreakpoint(other.id, true)
        val a = deliver(d, tether, "a")
        val b = deliver(d, other, "b")
        awaitHeld(d, 2)
        assertEquals(1, d.resume(other.id))
        assertTrue(b.await(5, TimeUnit.SECONDS))
        assertEquals(1, a.count)
        d.setBreakpoint(tether.id, false)
        assertTrue(a.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(other.id), d.state().breakpoints)
    }

    @Test
    fun bytesAreShownAsAShortDump() {
        val d = FabricDebugger()
        d.setBreakpoint(tether.id, true)
        scope.launch { d.beforeDelivery(tether, TrafficKind.BYTES, ByteArray(40) { it.toByte() }) }
        awaitHeld(d, 1)
        assertTrue(d.state().held.single().payload.startsWith("40 bytes: 00 01 02"))
        d.close()
    }

    @Test
    fun closeReleasesEverythingAndHoldsNothingAfterwards() {
        val d = FabricDebugger()
        d.setBreakpoint(tether.id, true)
        val held = deliver(d, tether, "x")
        awaitHeld(d, 1)
        d.close()
        assertTrue(held.await(5, TimeUnit.SECONDS))
        assertTrue(deliver(d, tether, "y").await(5, TimeUnit.SECONDS))
        assertTrue(d.state().breakpoints.isEmpty())
        assertThrows<IllegalArgumentException> { d.setBreakpoint(" ", true) }
    }
}
