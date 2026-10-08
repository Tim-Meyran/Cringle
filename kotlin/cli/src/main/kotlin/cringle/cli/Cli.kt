// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import io.grpc.Status
import io.grpc.StatusException
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking

/**
 * The `cringle` command line. It talks to the ManagementServer only. Exit codes: 0 success, 1 the command failed,
 * 2 the command line is wrong.
 */
public class Cli(
    private val out: PrintStream,
    private val err: PrintStream,
    private val stdin: InputStream = System.`in`,
    private val environment: Map<String, String> = System.getenv(),
    /** The version `--version` prints; by default the one of the distribution the CLI was started from. */
    private val version: String = Distribution.version(),
) {
    /** Runs one command line and returns the exit code. */
    public fun run(args: List<String>): Int {
        var command: Command? = null
        try {
            var json = false
            var server: String? = null
            var home: String? = null
            val rest = ArrayList<String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "--json" -> json = true
                    a == "--server" -> server = args.getOrNull(++i) ?: throw UsageException("--server needs a value")
                    a.startsWith("--server=") -> server = a.removePrefix("--server=")
                    a == "--home" -> home = args.getOrNull(++i) ?: throw UsageException("--home needs a value")
                    a.startsWith("--home=") -> home = a.removePrefix("--home=")
                    else -> rest += a
                }
                i++
            }
            if (rest.firstOrNull() == "--version") {
                out.println("cringle $version")
                return 0
            }
            if (rest == listOf("shell")) {
                // the global options of this call apply to every command of the session
                return shell(shellHome(home), buildList {
                    server?.let { add("--server"); add(it) }
                    home?.let { add("--home"); add(it) }
                    if (json) add("--json")
                })
            }
            val wantsHelp = rest.isEmpty() || rest.first() == "help" || rest.first() == "--help" || rest.first() == "-h"
            if (wantsHelp) {
                val words = rest.filter { it != "help" && it != "--help" && it != "-h" }
                out.println(if (words.isEmpty()) generalHelp() else helpFor(words))
                return 0
            }
            command = COMMANDS.filter { rest.size >= it.words.size && rest.take(it.words.size) == it.words }.maxByOrNull { it.words.size }
                ?: throw UsageException("unknown command '${rest.first()}'; 'cringle --help' lists the commands")
            val commandArgs = rest.drop(command.words.size)
            if ("--help" in commandArgs || "-h" in commandArgs) {
                out.println(commandHelp(command))
                return 0
            }
            val parsed = ArgParser.parse(commandArgs, command.options)
            if (parsed.positional.size < command.minArgs || parsed.positional.size > command.maxArgs) {
                throw UsageException("expected ${if (command.args.isEmpty()) "no arguments" else command.args}")
            }
            val homeDir: Path = (home ?: environment["CRINGLE_HOME"]?.takeIf { it.isNotBlank() })?.let { Paths.get(it) }
                ?: Paths.get(System.getProperty("user.home"), ".cringle")
            val profileFile = homeDir.resolve("cli.json")
            val stored = Profile.load(profileFile)
            val effective = Profile(
                server ?: environment["CRINGLE_SERVER"]?.takeIf { it.isNotBlank() } ?: stored.server,
                environment["CRINGLE_TOKEN"]?.takeIf { it.isNotBlank() } ?: stored.token,
                environment["CRINGLE_FINGERPRINT"]?.takeIf { it.isNotBlank() } ?: stored.fingerprint,
            )
            if (command.needsServer && effective.server == null) {
                throw UsageException("no server address: use --server host:port, CRINGLE_SERVER, or 'cringle login --server host:port'")
            }
            var connection: Connection? = null
            lateinit var env: Env
            env = Env(
                connection = {
                    connection ?: run {
                        val pinned = effective.fingerprint ?: throw UsageException(NO_FINGERPRINT)
                        Connection(effective.server!!, effective.token, pinned).also { connection = it }
                    }
                },
                profile = effective,
                profileFile = profileFile,
                readSecret = { stdin.bufferedReader().readLine() },
                save = { it.save(profileFile) },
                warn = { err.println("warning: $it") },
                environment = environment,
            )
            try {
                val output = runBlocking { command.run(env, parsed) }
                out.println(if (json) Render.json(output) else Render.human(output))
                return 0
            } finally {
                connection?.close()
            }
        } catch (e: UsageException) {
            err.println("error: ${e.message}")
            if (command != null) err.println("usage: cringle ${command.name} ${command.args}".trimEnd())
            return 2
        } catch (e: StatusException) {
            err.println("error: ${describe(e.status)}")
            return 1
        } catch (e: Exception) {
            err.println("error: ${e.message ?: e.javaClass.simpleName}")
            return 1
        }
    }

    /** The Cringle home of the session: `--home`, `CRINGLE_HOME` or `~/.cringle`. */
    private fun shellHome(home: String?): Path =
        (home ?: environment["CRINGLE_HOME"]?.takeIf { it.isNotBlank() })?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".cringle")

    /**
     * The interactive mode: reads one command per line and runs it like a command line of its own, with the global options
     * [globals]; a failing command is reported and the session goes on. `exit`, `quit` or the end of the input leave it.
     * On a terminal the line can be edited, Tab completes commands and options and the history is kept in
     * `<home>/shell-history`; from a pipe or a file the lines are read as they are. The standard input of a single command is
     * empty: a login in the shell takes its token with `--token-file` or `--token`, because the lines of the session are
     * not its token.
     */
    private fun shell(home: Path, globals: List<String>): Int {
        val input = (if (stdin === System.`in`) terminalShellInput(home.resolve("shell-history")) else null)
            ?: PlainShellInput(stdin.bufferedReader(), out)
        input.use {
            out.println(
                if (it.isTerminal) "cringle $version: Tab completes, the arrow keys show the history, 'help' lists the commands, 'exit' leaves"
                else "cringle $version: type a command, 'help' lists them, 'exit' leaves",
            )
            while (true) {
                val line = it.readLine("cringle> ") ?: break
                val words = try {
                    splitWords(line)
                } catch (e: IllegalArgumentException) {
                    err.println("error: ${e.message}")
                    continue
                }
                if (words.isEmpty()) continue
                if (words.first() == "exit" || words.first() == "quit") break
                if (words.first() == "shell") {
                    err.println("error: you are in the shell already")
                    continue
                }
                Cli(out, err, java.io.ByteArrayInputStream(ByteArray(0)), environment, version).run(globals + words)
            }
        }
        out.println()
        return 0
    }

    private fun describe(status: Status): String {
        val text = status.description ?: status.code.name.lowercase().replace('_', ' ')
        return when (status.code) {
            Status.Code.UNAUTHENTICATED -> "$text (not logged in or token invalid: run 'cringle login')"
            Status.Code.PERMISSION_DENIED -> "$text (your role does not allow this)"
            Status.Code.UNAVAILABLE -> "cannot reach the server: $text"
            else -> "$text [${status.code}]"
        }
    }

    private fun generalHelp(): String = buildString {
        appendLine("cringle - command line of the Cringle ManagementServer")
        appendLine()
        appendLine("usage: cringle [--server host:port] [--json] [--home dir] <command> [options]")
        appendLine()
        appendLine("commands:")
        val width = COMMANDS.maxOf { "${it.name} ${it.args}".trim().length }
        for (c in COMMANDS) appendLine("  ${"${c.name} ${c.args}".trim().padEnd(width)}  ${c.summary}")
        appendLine()
        appendLine("global options:")
        appendLine("  --server host:port  address of the ManagementServer (default: CRINGLE_SERVER or the profile)")
        appendLine("  --json              print JSON instead of text")
        appendLine("  --home dir          Cringle home with the profile cli.json (default: CRINGLE_HOME or ~/.cringle)")
        appendLine("  --version           print the version of this installation")
        appendLine("  --help              show this help; 'cringle <command> --help' shows the options of a command")
        appendLine()
        appendLine("The profile <home>/cli.json holds the server address, the token and the fingerprint of the server key that 'cringle login'")
        appendLine("stored; the token can be overridden with CRINGLE_TOKEN and the fingerprint with CRINGLE_FINGERPRINT. The connection is TLS 1.3")
        appendLine("and pinned to that fingerprint; a command that connects without one stops with exit code 2.")
        appendLine("Exit codes: 0 success, 1 the command failed, 2 the command line is wrong.")
    }.trimEnd()

    private fun helpFor(words: List<String>): String {
        val exact = COMMANDS.firstOrNull { it.words == words }
        if (exact != null) return commandHelp(exact)
        val group = COMMANDS.filter { it.words.take(words.size) == words }
        if (group.isEmpty()) throw UsageException("unknown command '${words.joinToString(" ")}'")
        return group.joinToString("\n") { "cringle ${it.name} ${it.args}".trimEnd() + "\n    ${it.summary}" }
    }

    private fun commandHelp(c: Command): String = buildString {
        appendLine("cringle ${c.name} ${c.args}".trimEnd())
        appendLine()
        appendLine(c.summary)
        if (c.options.isNotEmpty()) {
            appendLine()
            appendLine("options:")
            for (o in c.options) {
                val label = if (o.takesValue) "--${o.name} ${o.placeholder}" else "--${o.name}"
                appendLine("  ${label.padEnd(28)}${o.description}")
            }
        }
    }.trimEnd()
}
