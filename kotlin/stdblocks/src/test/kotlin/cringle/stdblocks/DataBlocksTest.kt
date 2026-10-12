// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DataBlocksTest {
    @Test
    fun castsPassTheRightKindAndSendTheReasonForTheOthers() = runTest {
        std("data.as-string") {
            send("in", "x")
            send("in", 1L)
            assertEquals(listOf<Any>("x"), out("out"))
            assertEquals(listOf<Any>("expected a text, got a number: 1"), out("error"))
        }
        std("data.as-number") {
            send("in", 1L)
            send("in", 2.5)
            send("in", "2")
            assertEquals(listOf<Any>(1.0, 2.5), out("out"))
            assertTrue((out("error").single() as String).startsWith("expected a number, got a text"))
        }
        std("data.as-int") {
            send("in", 3L)
            send("in", 4)
            send("in", 5.0)
            send("in", 5.5)
            send("in", listOf(1L))
            assertEquals(listOf<Any>(3L, 4L, 5L), out("out"))
            assertEquals(2, out("error").size)
            assertEquals("expected a whole number, got a list: [1]", out("error")[1])
        }
        std("data.as-boolean") {
            send("in", true)
            send("in", mapOf("a" to 1L))
            assertEquals(listOf<Any>(true), out("out"))
            assertEquals(listOf<Any>("expected a boolean, got an object: {\"a\":1}"), out("error"))
        }
    }
}
