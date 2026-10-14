// SPDX-License-Identifier: Apache-2.0

package cringle.engine.fabric

import cringle.contract.TetherType
import cringle.engine.tether.TetherInfo
import cringle.engine.tether.TrafficKind
import cringle.packaging.Endpoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrafficMonitorTest {
    private val tether = TetherInfo("c.out -> s.in", TetherType.MESSAGE, Endpoint("c", "out"), Endpoint("s", "in"))

    @Test
    fun nothingIsCopiedUntilSomebodyLooks() {
        val m = TrafficMonitor()
        m.observe(tether, TrafficKind.MESSAGE, "x")
        assertTrue(m.recent(tether.id).isEmpty())
        assertFalse(m.capturing(0))
        m.touch()
        m.observe(tether, TrafficKind.MESSAGE, "x")
        assertEquals(1, m.recent(tether.id).size)
    }

    @Test
    fun theLastValuesAreKeptNewestFirstUpToTheCapacity() {
        val m = TrafficMonitor(capacity = 3)
        m.touch()
        for (i in 1..5) m.observe(tether, TrafficKind.MESSAGE, mapOf("n" to i))
        val recent = m.recent(tether.id)
        assertEquals(listOf(5L, 4L, 3L), recent.map { it.seq })
        assertEquals("""{"n":5}""", recent.first().payload)
        assertEquals(listOf(5L, 4L), m.recent(tether.id, 2).map { it.seq })
    }

    @Test
    fun theLeaseRunsOut() {
        val m = TrafficMonitor()
        m.touch(1_000)
        assertTrue(m.capturing(1_000 + TrafficMonitor.LEASE_MILLIS - 1))
        assertFalse(m.capturing(1_000 + TrafficMonitor.LEASE_MILLIS))
    }

    @Test
    fun theValuesOfTcpAndSerialTethersAreNotCopied() {
        assertFalse(TrafficMonitor.canBeSeen(TetherType.TCP))
        assertFalse(TrafficMonitor.canBeSeen(TetherType.SERIAL))
        assertTrue(TrafficMonitor.canBeSeen(TetherType.MESSAGE) && TrafficMonitor.canBeSeen(TetherType.STREAM))
        val tcp = TetherInfo("a.out -> b.in", TetherType.TCP, Endpoint("a", "out"), Endpoint("b", "in"))
        val m = TrafficMonitor()
        m.touch()
        m.observe(tcp, TrafficKind.BYTES, ByteArray(4))
        assertTrue(m.recent(tcp.id).isEmpty())
    }

    @Test
    fun bytesAreShownAsADumpAndLongValuesAreShortened() {
        val m = TrafficMonitor()
        m.touch()
        m.observe(tether, TrafficKind.BYTES, byteArrayOf(1, 2, 255.toByte()))
        assertTrue(m.recent(tether.id).single().payload.startsWith("3 bytes"), m.recent(tether.id).single().payload)
        m.observe(tether, TrafficKind.MESSAGE, "y".repeat(10_000))
        assertTrue(m.recent(tether.id).first().payload.length <= PayloadText.MAX + 3)
    }
}
