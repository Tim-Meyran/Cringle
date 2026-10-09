// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import cringle.common.LocalTrust
import cringle.common.logging.CringleLogging
import cringle.engine.CringleHome
import java.nio.file.Paths
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory

private const val USAGE =
    "usage: daemon [--home <dir>] [--port <port>] [--router <host:port> | --combined] [--bind <loopback|all>] [--trust-local]\n" +
        "              [--with-management [<port>]] [--web-port <port>] [--with-repository [<port>]]"

/** Entry point of the daemon process. Exit code 2 signals invalid arguments. */
public fun main(args: Array<String>) {
    var home: java.nio.file.Path? = null
    var port = 0
    var router: String? = null
    var combined = false
    var trustLocal = false
    var managementPort: Int? = null
    var webPort: Int? = null
    var repositoryPort: Int? = null
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
    fun optionalPort(option: String, default: Int): Int =
        args.getOrNull(i + 1)?.takeIf { !it.startsWith("--") }?.let { i += 1; it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: fail("$option must be 1..65535") } ?: default
    while (i < args.size) {
        when (val option = args[i]) {
            "--home" -> home = Paths.get(value(option))
            "--port" -> port = value(option).toIntOrNull()?.takeIf { it in 0..65535 } ?: fail("--port must be 0..65535")
            "--router" -> router = value(option)
            "--combined" -> combined = true
            "--bind" -> bind = value(option)
            "--trust-local" -> trustLocal = true
            "--with-management" -> managementPort = optionalPort(option, 7500)
            "--web-port" -> webPort = value(option).toIntOrNull()?.takeIf { it in 1..65535 } ?: fail("--web-port must be 1..65535")
            "--with-repository" -> repositoryPort = optionalPort(option, 7600)
            else -> fail("unknown argument '$option'")
        }
        i += 1
    }
    if (router != null && combined) fail("--router and --combined exclude each other")
    if (webPort != null && managementPort == null) fail("--web-port needs --with-management")
    try {
        cringle.common.BindAddress.interfaceChoice(bind)
    } catch (e: IllegalArgumentException) {
        fail(e.message ?: "invalid --bind")
    }
    CringleLogging.init(CringleHome.resolve(home), "daemon", "main")
    val daemon = Daemon(CringleHome.resolve(home), port, router, combined, trustLocal = LocalTrust.enabled(trustLocal), companions = Companions(managementPort, webPort, repositoryPort), bindHost = bind)
    Runtime.getRuntime().addShutdownHook(Thread({ daemon.close() }, "daemon-shutdown"))
    daemon.start()
    LoggerFactory.getLogger("cringle.daemon").info("daemon started on port {}", daemon.port)
    println("daemon-port=${daemon.port}")
    System.out.flush()
    Thread.currentThread().join()
}
