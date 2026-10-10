// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MathBlocksTest {
    private suspend fun binary(name: String, a: Double, b: Double, expected: Double) = std(name) {
        send("a", a)
        send("b", b)
        assertEquals(listOf<Any>(expected), out("out"), name)
    }

    @Test
    fun arithmeticOnTheLatestValues() = runTest {
        binary("math.add", 2.0, 3.0, 5.0)
        binary("math.subtract", 2.0, 3.0, -1.0)
        binary("math.multiply", 2.0, 3.5, 7.0)
        binary("math.divide", 7.0, 2.0, 3.5)
        binary("math.modulo", 7.0, 4.0, 3.0)
        binary("math.min", 2.0, 3.0, 2.0)
        binary("math.max", 2.0, 3.0, 3.0)
        std("math.add") {
            send("a", 1)
            send("b", 2L)
            send("a", 10.0)
            assertEquals(listOf<Any>(3.0, 12.0), out("out"), "integers are taken as numbers, a new a uses the old b")
        }
    }

    @Test
    fun divisionByZeroGoesToTheErrorPort() = runTest {
        for (name in listOf("math.divide", "math.modulo")) {
            std(name) {
                send("a", 1.0)
                send("b", 0.0)
                assertEquals(emptyList<Any>(), out("out"))
                assertEquals(listOf<Any>("division by zero"), out("error"))
            }
        }
    }

    @Test
    fun oneInputFunctions() = runTest {
        std("math.abs") { send("in", -2.5); assertEquals(listOf<Any>(2.5), out("out")) }
        std("math.negate") { send("in", 2.5); assertEquals(listOf<Any>(-2.5), out("out")) }
        std("math.floor") { send("in", 2.7); assertEquals(listOf<Any>(2.0), out("out")) }
        std("math.ceil") { send("in", 2.1); assertEquals(listOf<Any>(3.0), out("out")) }
        std("math.round") { send("in", 2.5); send("in", -2.5); assertEquals(listOf<Any>(3.0, -3.0), out("out"), "half up, away from zero") }
        std("math.round", mapOf("decimals" to 2)) { send("in", 3.14159); assertEquals(listOf<Any>(3.14), out("out")) }
        assertThrows<IllegalArgumentException> { std("math.round", mapOf("decimals" to 30)) {} }
    }

    @Test
    fun constantAndRandom() = runTest {
        std("math.constant", mapOf("value" to 2.5)) { send("trigger", 1L); send("trigger", 2L); assertEquals(listOf<Any>(2.5, 2.5), out("out")) }
        assertThrows<IllegalArgumentException> { std("math.constant") {} }
        std("math.random", mapOf("min" to 10, "max" to 20)) {
            repeat(50) { send("trigger", 1L) }
            assertTrue(out("out").all { (it as Double) >= 10.0 && it < 20.0 })
            assertTrue(out("out").toSet().size > 1, "not always the same value")
        }
        assertThrows<IllegalArgumentException> { std("math.random", mapOf("min" to 5, "max" to 5)) {} }
    }

    @Test
    fun counterAndAccumulatorKeepState() = runTest {
        std("math.counter", mapOf("start" to 10, "step" to 5)) {
            send("in", 0L)
            send("in", 0L)
            send("reset", 0L)
            send("in", 0L)
            assertEquals(listOf<Any>(15L, 20L, 15L), out("count"))
        }
        std("math.counter") { send("in", 0L); send("in", 0L); assertEquals(listOf<Any>(1L, 2L), out("count")) }
        std("math.accumulator") {
            send("in", 1.5)
            send("in", 2.0)
            send("reset", 0L)
            send("in", 4.0)
            assertEquals(listOf<Any>(1.5, 3.5, 4.0), out("sum"))
        }
    }

    @Test
    fun convertersBetweenIntAndDouble() = runTest {
        std("math.int-to-double") { send("in", 3L); assertEquals(listOf<Any>(3.0), out("out")) }
        std("math.to-int") {
            send("in", 2.5)
            send("in", -2.5)
            send("in", 1.0e30)
            assertEquals(listOf<Any>(3L, -2L), out("out"))
            assertEquals(1, out("error").size)
        }
    }
}
