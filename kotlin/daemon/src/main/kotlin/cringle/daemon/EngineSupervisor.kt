// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import cringle.engine.v1.ConfigureRequest
import cringle.engine.v1.EngineManagementServiceGrpc
import cringle.router.v1.PrepareEngineRequest
import cringle.router.v1.RegistryServiceGrpc
import io.grpc.ManagedChannelBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** How to start an engine process. By default: the JVM and class path of the daemon itself. */
public data class EngineCommand(
    val java: String = File(System.getProperty("java.home"), "bin/java").path,
    val classPath: String = System.getProperty("java.class.path"),
    val mainClass: String = "cringle.engine.MainKt",
    val jvmArgs: List<String> = emptyList(),
)

/** State of an engine process as the daemon sees it. */
public enum class ProcessState { STOPPED, STARTING, RUNNING, STOPPING, CRASHED }

/** A snapshot of one engine. */
public data class EngineSnapshot(
    val id: String,
    val name: String,
    val state: ProcessState,
    val pid: Long,
    val managementPort: Int,
    val startedAt: Instant?,
    val exitCode: Int,
    val lastError: String,
)

/**
 * Starts engine processes and watches them. An engine that ends without being asked to is reported as
 * [ProcessState.CRASHED] with its exit code; it is not restarted automatically, that decision belongs to the
 * ManagementServer. Output of the processes goes to `<home>/daemon/logs/<id>.out.log` and `.err.log`.
 */
