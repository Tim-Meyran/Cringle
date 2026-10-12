// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JsonTest {
    @Test
    fun valuesKeepTheirKinds() {
        assertEquals("text", parseJson("\"text\""))
        assertEquals(42L, parseJson("42"))
        assertEquals(-7L, parseJson(" -7 "))
        assertEquals(2.5, parseJson("2.5"))
        assertEquals(1000.0, parseJson("1e3"))
        assertEquals(true, parseJson("true"))
        assertEquals(false, parseJson("false"))
        assertEquals(emptyList<Any>(), parseJson("[]"))
        assertEquals(emptyMap<String, Any>(), parseJson("{ }"))
        assertEquals(mapOf("a" to listOf(1L, "b", mapOf("c" to true))), parseJson("""{"a": [1, "b", {"c": true}]}"""))
        assertEquals(listOf("z", "a"), (parseJson("""{"z":1,"a":2}""") as Map<*, *>).keys.toList(), "the keys keep the order of the text")
        assertEquals(9223372036854775807L, parseJson("9223372036854775807"))
        assertTrue(parseJson("9223372036854775808") is Double, "an integer that does not fit is a number of the other kind")
    }

    @Test
    fun escapesAreRead() {
        assertEquals("a\"b\\c/d\ne\tf\u00e9\ud83d\ude00", parseJson("\"a\\\"b\\\\c\\/d\\ne\\tf\\u00e9\\ud83d\\ude00\""))
    }

    @Test
    fun invalidDocumentsAreRefusedWithAPosition() {
        val cases = mapOf(
            "" to "unexpected end",
            "null" to "null is not a valid value",
            "[1,null]" to "null is not a valid value",
            "{\"a\":1,\"a\":2}" to "duplicate key 'a'",
            "[1,]" to "unexpected character ']'",
            "{\"a\" 1}" to "':' is expected",
            "{a:1}" to "a key",
            "\"open" to "not closed",
            "\"bad\\q\"" to "unknown escape",
            "\"\\u12\"" to "four hexadecimal digits",
            "01" to "unexpected text after the document",
            "1e999" to "not a finite number",
            "[1] x" to "unexpected text after the document",
            "tru" to "unexpected character",
            "\"tab\there\"" to "control character",
        )
        for ((text, reason) in cases) {
            val e = assertThrows<JsonParseException>(text) { parseJson(text) }
            assertTrue(e.message!!.contains(reason), "$text: ${e.message}")
            assertTrue(e.message!!.contains("at position"), e.message)
        }
        assertThrows<JsonParseException> { parseJson("[".repeat(150) + "]".repeat(150)) }
    }

    @Test
    fun theWriterAndTheParserAgree() {
        val value = mapOf("a" to listOf(1L, "b\"\n\u0001", 2.5, true), "c" to mapOf("d" to emptyList<Any>()), "e" to "\u00e9")
        assertEquals(value, parseJson(toJson(value)))
        assertEquals("""{"a":[1,"b\"\n\u0001",2.5,true],"c":{"d":[]},"e":"é"}""", toJson(value))
    }
}
