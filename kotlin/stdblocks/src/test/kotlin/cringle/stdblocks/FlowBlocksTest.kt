// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import cringle.contract.BuiltinDriverTypes
import cringle.contract.DriverType
import cringle.contract.LogLevel
import cringle.contract.LoggingDriver
import cringle.stdblocks.flow.Debounce
import cringle.stdblocks.flow.Delay
import cringle.testkit.BlockTestHarness
import cringle.testkit.TestBlockPorts
import cringle.testkit.TestDriverSet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class FlowBlocksTest {
    private fun ports(vararg names: String) = TestBlockPorts().also { p -> names.forEach { p.addPort(it) } }

    @Test
    fun delayHoldsEveryValueForTheDelayAndKeepsTheOrder() = runTest {
        val ports = ports("in", "out")
        val h = BlockTestHarness(Delay(StandardTestDispatcher(testScheduler)), config = mapOf("delayMs" to 100), ports = ports)
        h.init()
        h.start()
        h.sendMessage("in", "a")
        advanceTimeBy(50)
        h.sendMessage("in", mapOf("b" to 1L))
        runCurrent()
        assertEquals(emptyList<Any>(), ports.tether("out").sentMessages)
        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf<Any>("a"), ports.tether("out").sentMessages)
        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf<Any>("a", mapOf("b" to 1L)), ports.tether("out").sentMessages)
        // what still waits is dropped when the block stops
        h.sendMessage("in", "late")
        h.stop()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, ports.tether("out").sentMessages.size)
        assertThrows<IllegalArgumentException> { BlockTestHarness(Delay(), config = mapOf("delayMs" to -1), ports = ports("in", "out")).init() }
    }

    @Test
    fun debounceSendsTheLastValueAfterTheQuietTime() = runTest {
        val ports = ports("in", "out")
        val h = BlockTestHarness(Debounce(StandardTestDispatcher(testScheduler)), config = mapOf("quietMs" to 100), ports = ports)
        h.init()
        h.start()
        h.sendMessage("in", 1L)
        advanceTimeBy(60)
        h.sendMessage("in", 2L)
        advanceTimeBy(99)
        runCurrent()
        assertEquals(emptyList<Any>(), ports.tether("out").sentMessages)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf<Any>(2L), ports.tether("out").sentMessages)
        h.stop()
        assertThrows<IllegalArgumentException> { BlockTestHarness(Debounce(), ports = ports("in", "out")).init() }
    }

    @Test
    fun bufferSendsAListOnFlushOrWhenFull() = runTest {
        std("flow.buffer", mapOf("size" to 3)) {
            send("in", "a")
            send("in", 1L)
            assertEquals(emptyList<Any>(), out("batch"))
            send("flush", 0L)
            send("flush", 0L)
            send("in", true)
            send("in", 2.5)
            send("in", "x")
            assertEquals(listOf<Any>(listOf<Any>("a", 1L), listOf<Any>(true, 2.5, "x")), out("batch"), "an empty buffer sends nothing")
        }
        std("flow.buffer") { send("in", 1L); send("flush", 0L); assertEquals(listOf<Any>(listOf<Any>(1L)), out("batch")) }
        assertThrows<IllegalArgumentException> { std("flow.buffer", mapOf("size" to 0)) {} }
    }

    @Test
    fun mergeOnceAndThrottle() = runTest {
        std("flow.merge") {
            send("a", "x")
            send("b", 2L)
            send("a", mapOf("k" to "v"))
            assertEquals(listOf<Any>("x", 2L, mapOf("k" to "v")), out("out"))
        }
        std("flow.once") {
            send("in", 1L)
            send("in", 2L)
            send("reset", 0L)
            send("in", 3L)
            assertEquals(listOf<Any>(1L, 3L), out("out"))
        }
        std("flow.throttle", mapOf("intervalMs" to 150)) {
            send("in", 1L)
            send("in", 2L)
            Thread.sleep(200)
            send("in", 3L)
            assertEquals(listOf<Any>(1L, 3L), out("out"))
            assertEquals(listOf<Any>(2L), out("dropped"))
        }
        assertThrows<IllegalArgumentException> { std("flow.throttle") {} }
    }

    @Test
    fun logAnyWritesTheValueAsJson() = runTest {
        val lines = ArrayList<Pair<LogLevel, String>>()
        val driver = object : LoggingDriver {
            override val type: DriverType get() = BuiltinDriverTypes.LOGGING

            override suspend fun log(level: LogLevel, message: String, error: Throwable?) {
                lines += level to message
            }
        }
        val h = BlockTestHarness.forProvider(StdBlockProvider(), "flow.log-any", TestDriverSet().add(LoggingDriver::class, driver), mapOf("prefix" to "v: ", "level" to "WARN"))
        h.runLifecycle {
            sendMessage("in", mapOf("a" to listOf(1L, "b\"q"), "c" to true, "d" to 2.0, "e" to 1.5))
            sendMessage("in", "text")
        }
        assertEquals(listOf(LogLevel.WARN to """v: {"a":[1,"b\"q"],"c":true,"d":2,"e":1.5}""", LogLevel.WARN to "v: \"text\""), lines)
    }

    @Test
    fun ifAndFilterOnAnyValues() = runTest {
        std("logic.if-any") {
            send("condition", true)
            send("value", mapOf("k" to 1L))
            send("condition", false)
            send("value", listOf("x"))
            assertEquals(listOf<Any>(mapOf("k" to 1L)), out("then"))
            assertEquals(listOf<Any>(listOf("x")), out("else"))
        }
        std("logic.filter-any") {
            send("condition", true)
            send("value", 5L)
            send("condition", false)
            send("value", 6L)
            assertEquals(listOf<Any>(5L), out("out"))
        }
    }
}
