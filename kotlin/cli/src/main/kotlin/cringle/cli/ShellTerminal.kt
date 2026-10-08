// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import cringle.common.OwnerOnlyFiles
import java.io.BufferedReader
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.jline.reader.Candidate
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder

/** Where the lines of the interactive mode come from. */
internal interface ShellInput : AutoCloseable {
    /** Shows [prompt] and returns the next line, or `null` at the end of the input. */
    fun readLine(prompt: String): String?

    /** Whether the input edits the line with history and completion (a terminal), as opposed to plain lines. */
    val isTerminal: Boolean

    override fun close() {}
}

/** Plain lines of a stream: used when the input is a pipe or a file, and by the tests. */
internal class PlainShellInput(private val reader: BufferedReader, private val out: PrintStream) : ShellInput {
    override val isTerminal: Boolean get() = false

    override fun readLine(prompt: String): String? {
        out.print(prompt)
        out.flush()
        return reader.readLine()
    }
}

/**
 * What Tab offers in the interactive mode: the next word of a command (`machine` then `add`, `list`, ...), the options of
 * the command typed so far, and `help`, `exit` and `quit` as first word. Values such as the ids of machines or engines are
 * not completed: that would take a call to the server on every Tab.
 */
internal object ShellCompletion {
    private val globalOptions = listOf("--server", "--home", "--json")

    /**
     * The completions for the word at [wordIndex] of a line whose words are [words]; [prefix] is what has been typed of
     * that word. The result is sorted and only holds candidates that start with [prefix].
     */
    fun candidates(words: List<String>, wordIndex: Int, prefix: String): List<String> {
        val before = words.take(wordIndex)
        val found = LinkedHashSet<String>()
        found += nextWords(before)
        if (before.firstOrNull() == "help") found += nextWords(before.drop(1))
        if (wordIndex == 0) {
            found += listOf("help", "exit", "quit")
            if (prefix.startsWith("-")) found += globalOptions
        }
        val command = COMMANDS.filter { it.words.size <= before.size && before.take(it.words.size) == it.words }.maxByOrNull { it.words.size }
        if (command != null && (prefix.isEmpty() || prefix.startsWith("-"))) {
            found += command.options.map { "--" + it.name }
            found += "--help"
        }
        return found.filter { it.startsWith(prefix) }.sorted()
    }

    private fun nextWords(before: List<String>): List<String> =
        COMMANDS.filter { it.words.size > before.size && it.words.take(before.size) == before }.map { it.words[before.size] }
}

private class TerminalShellInput(private val terminal: Terminal, private val reader: LineReader) : ShellInput {
    override val isTerminal: Boolean get() = true

    override fun readLine(prompt: String): String? = try {
        reader.readLine(prompt)
    } catch (e: UserInterruptException) {
        "" // Ctrl-C clears the line and goes on
    } catch (e: EndOfFileException) {
        null // Ctrl-D leaves the shell
    }

    override fun close() = terminal.close()
}

/**
 * The input of the interactive mode on a real terminal: line editing, a history that is kept in [historyFile] between
 * sessions, and Tab completion of commands and options ([ShellCompletion]). `null` when there is no terminal (the input or the
 * output is a pipe or a file, or the terminal is a dumb one); the caller reads plain lines then.
 *
 * The history file belongs to the owner alone, and a line that gives a token on the command line (`--token`, but not
 * `--token-file`) is never written to it.
 */
internal fun terminalShellInput(historyFile: Path): ShellInput? {
    if (System.console() == null) return null // JLine would only warn and create a dumb terminal
    val terminal = try {
        TerminalBuilder.builder().system(true).build()
    } catch (e: Exception) {
        return null
    }
    if (terminal.type == Terminal.TYPE_DUMB || terminal.type == Terminal.TYPE_DUMB_COLOR) {
        terminal.close()
        return null
    }
    return TerminalShellInput(terminal, lineReaderFor(terminal, historyFile))
}

/**
 * The line reader on [terminal]: completion by [ShellCompletion] and a history in [historyFile] that belongs to the owner
 * alone and never holds a line that gives a token (`--token`, `--token=`).
 */
internal fun lineReaderFor(terminal: Terminal, historyFile: Path): LineReader {
    try {
        Files.createDirectories(historyFile.toAbsolutePath().parent)
        if (!Files.exists(historyFile)) OwnerOnlyFiles.writeAtomically(historyFile, "")
    } catch (e: Exception) {
        // no history then; the shell itself works
    }
    return LineReaderBuilder.builder()
        .terminal(terminal)
        .appName("cringle")
        .completer { _, line, candidates ->
            ShellCompletion.candidates(line.words(), line.wordIndex(), line.word()).forEach { candidates += Candidate(it) }
        }
        .variable(LineReader.HISTORY_FILE, historyFile)
        .variable(LineReader.HISTORY_IGNORE, "*--token *:*--token=*")
        .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
        .build()
}
