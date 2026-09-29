// SPDX-License-Identifier: Apache-2.0

package cringle.schema

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CanonicalJsonTest {
    private fun canonical(json: String) = CanonicalJson.encode(Json.parseToJsonElement(json))

    @Test
    fun numbersFollowRfc8785() {
        val vectors = mapOf(
            "4.50" to "4.5",
            "2e-3" to "0.002",
            "1E30" to "1e+30",
            "0.000000000000000000000000001" to "1e-27",
            "333333333.33333329" to "333333333.3333333",
            "-0" to "0",
            "-0.0" to "0",
            "0.0" to "0",
            "100.0" to "100",
            "1e21" to "1e+21",
            "1e20" to "100000000000000000000",
            "123456789012345680000" to "123456789012345680000",
            "0.000001" to "0.000001",
            "0.0000001" to "1e-7",
            "1.5e300" to "1.5e+300",
            "-1.25e-10" to "-1.25e-10",
            "0.1" to "0.1",
            "1e2" to "100",
            "5E+3" to "5000",
        )
        for ((input, expected) in vectors) assertEquals(expected, canonical(input), "number $input")
    }

    @Test
    fun integersInTheLongRangeAreWrittenExactly() {
        for (input in listOf("0", "7", "-7", "9007199254740993", "9223372036854775807", "-9223372036854775808")) {
            assertEquals(input, canonical(input))
        }
        // beyond the 64-bit range a number is an ordinary double
        assertEquals("9223372036854776000", canonical("9223372036854775808"))
    }

    @Test
    fun numbersOutOfTheDoubleRangeAreRejected() {
        assertThrows<IllegalArgumentException> { canonical("1e999") }
    }

    @Test
    fun keysAreSortedForObjectsAtEveryLevel() {
        assertEquals("""{"a":1,"b":{"x":1,"y":2},"c":[{"m":1,"n":2}]}""", canonical("""{"c":[{"n":2,"m":1}],"b":{"y":2,"x":1},"a":1}"""))
        assertEquals("""{"B":1,"a":2}""", canonical("""{"a":2,"B":1}""")) // by code unit: uppercase before lowercase
    }

    @Test
    fun keysAreSortedByUtf16CodeUnitsNotCodePoints() {
        // U+1F600 is the surrogate pair D83D DE00, which sorts before U+FB33
        val emoji = "😀"
        val hebrew = "דּ"
        assertEquals("{\"$emoji\":1,\"$hebrew\":2}", canonical("{\"$hebrew\":2,\"$emoji\":1}"))
    }

    @Test
    fun whitespaceIsDropped() {
        assertEquals("""{"a":[1,2,{"b":null}]}""", canonical(" {\n  \"a\" : [ 1 , 2 , { \"b\" : null } ]\n} "))
    }

    @Test
    fun literalsAndStrings() {
        assertEquals("[true,false,null]", canonical("[true,false,null]"))
        assertEquals("\"a\\\"b\\\\c\"", canonical("\"a\\\"b\\\\c\""))
        assertEquals("\"\\b\\t\\n\\f\\r\"", canonical("\"\\b\\t\\n\\f\\r\""))
        assertEquals("\"\\u0000\\u001f\"", canonical("\"\\u0000\\u001F\""))
        assertEquals("\"/\u007fé€\"", canonical("\"\\/\\u007f\\u00e9\\u20ac\"")) // no escaping beyond the required
        assertEquals("\"😀\"", canonical("\"\\uD83D\\uDE00\""))
        assertEquals("\"\"", canonical("\"\""))
    }

    @Test
    fun aStringThatLooksLikeANumberStaysAString() {
        assertEquals("\"1.50\"", canonical("\"1.50\""))
        assertEquals("[\"true\",true]", canonical("[\"true\",true]"))
    }

    @Test
    fun encodingIsStableUnderReparsing() {
        val inputs = listOf(
            """{"z":[1.0,2.50,-0,1e30,{"b":"x","a":"é"}],"a":null,"m":{"k":true}}""",
            "[9007199254740993,0.1,1e-7,\"\\ud83d\\ude00\"]",
            "{}",
            "[]",
        )
        for (input in inputs) {
            val once = canonical(input)
            assertEquals(once, canonical(once), "input $input")
        }
    }
}
