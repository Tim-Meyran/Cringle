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
                val code = try {
                    started.waitFor()
                } catch (e: InterruptedException) {
                    break
                }
                if (!stopped) log.warn("{} ended with exit code {}", name, code)
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
        val builder = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.appendTo(logs.resolve("$name.err.log").toFile()))
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
) {
    /** Whether the daemon runs at least one program. */
    val any: Boolean get() = managementPort != null || repositoryPort != null
}
