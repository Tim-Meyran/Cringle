// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import cringle.common.logging.CringleLogging
import cringle.engine.CringleHome
import java.nio.file.Paths
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory

private const val USAGE = "usage: daemon [--home <dir>] [--port <port>] [--router <host:port> | --combined]"

/** Entry point of the daemon process. Exit code 2 signals invalid arguments. */
public fun main(args: Array<String>) {
    var home: java.nio.file.Path? = null
    var port = 0
    var router: String? = null
    var combined = false
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
            "--insecure-dev-mode" -> Unit // accepted and ignored: the daemon API is always mTLS (the switch goes away with #5)
            else -> fail("unknown argument '$option'")
        }
        i += 1
    }
    if (router != null && combined) fail("--router and --combined exclude each other")
    CringleLogging.init(CringleHome.resolve(home), "daemon", "main")
    val daemon = Daemon(CringleHome.resolve(home), port, router, combined)
    Runtime.getRuntime().addShutdownHook(Thread({ daemon.close() }, "daemon-shutdown"))
    daemon.start()
    LoggerFactory.getLogger("cringle.daemon").info("daemon started on port {}", daemon.port)
    println("daemon-port=${daemon.port}")
    System.out.flush()
    Thread.currentThread().join()
}
