// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import java.time.Duration
import java.util.concurrent.TimeUnit

/** Starts, stops and checks the Cringle services of an installation. */
internal interface ServiceController {
    /** The installed Cringle services that are active right now. */
    fun activeServices(): List<String>
    fun stop(name: String)
    fun start(name: String)
    fun restart(name: String)
    fun isActive(name: String): Boolean
}

/** systemd (Linux). */
internal class SystemdServiceController : ServiceController {
    override fun activeServices(): List<String> =
        run("systemctl", "list-units", "--type=service", "--state=active", "--no-legend", "--plain", "cringle-*")
            .lineSequence()
            .mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { name -> name.endsWith(".service") } }
            .toList()

    override fun stop(name: String) { run("systemctl", "stop", name) }
    override fun start(name: String) { run("systemctl", "start", name) }
    override fun restart(name: String) { run("systemctl", "restart", name) }
    override fun isActive(name: String): Boolean = exec("systemctl", "is-active", "--quiet", name).first == 0
}

/**
 * The Windows services of install.ps1. [command] runs `sc.exe` (replaceable in tests), [stopTimeout] bounds the wait for a stop: WinSW takes up to its
 * `stoptimeout` of 60 s to end the daemon, and until the service is `STOPPED` it refuses a start.
 */
internal class WindowsServiceController(
    private val command: (Array<String>) -> Pair<Int, String> = { exec(*it) },
    private val stopTimeout: Duration = Duration.ofSeconds(120),
    private val pollInterval: Duration = Duration.ofMillis(500),
) : ServiceController {
    override fun activeServices(): List<String> = listOf("cringle-daemon", "cringle-management").filter { isActive(it) }

    override fun stop(name: String) {
        command(arrayOf("sc.exe", "stop", name))
        val deadline = System.nanoTime() + stopTimeout.toNanos()
        while (true) {
            // `sc query` of a service that does not exist fails: nothing to wait for
            val (exit, output) = command(arrayOf("sc.exe", "query", name))
            if (exit != 0 || output.contains("STOPPED")) return
            if (System.nanoTime() >= deadline) break
            Thread.sleep(pollInterval.toMillis())
        }
        throw IllegalStateException("$name did not stop within ${stopTimeout.seconds} seconds")
    }

    override fun start(name: String) {
        val (exit, output) = command(arrayOf("sc.exe", "start", name))
        // 1056: the service is already running
        if (exit != 0 && exit != 1056) throw IllegalStateException("sc start $name failed (exit code $exit): ${output.trim()}")
    }

    override fun restart(name: String) { stop(name); start(name) }
    override fun isActive(name: String): Boolean = command(arrayOf("sc.exe", "query", name)).second.contains("RUNNING")
}

/** Runs a command and returns its exit code and output; throws if it does not end within 120 seconds. */
internal fun exec(vararg command: String): Pair<Int, String> {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(120, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        throw IllegalStateException("${command.joinToString(" ")} did not end within 120 seconds")
    }
    return process.exitValue() to output
}

/** Like [exec], but throws [IllegalStateException] when the command fails. */
private fun run(vararg command: String): String {
    val (code, output) = exec(*command)
    if (code != 0) throw IllegalStateException("${command.joinToString(" ")} failed with exit code $code: ${output.trim()}")
    return output
}
