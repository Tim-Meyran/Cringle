// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import cringle.common.Identity
import cringle.common.TrustKind
import cringle.common.TrustStore
import cringle.engine.CringleHome
import java.nio.file.Paths
import kotlin.system.exitProcess

private const val USAGE = "usage: daemon [--home <dir>] [--port <port>] [--router <host:port> | --combined] --insecure-dev-mode"

/** Entry point of the daemon process. Exit code 2 signals invalid arguments. */
public fun main(args: Array<String>) {
    var home: java.nio.file.Path? = null
    var port = 0
    var router: String? = null
    var combined = false
    var insecure = false
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
            "--router" -> router = value(option)
            "--combined" -> combined = true
            "--insecure-dev-mode" -> insecure = true
            else -> fail("unknown argument '$option'")
        }
        i += 1
    }
    if (router != null && combined) fail("--router and --combined exclude each other")
    val daemon = if (insecure) {
        System.err.println("WARNING: INSECURE DEV MODE - daemon API is unauthenticated (loopback only)")
        Daemon(CringleHome.resolve(home), port, router, combined)
    } else {
        val homePath = CringleHome.resolve(home)
        val identity = Identity.loadOrCreate(homePath, "CN=daemon")
        val trustStore = TrustStore(homePath.resolve("trust-store"))
        Daemon(homePath, port, router, combined, identity = identity, trustStore = trustStore)
    }
    Runtime.getRuntime().addShutdownHook(Thread({ daemon.close() }, "daemon-shutdown"))
    daemon.start()
    println("daemon-port=${daemon.port}")
    System.out.flush()
    Thread.currentThread().join()
}
