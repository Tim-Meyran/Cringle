// SPDX-License-Identifier: Apache-2.0

package cringle.testkit

import cringle.contract.DriverType
import cringle.contract.DwhDriver
import cringle.contract.IsolationLevel
import cringle.contract.TetherType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FakesTest {
    @Test
    fun operationsOfTheWrongTetherTypeFail() = runTest {
        val message = InMemoryTether(TetherType.MESSAGE)
        assertTrue(assertThrows<IllegalStateException> { message.request("x") }.message!!.contains("not valid for a MESSAGE"))
        assertThrows<IllegalStateException> { message.openStream() }
        assertThrows<IllegalStateException> { message.openByteStream() }
        assertThrows<IllegalStateException> { InMemoryTether(TetherType.STREAM).send("x") }
    }

    @Test
    fun scriptedRequestsAreRecorded() = runTest {
        val t = InMemoryTether(TetherType.REQUEST_RESPONSE)
        assertThrows<IllegalStateException> { t.request("unscripted") }
        t.requestHandler = { "re:$it" }
        assertEquals("re:a", t.request("a"))
        assertEquals(listOf<Any>("unscripted", "a"), t.requests)
    }

    @Test
    fun streamsRecordAndFeed() = runTest {
        val t = InMemoryTether(TetherType.STREAM)
        val s = t.openStream() as InMemoryTetherStream
        s.feed(1)
        assertEquals(1, s.incoming.first())
        s.send("out")
        s.close()
        assertEquals(listOf<Any>("out"), s.sent)
        assertTrue(s.closed)
        assertThrows<IllegalStateException> { s.send("late") }
        assertEquals(listOf(s), t.streams)
    }

    @Test
    fun driverSetHandsOutRegisteredDriversOnly() = runTest {
        val dwh = InMemoryDwhDriver()
        val set = TestDriverSet().add(dwh as DwhDriver)
        assertSame(dwh, set[DwhDriver::class])
        assertThrows<IllegalArgumentException> { set[RecordingDriver::class] }
        val other = RecordingDriver(DriverType("x", IsolationLevel.PROCESS))
        val both = TestDriverSet().add(RecordingDriver::class, other)
        both.startAll()
        both.stopAll()
        assertEquals(listOf("start", "stop"), other.calls)
    }

    @Test
    fun unknownPortsFail() {
        val ports = TestBlockPorts()
        assertThrows<IllegalArgumentException> { ports.port("x") }
        assertThrows<IllegalArgumentException> { ports.varArgPort("x") }
    }
}
