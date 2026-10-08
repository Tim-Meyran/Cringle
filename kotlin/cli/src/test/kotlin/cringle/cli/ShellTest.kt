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

/** Completion and history of the interactive mode on a terminal. */
class ShellTerminalTest {
    @org.junit.jupiter.api.io.TempDir
    lateinit var dir: java.nio.file.Path

    // --- what Tab offers (the pure part) ---

    @Test
    fun theFirstWordCompletesToCommandsAndTheShellWords() {
        assertEquals(listOf("machine"), ShellCompletion.candidates(emptyList(), 0, "mach"))
        assertEquals(listOf("engine", "exit"), ShellCompletion.candidates(emptyList(), 0, "e"))
        assertEquals(true, "help" in ShellCompletion.candidates(emptyList(), 0, ""))
        assertEquals(listOf("--home", "--json", "--server"), ShellCompletion.candidates(emptyList(), 0, "--"))
    }

    @Test
    fun theSecondWordCompletesToTheSubcommandsOfTheFirst() {
        assertEquals(listOf("add", "list", "remove"), ShellCompletion.candidates(listOf("machine"), 1, "").filter { !it.startsWith("-") })
        assertEquals(listOf("list"), ShellCompletion.candidates(listOf("machine"), 1, "l"))
        assertEquals(listOf("add", "add-component", "list", "revoke"), ShellCompletion.candidates(listOf("trust"), 1, ""))
        assertEquals(listOf("add", "add-component"), ShellCompletion.candidates(listOf("trust"), 1, "add"))
    }

    @Test
    fun optionsOfTheCommandAreOfferedAfterTheCommandWords() {
        val options = ShellCompletion.candidates(listOf("trust", "add", "127.0.0.1:7600"), 3, "--")
        assertEquals(listOf("--fingerprint", "--help", "--yes"), options)
        assertEquals(emptyList<String>(), ShellCompletion.candidates(listOf("whoami"), 1, "x"))
        assertEquals(listOf("add", "list", "remove"), ShellCompletion.candidates(listOf("help", "machine"), 2, ""))
    }

    // --- JLine on a terminal that is made of streams ---

    /**
     * The keys, and then no more: the stream **waits** until the terminal is closed instead of ending. An input that ends at once lets the terminal
     * see the end of its input and close before `readLine` has taken the keys (an `EndOfFileException` that depends on timing).
     */
    private class KeysThenWait(keys: ByteArray) : java.io.InputStream() {
        private val data = java.io.ByteArrayInputStream(keys)
        private val closed = java.util.concurrent.CountDownLatch(1)

        override fun read(): Int {
            val b = data.read()
            if (b >= 0) return b
            closed.await(30, java.util.concurrent.TimeUnit.SECONDS)
            return -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = data.read(b, off, len)
            if (n > 0) return n
            closed.await(30, java.util.concurrent.TimeUnit.SECONDS)
            return -1
        }

        override fun close() = closed.countDown()
    }

    private val terminals = ArrayList<org.jline.terminal.Terminal>()

    @org.junit.jupiter.api.AfterEach
    fun closeTerminals() {
        terminals.forEach { runCatching { it.close() } }
        terminals.clear()
    }

    private fun reader(keys: String, history: java.nio.file.Path): org.jline.reader.LineReader {
        val terminal = org.jline.terminal.TerminalBuilder.builder()
            .system(false)
            .streams(KeysThenWait(keys.toByteArray()), java.io.ByteArrayOutputStream())
            .type("xterm")
            .build()
        terminals += terminal
        return lineReaderFor(terminal, history)
    }

    @Test
    fun tabCompletesAUniquePrefix() {
        val line = reader("mach\t\n", dir.resolve("history")).readLine("cringle> ")
        assertEquals("machine", line.trim())
    }

    @Test
    fun theHistoryIsKeptBetweenSessionsAndTheUpArrowBringsTheLastLineBack() {
        val history = dir.resolve("history")
        assertEquals("whoami", reader("whoami\n", history).readLine("> "))
        assertEquals("whoami", reader("\u001bOA\n", history).readLine("> "))
    }

    @Test
    fun aLineThatGivesATokenIsNeverKept() {
        val history = dir.resolve("history")
        reader("login --token secret-token-1\n", history).readLine("> ")
        reader("login --token=secret-token-2\n", history).readLine("> ")
        reader("login --token-file t.txt\n", history).readLine("> ")
        val kept = java.nio.file.Files.readString(history)
        assertEquals(false, kept.contains("secret-token"), kept)
        assertEquals(true, kept.contains("login --token-file t.txt"), kept)
    }

    @Test
    fun withoutATerminalThereIsNoTerminalInput() {
        // the tests run with the console redirected: the shell then reads plain lines
        assertEquals(null, terminalShellInput(dir.resolve("history")))
    }
}