public class EngineSupervisor(
    private val home: Path,
    private val command: EngineCommand = EngineCommand(),
    private val routerAddress: () -> String? = { null },
    private val startTimeout: Duration = Duration.ofSeconds(90),
    private val stopTimeout: Duration = Duration.ofSeconds(30),
    /** Called after the daemon stopped an engine process, with the engine id and its environment. */
    private val onStopped: (String, Map<String, String>) -> Unit = { id, _ -> },
    /** Appends one line to a log file; replaced in tests to make writing fail. */
    private val appendLog: (Path, String) -> Unit = { file, line ->
        Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    },
    /** Where the supervisor reports problems of its own (at most once per engine process for a failing log file). */
    private val onWarning: (String) -> Unit = { System.err.println("WARNING: $it") },
    /** Secure random number generator for enrollment secrets. */
    private val secureRandom: SecureRandom = SecureRandom(),
) : AutoCloseable {
    private class Managed(val id: String, var name: String) {
        val lock = ReentrantLock()

        @Volatile var state = ProcessState.STOPPED

        @Volatile var process: Process? = null

        @Volatile var port = 0

        @Volatile var startedAt: Instant? = null

        @Volatile var exitCode = 0

        @Volatile var lastError = ""

        @Volatile var pump: Thread? = null

        @Volatile var environment: Map<String, String>? = null
    }

    private val engines = LinkedHashMap<String, Managed>()
    private val logs: Path = home.resolve("daemon").resolve("logs")

    /** Adds an engine to supervise (state STOPPED). */
    public fun add(id: String, name: String) {
        synchronized(engines) { engines[id] = Managed(id, name) }
    }

    /** Stops [id] if needed and forgets it. */
    public fun remove(id: String) {
        val m = find(id)
        stop(id)
        synchronized(engines) { engines.remove(m.id) }
    }

    private fun find(id: String): Managed =
        synchronized(engines) { engines[id] } ?: throw DaemonException(DaemonError.NOT_FOUND, "engine '$id' is not registered")

    private fun snapshot(m: Managed) = EngineSnapshot(
        m.id, m.name, m.state, m.process?.pid() ?: 0, m.port, m.startedAt, m.exitCode, m.lastError,
    )

    /** The current state of [id]. */
    public fun get(id: String): EngineSnapshot = snapshot(find(id))

    /** The current state of all engines. */
    public fun list(): List<EngineSnapshot> = synchronized(engines) { engines.values.map { snapshot(it) } }

    /** Starts the engine process and waits until its management API is up. */
    public fun start(id: String): EngineSnapshot {
        val m = find(id)
        m.lock.withLock {
            if (m.state == ProcessState.RUNNING || m.state == ProcessState.STARTING) {
                throw DaemonException(DaemonError.FAILED_PRECONDITION, "engine '$id' is already ${m.state.name.lowercase()}")
            }
            Files.createDirectories(logs)
            m.state = ProcessState.STARTING
            m.lastError = ""
            m.exitCode = 0
            
            // Generate enrollment secret if router is configured
            val enrollmentSecret = if (routerAddress() != null) {
                val secret = ByteArray(32)
                secureRandom.nextBytes(secret)
                secret
            } else {
                null
            }
            
            val cmd = listOf(command.java) + command.jvmArgs + listOf(
                "-cp", command.classPath, command.mainClass,
                "--id", m.id, "--name", m.name, "--home", home.toString(), "--insecure-dev-mode",
            )
            val builder = ProcessBuilder(cmd)
                .redirectError(ProcessBuilder.Redirect.appendTo(logs.resolve("${m.id}.err.log").toFile()))
            builder.environment()["CRINGLE_HOME"] = home.toString()
            
            // Pass enrollment secret to engine via environment
            enrollmentSecret?.let { secret ->
                builder.environment()["CRINGLE_ENROLLMENT_SECRET"] = secret.joinToString("\\") { byte ->
                    "%02x".format(byte)
                }
            }
            val process = try {
                builder.start()
            } catch (e: Exception) {
                fail(m, "cannot start engine process: ${e.message}")
            }
            // Capture the process environment for onStopped callback
            m.environment = builder.environment()
            m.process = process
            val port = CompletableFuture<Int>()
            m.pump = Thread({ pumpOutput(m, process, port) }, "engine-out-${m.id}").apply { isDaemon = true }
            m.pump?.start()
            process.onExit().thenAccept { p -> onExit(m, p) }
            val managementPort = try {
                port.get(startTimeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                process.destroyForcibly()
                fail(m, "engine '$id' did not report its management port within $startTimeout")
            } catch (e: Exception) {
                val code = runCatching { process.waitFor(5, TimeUnit.SECONDS); process.exitValue() }.getOrDefault(-1)
                fail(m, "engine '$id' ended with exit code $code before it was ready (see ${logs.resolve("${m.id}.err.log")})")
            }
            m.port = managementPort
            m.startedAt = Instant.now()
            m.state = ProcessState.RUNNING
            configureRouter(m, enrollmentSecret)
            return snapshot(m)
        }
    }

    private fun fail(m: Managed, message: String): Nothing {
        m.state = ProcessState.CRASHED
        m.lastError = message
        m.port = 0
        throw DaemonException(DaemonError.FAILED_PRECONDITION, message)
    }

    /**
     * Reads the standard output of the engine process until it ends. A failure to write the log (disk full, file
     * locked) is reported once and the lines are discarded from then on, but the stream is always read to its end: an
     * engine whose output nobody reads blocks as soon as the pipe is full.
     */
    private fun pumpOutput(m: Managed, process: Process, port: CompletableFuture<Int>) {
        var logFailed = false
        val logFile = logs.resolve("${m.id}.out.log")
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!port.isDone && line.startsWith("management-port=")) {
                        line.removePrefix("management-port=").trim().toIntOrNull()?.let { port.complete(it) }
                    }
                    if (logFailed) continue
                    try {
                        appendLog(logFile, line)
                    } catch (e: Exception) {
                        logFailed = true
                        runCatching {
                            onWarning("engine '${m.id}': cannot write $logFile (${e.message}); its output is discarded until the process ends")
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // stream closed
        } finally {
            port.completeExceptionally(IllegalStateException("engine output ended"))
        }
    }

    private fun onExit(m: Managed, p: Process) {
        if (m.process !== p) return
        val code = p.exitValue()
        m.exitCode = code
        m.port = 0
        if (m.state == ProcessState.STOPPING) {
            m.state = ProcessState.STOPPED
        } else if (m.state == ProcessState.RUNNING) {
            m.state = ProcessState.CRASHED
            m.lastError = "engine process ended unexpectedly with exit code $code"
        }
    }

    private fun configureRouter(m: Managed, enrollmentSecret: ByteArray?) {
        val router = routerAddress() ?: return
        val channel = ManagedChannelBuilder.forAddress("127.0.0.1", m.port).usePlaintext().build()
        try {
            enrollmentSecret?.let { secret ->
                val secretHash = MessageDigest.getInstance("SHA-256").digest(secret)
                
                // Call PrepareEngine to register the secret hash
                val prepareEngineStub = RegistryServiceGrpc.newBlockingStub(channel).withDeadlineAfter(30, TimeUnit.SECONDS)
                 prepareEngineStub.prepareEngine(
                     PrepareEngineRequest.newBuilder()
                         .setEngineId(cringle.common.v1.EngineId.newBuilder().setValue(m.id))
                         .setEnrollmentSecretHash(com.google.protobuf.ByteString.copyFrom(secretHash))
                         .build()
                 )
            }
            
            // Configure the router address
            EngineManagementServiceGrpc.newBlockingStub(channel).withDeadlineAfter(30, TimeUnit.SECONDS)
                .configure(ConfigureRequest.newBuilder().setRouterAddress(router).build())
        } catch (e: Exception) {
            m.lastError = "engine is running but could not be configured: ${e.message}"
        } finally {
            channel.shutdownNow()
        }
    }

    /** Stops the engine process gracefully, forcibly after the timeout. Does nothing if there is no process. */
    public fun stop(id: String): EngineSnapshot {
        val m = find(id)
        m.lock.withLock {
            val p = m.process
            if (p == null || !p.isAlive) {
                if (m.state != ProcessState.CRASHED) m.state = ProcessState.STOPPED
                return snapshot(m)
            }
            m.state = ProcessState.STOPPING
            p.destroy()
            if (!p.waitFor(stopTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                p.waitFor(10, TimeUnit.SECONDS)
            }
            m.exitCode = if (p.isAlive) m.exitCode else p.exitValue()
            // release the log files before returning (matters on Windows, where open files cannot be deleted)
            m.pump?.join(5_000)
            m.state = ProcessState.STOPPED
            m.port = 0
            m.lastError = ""
            runCatching { onStopped(m.id, m.environment ?: emptyMap()) }
            return snapshot(m)
        }
    }

    /** Stops (if running) and starts the engine. */
    public fun restart(id: String): EngineSnapshot {
        stop(id)
        return start(id)
    }

    /** Stops all engine processes. */
    override fun close() {
        list().forEach { runCatching { stop(it.id) } }
    }
}
