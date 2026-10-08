// SPDX-License-Identifier: Apache-2.0

package cringle.repository

import cringle.common.CertificateWatcher
import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.common.LocalTrust
import cringle.common.LocalTrustScanner
import cringle.common.TrustKind
import cringle.common.TrustStore
import cringle.common.logging.CringleLogging
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory

private const val USAGE = "usage: repository [--home <dir>] [--port <port>] [--auth] [--trust-local]"

/** The Cringle home: [explicit], `CRINGLE_HOME` or `~/.cringle`. */
private fun resolveHome(explicit: Path?): Path =
    explicit ?: System.getenv("CRINGLE_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: Paths.get(System.getProperty("user.home"), ".cringle")

/**
 * A running repository process: the package repository in `<home>/repository/packages` as a gRPC server with mutual TLS on the
 * loopback interface, with the identity and the trust store in `<home>/repository`. With [trustLocal] the management server, the
 * engines and the Gradle plugin of the same home are trusted as soon as their key files exist ([LocalTrust]); the scan runs once a
 * second. With [auth] the calls need user tokens (`<home>/repository/users.json`); [bootstrapToken] is the token of the admin user
 * on the first start.
 */
public class RepositoryProgram(home: Path, port: Int = 0, auth: Boolean = false, trustLocal: Boolean = false) : AutoCloseable {
    private val base = home.resolve("repository")

    /** The identity of the repository. */
    public val identity: Identity = Identity.loadOrCreate(base, ComponentKind.REPOSITORY.commonName("repository"))

    /** The peers the repository accepts. */
    public val trustStore: TrustStore = TrustStore(base.resolve("trust.json"))

    private val watcher = CertificateWatcher(identity)
    private val scanner = if (LocalTrust.enabled(trustLocal)) {
        LocalTrustScanner(
            home,
            trustStore,
            listOf(
                LocalTrustScanner.Component("management", "management", TrustKind.COMPONENT),
                LocalTrustScanner.Component(".", "gradle-plugin", TrustKind.COMPONENT),
            ),
            engines = true,
        )
    } else null
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "repository-local-trust").apply { isDaemon = true } }
    private val users: UserManager? = if (auth) UserManager(FileUserStore(base.resolve("users.json"))) else null

    /** The token of the admin user, shown once on the first start with auth; `null` otherwise. */
    public val bootstrapToken: String? = users?.bootstrap()

    /** The server. */
    public val server: RepositoryServer

    init {
        scanner?.sync(force = true)
        server = RepositoryServer(
            PackageRepository(base.resolve("packages")),
            port,
            users,
            Files.createDirectories(base.resolve("upload")),
            RepositoryTls(identity, trustStore),
        )
    }

    /** The port of the server; valid after [start]. */
    public val port: Int get() = server.port

    /** Starts the server, the check of the certificate and the scan of the local trust. */
    public fun start(): RepositoryProgram {
        server.start()
        watcher.start()
        scanner?.let { timer.scheduleWithFixedDelay({ runCatching { it.sync(force = true) } }, 1, 1, TimeUnit.SECONDS) }
        return this
    }

    override fun close() {
        timer.shutdownNow()
        watcher.close()
        server.stop()
    }
}

/** Entry point of the repository process. Exit code 2 signals invalid arguments. */
public fun main(args: Array<String>) {
    var home: Path? = null
    var port = 0
    var auth = false
    var trustLocal = false
    var i = 0
    fun fail(message: String): Nothing {
        System.err.println("error: $message")
        System.err.println(USAGE)
        exitProcess(2)
    }
    while (i < args.size) {
        when (val option = args[i]) {
            "--home" -> home = Paths.get(args.getOrNull(++i) ?: fail("$option needs a value"))
            "--port" -> port = args.getOrNull(++i)?.toIntOrNull()?.takeIf { it in 0..65535 } ?: fail("--port must be 0..65535")
            "--auth" -> auth = true
            "--trust-local" -> trustLocal = true
            else -> fail("unknown argument '$option'")
        }
        i += 1
    }
    val homeDir = resolveHome(home)
    CringleLogging.init(homeDir, "repository", "main")
    val program = RepositoryProgram(homeDir, port, auth, trustLocal)
    Runtime.getRuntime().addShutdownHook(Thread({ program.close() }, "repository-shutdown"))
    program.start()
    LoggerFactory.getLogger("cringle.repository").info("repository started on port {}", program.port)
    program.bootstrapToken?.let { println("bootstrap-token=$it") }
    println("fingerprint=${program.identity.publicKeyFingerprint}")
    println("repository-port=${program.port}")
    System.out.flush()
    Thread.currentThread().join()
}
