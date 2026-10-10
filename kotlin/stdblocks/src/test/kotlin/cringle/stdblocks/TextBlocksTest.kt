// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TextBlocksTest {
    @Test
    fun concatJoinsTheLatestValuesOnceBothAreThere() = runTest {
        std("text.concat", mapOf("separator" to "-")) {
            send("a", "x")
            assertEquals(emptyList<Any>(), out("out"), "no result before b")
            send("b", "y")
            send("a", "z")
            assertEquals(listOf<Any>("x-y", "z-y"), out("out"))
        }
        std("text.concat") {
            send("b", "2")
            send("a", "1")
            assertEquals(listOf<Any>("12"), out("out"))
        }
    }

    @Test
    fun templateSplitAndReplace() = runTest {
        std("text.template", mapOf("template" to "Hello {}!")) {
            send("in", "world")
            assertEquals(listOf<Any>("Hello world!"), out("out"))
        }
        assertThrows<IllegalArgumentException> { std("text.template", mapOf("template" to "no placeholder")) {} }
        assertThrows<IllegalArgumentException> { std("text.template") {} }

        std("text.split", mapOf("separator" to ",")) {
            send("in", "a,b,,c")
            assertEquals(listOf<Any>("a", "b", "", "c"), out("part"))
        }
        assertThrows<IllegalArgumentException> { std("text.split", mapOf("separator" to "")) {} }

        std("text.replace", mapOf("find" to "a", "replacement" to "o")) {
            send("in", "banana")
            assertEquals(listOf<Any>("bonono"), out("out"))
        }
        std("text.replace", mapOf("find" to "[0-9]+", "replacement" to "#", "regex" to true)) {
            send("in", "a12b3")
            assertEquals(listOf<Any>("a#b#"), out("out"))
        }
        assertThrows<IllegalArgumentException> { std("text.replace", mapOf("find" to "")) {} }
    }

    @Test
    fun regexMatchSendsTheGroupOrTheInput() = runTest {
        std("text.regex-match", mapOf("pattern" to "id=(\\d+)", "group" to 1)) {
            send("in", "order id=42 ok")
            send("in", "nothing here")
            assertEquals(listOf<Any>("42"), out("match"))
            assertEquals(listOf<Any>("nothing here"), out("nomatch"))
        }
        assertThrows<IllegalArgumentException> { std("text.regex-match", mapOf("pattern" to "(")) {} }
    }

    @Test
    fun trimCaseLengthAndContains() = runTest {
        std("text.trim") { send("in", "  x \n"); assertEquals(listOf<Any>("x"), out("out")) }
        std("text.upper") { send("in", "abC"); assertEquals(listOf<Any>("ABC"), out("out")) }
        std("text.lower") { send("in", "abC"); assertEquals(listOf<Any>("abc"), out("out")) }
        std("text.length") { send("in", "häh"); assertEquals(listOf<Any>(3L), out("out")) }
        std("text.contains", mapOf("needle" to "ell")) {
            send("in", "hello")
            send("in", "world")
            assertEquals(listOf<Any>("hello"), out("yes"))
            assertEquals(listOf<Any>("world"), out("no"))
        }
    }

    @Test
    fun parsersSendTheValueOrTheReason() = runTest {
        std("text.parse-int") {
            send("in", " 42 ")
            send("in", "4.5")
            assertEquals(listOf<Any>(42L), out("out"))
            assertEquals(listOf<Any>("not a valid value: 4.5"), out("error"))
        }
        std("text.parse-double") {
            send("in", "4.5")
            send("in", "NaN")
            send("in", "x")
            assertEquals(listOf<Any>(4.5), out("out"))
            assertEquals(2, out("error").size, "NaN and a word are refused")
        }
        std("text.parse-boolean") {
            send("in", "TRUE")
            send("in", "false")
            send("in", "yes")
            assertEquals(listOf<Any>(true, false), out("out"))
            assertEquals(1, out("error").size)
        }
    }

    @Test
    fun convertersToText() = runTest {
        std("text.to-string") { send("in", 42L); send("in", -7); assertEquals(listOf<Any>("42", "-7"), out("out")) }
        std("text.double-to-string") { send("in", 1.5); assertEquals(listOf<Any>("1.5"), out("out")) }
        std("text.boolean-to-string") { send("in", true); assertEquals(listOf<Any>("true"), out("out")) }
    }
}
