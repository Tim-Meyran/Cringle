// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import cringle.stdblocks.data.parsePath
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JsonBlocksTest {
    @Test
    fun paths() {
        assertEquals(listOf<Any>("a", "b", 0, "c"), parsePath("a.b[0].c"))
        assertEquals(listOf<Any>("a", "b"), parsePath("$.a.b"))
        assertEquals(listOf<Any>(0, 1), parsePath("[0][1]"))
        assertEquals(emptyList<Any>(), parsePath(""))
        for (bad in listOf("a..b", "a[x]", "a[-1]", "a[1", "a b[", "a.")) assertThrows<IllegalArgumentException>(bad) { parsePath(bad) }
    }

    @Test
    fun parseAndStringify() = runTest {
        std("data.json-parse") {
            send("in", """{"user":{"name":"Ada","tags":["a","b"]},"n":1}""")
            send("in", "{broken")
            assertEquals(listOf<Any>(mapOf("user" to mapOf("name" to "Ada", "tags" to listOf("a", "b")), "n" to 1L)), out("out"))
            assertTrue((out("error").single() as String).contains("a key") && (out("error").single() as String).contains("position"))
        }
        std("data.json-stringify") {
            send("in", mapOf("a" to listOf(1L, "x")))
            send("in", "text")
            assertEquals(listOf<Any>("""{"a":[1,"x"]}""", "\"text\""), out("out"))
        }
    }

    @Test
    fun getFollowsThePathOrReportsMissing() = runTest {
        val doc = mapOf("user" to mapOf("name" to "Ada", "tags" to listOf("a", "b")))
        std("data.json-get", mapOf("path" to "user.tags[1]")) {
            send("in", doc)
            send("in", mapOf("user" to mapOf("tags" to listOf("only"))))
            send("in", "not an object")
            assertEquals(listOf<Any>("b"), out("found"))
            assertEquals(listOf<Any>(mapOf("user" to mapOf("tags" to listOf("only"))), "not an object"), out("missing"))
        }
        std("data.json-get", mapOf("path" to "$.user")) { send("in", doc); assertEquals(listOf<Any>(doc.getValue("user")), out("found")) }
        assertThrows<IllegalArgumentException> { std("data.json-get") {} }
        assertThrows<IllegalArgumentException> { std("data.json-get", mapOf("path" to "")) {} }
        assertThrows<IllegalArgumentException> { std("data.json-get", mapOf("path" to "a..b")) {} }
    }

    @Test
    fun setWritesACopyAndCreatesMissingKeys() = runTest {
        std("data.json-set", mapOf("path" to "user.name")) {
            val doc = mapOf("user" to mapOf("name" to "Ada", "age" to 36L))
            send("a", doc)
            send("b", "Grace")
            send("b", "Hedy")
            assertEquals(
                listOf<Any>(mapOf("user" to mapOf("name" to "Grace", "age" to 36L)), mapOf("user" to mapOf("name" to "Hedy", "age" to 36L))),
                out("out"),
                "the latest document and each new value",
            )
            assertEquals(mapOf("user" to mapOf("name" to "Ada", "age" to 36L)), doc, "the original stays as it was")
        }
        std("data.json-set", mapOf("path" to "a.b[0]")) {
            send("a", emptyMap<String, Any>())
            send("b", 1L)
            assertEquals(listOf<Any>(mapOf("a" to mapOf("b" to listOf(1L)))), out("out"))
        }
        std("data.json-set", mapOf("path" to "items[1]")) {
            send("a", mapOf("items" to listOf("x")))
            send("b", "y")
            assertEquals(listOf<Any>(mapOf("items" to listOf("x", "y"))), out("out"), "an index at the end appends")
        }
    }

    @Test
    fun setReportsAPathThatDoesNotFit() = runTest {
        std("data.json-set", mapOf("path" to "a.b")) {
            send("a", mapOf("a" to "text"))
            send("b", 1L)
            assertEquals(listOf<Any>("'a' is not an object"), out("error"))
        }
        std("data.json-set", mapOf("path" to "items[5]")) {
            send("a", mapOf("items" to listOf("x")))
            send("b", 1L)
            assertEquals(listOf<Any>("the index 5 is beyond the end of the list (1 entries)"), out("error"))
        }
        std("data.json-set", mapOf("path" to "[0]")) {
            send("a", mapOf("k" to 1L))
            send("b", 1L)
            assertEquals(listOf<Any>("'' is not a list"), out("error"))
        }
    }

    @Test
    fun base64AndSize() = runTest {
        std("data.base64-encode") { send("in", "häh?"); assertEquals(listOf<Any>("aMOkaD8="), out("out")) }
        std("data.base64-decode") {
            send("in", "aMOkaD8=")
            send("in", "not base64!")
            send("in", "/w==")
            assertEquals(listOf<Any>("häh?"), out("out"))
            assertEquals(2, out("error").size, "text that is no Base64 and bytes that are no UTF-8 are refused")
        }
        std("data.size") {
            send("in", "häh")
            send("in", listOf(1L, 2L))
            send("in", mapOf("a" to 1L))
            send("in", 5L)
            assertEquals(listOf<Any>(3L, 2L, 1L), out("out"))
            assertEquals(listOf<Any>("expected a text, a list or an object, got a number"), out("error"))
        }
    }
}
