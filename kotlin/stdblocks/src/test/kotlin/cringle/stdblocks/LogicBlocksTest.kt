// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LogicBlocksTest {
    private suspend fun compare(name: String, operator: String?, a: Any, b: Any) = std(name, operator?.let { mapOf("operator" to it) } ?: emptyMap()) {
        send("a", a)
        send("b", b)
        result = out("out").single() as Boolean
    }

    private var result = false

    @Test
    fun numbersAreComparedWithTheOperator() = runTest {
        val cases = listOf(
            Triple("EQ", 1.0 to 1.0, true), Triple("EQ", 1.0 to 2.0, false), Triple("NE", 1.0 to 2.0, true),
            Triple("LT", 1.0 to 2.0, true), Triple("LT", 2.0 to 2.0, false), Triple("LE", 2.0 to 2.0, true),
            Triple("GT", 3.0 to 2.0, true), Triple("GT", 2.0 to 2.0, false), Triple("GE", 2.0 to 2.0, true),
        )
        for ((operator, pair, expected) in cases) {
            compare("logic.compare", operator, pair.first, pair.second)
            assertEquals(expected, result, "$operator ${pair.first} ${pair.second}")
        }
        compare("logic.compare", null, 4L, 4.0)
        assertEquals(true, result, "the operator defaults to EQ and integers count as numbers")
        assertThrows<IllegalArgumentException> { std("logic.compare", mapOf("operator" to "NEAR")) {} }
    }

    @Test
    fun textsAreComparedInTheirOrder() = runTest {
        compare("logic.compare-text", "LT", "apple", "banana")
        assertEquals(true, result)
        compare("logic.compare-text", "EQ", "a", "A")
        assertEquals(false, result)
    }

    @Test
    fun booleanOperators() = runTest {
        val table = mapOf("logic.and" to listOf(false, false, false, true), "logic.or" to listOf(false, true, true, true), "logic.xor" to listOf(false, true, true, false))
        for ((name, expected) in table) {
            val inputs = listOf(false to false, false to true, true to false, true to true)
            for ((i, pair) in inputs.withIndex()) {
                std(name) {
                    send("a", pair.first)
                    send("b", pair.second)
                    assertEquals(listOf<Any>(expected[i]), out("out"), "$name $pair")
                }
            }
        }
        std("logic.not") { send("in", true); send("in", false); assertEquals(listOf<Any>(false, true), out("out")) }
    }

    @Test
    fun ifRoutesByTheLatestCondition() = runTest {
        std("logic.if") {
            send("value", "before any condition")
            send("condition", true)
            send("value", "yes")
            send("condition", false)
            send("value", "no")
            assertEquals(listOf<Any>("yes"), out("then"))
            assertEquals(listOf<Any>("before any condition", "no"), out("else"))
        }
        std("logic.if-number") {
            send("condition", true)
            send("value", 2.5)
            assertEquals(listOf<Any>(2.5), out("then"))
        }
    }

    @Test
    fun filterPassesValuesWhileTheConditionIsTrue() = runTest {
        std("logic.filter") {
            send("value", "dropped")
            send("condition", true)
            send("value", "kept")
            send("condition", false)
            send("value", "dropped too")
            assertEquals(listOf<Any>("kept"), out("out"))
        }
        std("logic.filter-number") {
            send("condition", true)
            send("value", 1.0)
            assertEquals(listOf<Any>(1.0), out("out"))
        }
    }
}
