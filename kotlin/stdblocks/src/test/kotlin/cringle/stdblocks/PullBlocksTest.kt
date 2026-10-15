// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import cringle.contract.TetherType
import cringle.testkit.BlockTestHarness
import cringle.testkit.InMemoryTether
import cringle.testkit.TestBlockPorts
import cringle.testkit.TestDriverSet
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The blocks that can be asked for their value (`PULL`): the pure function blocks and `text.format`. */
class PullBlocksTest {
    private fun TestBlockPorts.slots(name: String) = varArgPort(name).map { it as InMemoryTether }

    private fun block(name: String, config: Map<String, Any?> = emptyMap(), sizes: Map<String, Int> = emptyMap()) =
        BlockTestHarness.forProvider(StdBlockProvider(), name, TestDriverSet(), config, sizes)

    private val unconnected: suspend (Any) -> Any = { throw IllegalStateException("port 'x' of block 'b' is not connected in the blueprint") }

    @Test
    fun onlyThePureFunctionBlocksSupportPull() {
        val definitions = StdBlockProvider().definitions.associateBy { it.name }
        for (name in listOf("text.upper", "text.concat", "math.add", "logic.not", "logic.and", "data.json-stringify", "text.to-string", "math.int-to-double")) {
            assertTrue(definitions.getValue(name).ports.all { TetherType.PULL in it.tetherTypes && TetherType.MESSAGE in it.tetherTypes }, name)
        }
        for (name in listOf("math.counter", "math.accumulator", "flow.log", "flow.once", "time.timer-trigger", "logic.if", "math.random")) {
            assertFalse(definitions.getValue(name).ports.any { TetherType.PULL in it.tetherTypes }, name)
        }
    }

    @Test
    fun aFunctionBlockAsksItsInputAndAnswersTheResult() = runTest {
        block("text.upper").runLifecycle {
            ports.tether("in").requestHandler = { "hello $it" }
            assertEquals("HELLO CTX", request("out", "ctx"))
            assertEquals(listOf<Any>("ctx"), ports.tether("in").requests, "the context goes upstream")
            // as before: a message goes through
            sendMessage("in", "abc")
            assertEquals(listOf<Any>("ABC"), ports.tether("out").sentMessages)
        }
        block("math.add").runLifecycle {
            ports.tether("a").requestHandler = { 1.5 }
            ports.tether("b").requestHandler = { 2L }
            assertEquals(3.5, request("out", "ctx"))
        }
        block("text.concat").runLifecycle {
            ports.tether("a").requestHandler = { "x" }
            ports.tether("b").requestHandler = { "y" }
            assertEquals("xy", request("out", "ctx"))
        }
    }

    @Test
    fun anOutputWithoutAValueForTheInputFails() = runTest {
        // data.json-parse sends a bad text on error, not on out
        block("data.json-parse").runLifecycle {
            ports.tether("in").requestHandler = { "{nope" }
            assertThrows<IllegalStateException> { request("out", "ctx") }
            assertEquals("ctx", ports.tether("in").requests.single())
        }
        block("text.upper").runLifecycle {
            ports.tether("in").requestHandler = unconnected
            assertThrows<IllegalStateException> { request("out", "ctx") }
        }
    }

    private val format = mapOf("template" to "Hello {name}, you are {age}! {{done}}", "variables" to listOf("name", "age"))

    @Test
    fun theTextFormatPushesWhenEveryVariableHasAValue() = runTest {
        block("text.format", format, mapOf("args" to 2)).runLifecycle {
            sendMessage("args", "Ada", 0)
            assertEquals(emptyList<Any>(), ports.tether("out").sentMessages, "not yet: age has no value")
            sendMessage("args", 36L, 1)
            assertEquals(listOf<Any>("Hello Ada, you are 36! {done}"), ports.tether("out").sentMessages)
            sendMessage("args", "Bob", 0)
            sendMessage("trigger", 1L)
            assertEquals(listOf<Any>("Hello Ada, you are 36! {done}", "Hello Bob, you are 36! {done}", "Hello Bob, you are 36! {done}"), ports.tether("out").sentMessages)
        }
    }

    @Test
    fun theTextFormatAsksItsSlotsWhenTheTextIsAskedFor() = runTest {
        block("text.format", format, mapOf("args" to 2)).runLifecycle {
            val slots = ports.slots("args")
            slots[0].requestHandler = { "Ada" }
            slots[1].requestHandler = { 36L }
            assertEquals("Hello Ada, you are 36! {done}", request("out", mapOf("ctx" to true)))
            assertEquals(listOf<Any>(mapOf("ctx" to true)), slots[0].requests, "the context is handed on")
            // a slot that is not asked (no pull tether) uses the kept value, a missing one is an error with the name
            sendMessage("args", "Kept", 0)
            slots[0].requestHandler = unconnected
            slots[1].requestHandler = unconnected
            val e = assertThrows<IllegalStateException> { request("out", "ctx") }
            assertTrue(e.message!!.contains("'age'"), e.message)
            sendMessage("args", 5L, 1)
            assertEquals("Hello Kept, you are 5! {done}", request("out", "ctx"))
        }
    }

    @Test
    fun theTextFormatChecksItsConfiguration() = runTest {
        assertThrows<IllegalArgumentException> { block("text.format", mapOf("template" to "{x}", "variables" to listOf("name")), mapOf("args" to 1)).init() }
        assertThrows<IllegalArgumentException> { block("text.format", mapOf("template" to "{name", "variables" to listOf("name")), mapOf("args" to 1)).init() }
        assertThrows<IllegalArgumentException> { block("text.format", mapOf("template" to "a}", "variables" to listOf("name")), mapOf("args" to 1)).init() }
        assertThrows<IllegalArgumentException> { block("text.format", mapOf("template" to "{a}", "variables" to listOf("a", "a")), mapOf("args" to 2)).init() }
        assertThrows<IllegalArgumentException> { block("text.format", mapOf("template" to "{a}", "variables" to listOf("a", "b")), mapOf("args" to 1)).init() }
        assertThrows<IllegalArgumentException> { block("text.format", emptyMap(), mapOf("args" to 1)).init() }
        // without variables there is one, `value`
        block("text.format", mapOf("template" to "<{value}>"), mapOf("args" to 1)).runLifecycle {
            sendMessage("args", mapOf("a" to 1L), 0)
            assertEquals(listOf<Any>("""<{"a":1}>"""), ports.tether("out").sentMessages, "a value that is no text is written as JSON")
        }
    }
}
