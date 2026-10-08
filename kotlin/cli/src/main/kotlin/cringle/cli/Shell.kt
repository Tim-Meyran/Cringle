// SPDX-License-Identifier: Apache-2.0

package cringle.cli

/**
 * Splits a line of the interactive mode into words. Words are separated by white space; single or double quotes keep white
 * space inside a word (`machine add "m 1"` is a word with a blank); there is no escape character and no expansion. An
 * empty pair of quotes is an empty word. Throws [IllegalArgumentException] for a quote that is not closed.
 */
internal fun splitWords(line: String): List<String> {
    val words = ArrayList<String>()
    val word = StringBuilder()
    var inWord = false
    var quote: Char? = null
    for (c in line) {
        when {
            quote != null -> if (c == quote) quote = null else word.append(c)
            c == '"' || c == '\'' -> {
                quote = c
                inWord = true
            }
            c.isWhitespace() -> if (inWord) {
                words += word.toString()
                word.clear()
                inWord = false
            }
            else -> {
                word.append(c)
                inWord = true
            }
        }
    }
    require(quote == null) { "the quote $quote is not closed" }
    if (inWord) words += word.toString()
    return words
}
