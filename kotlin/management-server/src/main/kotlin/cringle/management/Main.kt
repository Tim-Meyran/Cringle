// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.common.LocalTrust
import cringle.common.TrustStore
import cringle.common.logging.CringleLogging
import cringle.engine.CringleHome
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory

private const val USAGE =
    "usage: management-server [--home <dir>] [--port <port>] [--repository <host:port>] [--repository-token <token>] " +
        "[--router <host:port>] [--machine <id>=<daemon host:port>]... [--cache-max-unused-days <n>] [--auth] [--web-port <port>] [--web-host <host>] [--bind <loopback|all|address>] [--trust-local]"

/** Entry point of the management server process. Exit code 2 signals invalid arguments. */
public fun main(args: Array<String>) {
    var home: Path? = null
    var port = 0
    var repository: String? = null
    var token: String? = null
    var router: String? = null
    val machines = ArrayList<Pair<String, String>>()
    var cacheDays: Long? = null
    var auth = false
    var trustLocal = false
    var webPort: Int? = null
    var webHost: String? = null
    var bind: String? = cringle.common.BindAddress.fromEnvironment()
    var i = 0
    fun fail(message: String): Nothing {
        System.err.println("error: $message")
        System.err.println(USAGE)
        exitProcess(2)
    }
    fun value(option: String): String {
        if (i + 1 >= args.size) fail("$option needs a value")
        i += 1
        return args[i]
    }
    while (i < args.size) {
        when (val option = args[i]) {
            "--home" -> home = Paths.get(value(option))
            "--port" -> port = value(option).toIntOrNull()?.takeIf { it in 0..65535 } ?: fail("--port must be 0..65535")
            "--repository" -> repository = value(option)
            "--repository-token" -> token = value(option)
            "--router" -> router = value(option)
            "--machine" -> {
                val v = value(option)
                if ('=' !in v) fail("--machine needs <id>=<host:port>")
                machines += v.substringBefore('=') to v.substringAfter('=')
            }
            "--cache-max-unused-days" -> cacheDays = value(option).toLongOrNull()?.takeIf { it >= 0 } ?: fail("--cache-max-unused-days must be a number >= 0")
            "--auth" -> auth = true
            "--trust-local" -> trustLocal = true
            "--web-port" -> webPort = value(option).toIntOrNull()?.takeIf { it in 0..65535 } ?: fail("--web-port must be 0..65535")
            "--web-host" -> webHost = value(option)
            "--bind" -> bind = value(option)
            else -> fail("unknown argument '$option'")
        }
        i += 1
    }
    try {
        cringle.common.BindAddress.socketAddress(bind, 0)
    } catch (e: IllegalArgumentException) {
        fail(e.message ?: "invalid --bind")
    }
    CringleLogging.init(CringleHome.resolve(home), "management", "main")
    val base = CringleHome.resolve(home).resolve("management")
    // the identity and the peers of the management server: every channel to a daemon, engine, repository or router is mTLS
    val identity = Identity.loadOrCreate(base, ComponentKind.MANAGEMENT.commonName("management"))
    val trustStore = TrustStore(base.resolve("trust.json"))
    val certificateWatcher = cringle.common.CertificateWatcher(identity).start()
    // --trust-local: the daemon, its router and the engines of this home are trusted as soon as their key files exist (docs/trust.md)
    val localSync = if (LocalTrust.enabled(trustLocal)) LocalTrustSync(CringleHome.resolve(home), trustStore).also { it.sync(force = true) } else null
    val core = ManagementCore(ManagementStore(base.resolve("state.json")), identity, trustStore, repository, token, router, beforeConnect = localSync?.let { sync -> { sync.sync() } })
    val known = kotlinx.coroutines.runBlocking { core.listMachines().map { it.record.id }.toSet() }
    for ((id, address) in machines) {
        if (id !in known) kotlinx.coroutines.runBlocking { core.addMachine(id, address, null, null) }
    }
    var users: UserManager? = null
    var bootstrapFile: BootstrapTokenFile? = null
    if (auth) {
        users = UserManager(FileUserStore(base.resolve("users.json")))
        // shown once, on the very first start; the file keeps it if the output is lost, until the first login with it
        bootstrapFile = BootstrapTokenFile(base.resolve("bootstrap-token"))
        users.bootstrap(bootstrapFile::write)?.let { println("bootstrap-token=$it") }
        if (bootstrapFile.exists()) System.err.println("the bootstrap token is also in ${bootstrapFile.file}; the file is deleted at the first login with that token")
    } else {
        System.err.println("WARNING: no --auth: everybody who can reach the port is administrator")
    }
    val server = ManagementServer(core, port, users, cacheMaxUnusedDays = cacheDays, onAuthenticated = { bootstrapFile?.used(it) }, webPort = webPort, webHost = webHost, bindHost = bind)
    Runtime.getRuntime().addShutdownHook(Thread({ certificateWatcher.close(); server.close() }, "management-shutdown"))
    server.start()
    LoggerFactory.getLogger("cringle.management").info("management server started on port {}", server.port)
    println("management-port=${server.port}")
    if (cringle.common.BindAddress.isOpen(bind)) {
        LoggerFactory.getLogger("cringle.management").warn("listening on {}, not only on the loopback interface: the server is reachable from the network", bind)
        println("listening=$bind")
    }
    // the fingerprint that CLI and WebUI pin the server to ('cringle login --fingerprint'); it does not change while the key stays
    println("fingerprint=${identity.publicKeyFingerprint}")
    server.web?.let { println("web-port=${it.port}") }
    System.out.flush()
    Thread.currentThread().join()
}
