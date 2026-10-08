// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The words of a line of the interactive mode. */
class ShellTest {
    @Test
    fun wordsAreSeparatedByWhiteSpace() {
        assertEquals(listOf("machine", "list"), splitWords("machine list"))
        assertEquals(listOf("machine", "list"), splitWords("   machine \t list  "))
        assertEquals(emptyList<String>(), splitWords("   "))
    }

    @Test
    fun quotesKeepWhiteSpaceInsideAWord() {
        assertEquals(listOf("machine", "add", "m 1", "127.0.0.1:7400"), splitWords("""machine add "m 1" 127.0.0.1:7400"""))
        assertEquals(listOf("a", "b c", "d"), splitWords("a 'b c' d"))
        assertEquals(listOf("say \"hi\""), splitWords("""'say "hi"'"""))
        assertEquals(listOf("ab"), splitWords("""a"b""""), "a quote inside a word joins the parts")
        assertEquals(listOf("", "x"), splitWords("""'' x"""), "an empty pair of quotes is an empty word")
    }

    @Test
    fun aQuoteThatIsNotClosedIsAnError() {
        val e = assertThrows<IllegalArgumentException> { splitWords("""machine add "m 1""") }
        assertEquals("the quote \" is not closed", e.message)
    }
}
