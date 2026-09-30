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
) {
    /** Runs one command line and returns the exit code. */
    public fun run(args: List<String>): Int {
        var command: Command? = null
        try {
            var json = false
            var server: String? = null
            var home: String? = null
            var insecureOption = false
            val rest = ArrayList<String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "--json" -> json = true
                    a == "--insecure-dev-mode" -> insecureOption = true
                    a == "--server" -> server = args.getOrNull(++i) ?: throw UsageException("--server needs a value")
                    a.startsWith("--server=") -> server = a.removePrefix("--server=")
                    a == "--home" -> home = args.getOrNull(++i) ?: throw UsageException("--home needs a value")
                    a.startsWith("--home=") -> home = a.removePrefix("--home=")
                    else -> rest += a
                }
                i++
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
                stored.insecure,
            )
            // the connection is not encrypted until mTLS exists (issue #13), which has to be accepted explicitly
            val insecure = insecureOption || environment["CRINGLE_INSECURE_DEV_MODE"] == "1" || stored.insecure
            if (command.needsServer && effective.server == null) {
                throw UsageException("no server address: use --server host:port, CRINGLE_SERVER, or 'cringle login --server host:port'")
            }
            var connection: Connection? = null
            lateinit var env: Env
            env = Env(
                connection = {
                    connection ?: run {
                        env.requireInsecure()
                        Connection(effective.server!!, effective.token).also { connection = it }
                    }
                },
                profile = effective,
                profileFile = profileFile,
                readSecret = { stdin.bufferedReader().readLine() },
                save = { it.save(profileFile) },
                insecure = insecure,
                insecureOption = insecureOption,
                warn = { err.println("warning: $it") },
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
        appendLine("usage: cringle [--server host:port] [--json] [--home dir] [--insecure-dev-mode] <command> [options]")
        appendLine()
        appendLine("commands:")
        val width = COMMANDS.maxOf { "${it.name} ${it.args}".trim().length }
        for (c in COMMANDS) appendLine("  ${"${c.name} ${c.args}".trim().padEnd(width)}  ${c.summary}")
        appendLine()
        appendLine("global options:")
        appendLine("  --server host:port  address of the ManagementServer (default: CRINGLE_SERVER or the profile)")
        appendLine("  --json              print JSON instead of text")
        appendLine("  --home dir          Cringle home with the profile cli.json (default: CRINGLE_HOME or ~/.cringle)")
        appendLine("  --insecure-dev-mode accept the unencrypted connection (also CRINGLE_INSECURE_DEV_MODE=1, or stored by")
        appendLine("                      'cringle login --insecure-dev-mode'); without it the CLI does not connect")
        appendLine("  --help              show this help; 'cringle <command> --help' shows the options of a command")
        appendLine()
        appendLine("The profile <home>/cli.json holds the server address and the token that 'cringle login' stored; the token can be")
        appendLine("overridden with CRINGLE_TOKEN. Exit codes: 0 success, 1 the command failed, 2 the command line is wrong.")
        appendLine("The connection is not encrypted yet (client certificates follow with mTLS, issue #13), so every command that connects")
        appendLine("needs --insecure-dev-mode, CRINGLE_INSECURE_DEV_MODE=1 or a profile written with 'cringle login --insecure-dev-mode'.")
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
