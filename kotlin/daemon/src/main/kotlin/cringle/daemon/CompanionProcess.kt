// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import java.io.BufferedWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory

/**
 * A program that the daemon starts and keeps running next to the engines: the management server and the repository of a
 * machine that runs everything itself (`--with-management`, `--with-repository`). It is a JVM with the class path of
 * [command], started with [mainClass] and [arguments] and the environment [environment]; its output goes to
 * `<logs>/<name>.out.log` and `<name>.err.log`, except for the lines `bootstrap-token=...`, which are a secret and stay in the
 * file the program writes for the operator.
 *
 * When the program ends without [close], it is started again after a delay that grows with every start that was quick
 * (1 s, 2 s, 4 s, up to [maxDelay]); a start that ran for [stableAfter] resets it. The daemon does not give up: a program
 * that cannot start because of its configuration shows it in its log.
 */
internal class CompanionProcess(
    private val name: String,
    private val command: EngineCommand,
    private val mainClass: String,
    private val arguments: List<String>,
    private val environment: Map<String, String>,
    private val logs: Path,
    private val stableAfter: Duration = Duration.ofSeconds(60),
    private val maxDelay: Duration = Duration.ofSeconds(30),
    private val firstDelay: Duration = Duration.ofSeconds(1),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger("cringle.daemon.companion")

    @Volatile
    private var stopped = false

    @Volatile
    private var process: Process? = null
    private var thread: Thread? = null

    @Volatile
    private var pumpThread: Thread? = null

    /** The size of the error log when the program was started the last time: what it wrote after that is the reason it ended. */
    @Volatile
    private var errorFrom = 0L
    private val starts = AtomicInteger()

    /** How often the program was started, the first start included. */
    val startCount: Int get() = starts.get()

    /** Whether the program runs right now. */
    val isRunning: Boolean get() = process?.isAlive == true

    /** Starts the program and the thread that keeps it running; a second call does nothing. */
    @Synchronized
    fun start() {
        if (thread != null) return
        Files.createDirectories(logs)
        thread = Thread({ supervise() }, "companion-$name").apply {
            isDaemon = true
            start()
        }
    }

    private fun supervise() {
        var quick = 0
        while (!stopped) {
            val began = System.nanoTime()
            val started = launch()
            if (started != null) {
                process = started
                if (stopped) {
                    // close() ran while the program was being started: it did not see the new process, so it is ended here
                    started.destroyForcibly()
                    break
                }
                val code = try {
                    started.waitFor()
                } catch (e: InterruptedException) {
                    started.destroyForcibly()
                    break
                }
                if (!stopped) log.warn("{} ended with exit code {}", name, code)
                // the reason is in the error output of the program: it goes to the log of the daemon too, so that `journalctl -u cringle-daemon` shows it
                if (!stopped && code != 0) errorTail()?.let { log.warn("{} said: {}", name, it) }
            }
            if (stopped) break
            quick = if (Duration.ofNanos(System.nanoTime() - began) >= stableAfter) 0 else quick + 1
            val delay = minOf(maxDelay.toMillis(), firstDelay.toMillis() shl (minOf(quick, 16).coerceAtLeast(1) - 1))
            log.info("{} is started again in {} ms", name, delay)
            try {
                Thread.sleep(delay)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun launch(): Process? {
        val cmd = listOf(command.java) + command.jvmArgs + listOf("-cp", command.classPath, mainClass) + arguments
        val errorLog = logs.resolve("$name.err.log")
        errorFrom = runCatching { Files.size(errorLog) }.getOrDefault(0L)
        val builder = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.appendTo(errorLog.toFile()))
        builder.environment().putAll(environment)
        val started = try {
            builder.start()
        } catch (e: IOException) {
            log.error("{} cannot be started: {}", name, e.message)
            return null
        }
        starts.incrementAndGet()
        log.info("{} started (process {})", name, started.pid())
        pumpThread = Thread({ pump(started) }, "companion-$name-out").apply {
            isDaemon = true
            start()
        }
        return started
    }

    /** The last lines that the program wrote to its error output since it was started the last time, `null` if there are none. */
    internal fun errorTail(): String? = try {
        val file = logs.resolve("$name.err.log")
        val bytes = Files.readAllBytes(file)
        val from = minOf(errorFrom, bytes.size.toLong()).toInt()
        tailOf(String(bytes, from, bytes.size - from, Charsets.UTF_8))
    } catch (e: IOException) {
        null
    }

    /** Copies the output to the log file, without the lines that are secrets. */
    private fun pump(started: Process) {
        try {
            Files.newBufferedWriter(logs.resolve("$name.out.log"), StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { out: BufferedWriter ->
                started.inputStream.bufferedReader().forEachLine { line ->
                    if (!line.startsWith("bootstrap-token=")) {
                        out.write(line)
                        out.newLine()
                        out.flush()
                    }
                }
            }
        } catch (e: IOException) {
            // the program ended or the log cannot be written; the supervision goes on
        }
    }

    /** Stops the program and the supervision; the program gets a few seconds to end by itself. */
    override fun close() {
        stopped = true
        val p = process
        p?.destroy()
        thread?.interrupt()
        if (p != null && !p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly()
        thread?.join(2000)
        pumpThread?.join(2000)
    }
}

/**
 * The last [lines] lines of [text] (blank ones left out) in one line, separated by ` | ` and not longer than 1500 characters; `null` if there are none.
 * The lines of a stack trace come in reverse for the reader: the exception is the last `Exception in thread` line, so those are kept first.
 */
internal fun tailOf(text: String, lines: Int = 10): String? {
    val all = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (all.isEmpty()) return null
    val exception = all.lastOrNull { it.startsWith("Exception in thread") || it.startsWith("Error:") }
    val tail = all.takeLast(lines)
    val chosen = if (exception != null && exception !in tail) listOf(exception) + tail else tail
    return chosen.joinToString(" | ").takeLast(1500)
}

/**
 * The programs a daemon runs next to its engines (`--with-management`, `--with-repository`): the management server on
 * [managementPort] (with user logins, the web interface on [webPort] if given, and this machine as machine `local`) and the
 * repository on [repositoryPort]. They are started as JVMs with the class path of [command] and trust each other and the daemon
 * by their key files in the home (`LocalTrust`). `null` leaves a program out.
 */
public data class Companions(
    val managementPort: Int? = null,
    val webPort: Int? = null,
    val repositoryPort: Int? = null,
    val command: EngineCommand = EngineCommand(),
    /** The public address of the web interface (`--web-url`), `null` for the `Host` header (#314). */
    val webUrl: String? = null,
) {
    /** Whether the daemon runs at least one program. */
    val any: Boolean get() = managementPort != null || repositoryPort != null
}
