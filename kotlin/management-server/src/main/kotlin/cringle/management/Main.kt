// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.engine.CringleHome
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private const val USAGE =
    "usage: management-server [--home <dir>] [--port <port>] [--repository <host:port>] [--repository-token <token>] " +
        "[--router <host:port>] [--machine <id>=<daemon host:port>]... [--cache-max-unused-days <n>] [--auth] --insecure-dev-mode"

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
            "--insecure-dev-mode" -> insecure = true
            else -> fail("unknown argument '$option'")
        }
        i += 1
    }
    if (!insecure) fail("secure (mTLS) operation is not available yet (issue #13); start with --insecure-dev-mode")
    System.err.println("WARNING: INSECURE DEV MODE - the connection is not encrypted (loopback only)")
    val base = CringleHome.resolve(home).resolve("management")
    val core = ManagementCore(ManagementStore(base.resolve("state.json")), repository, token, router)
    val known = kotlinx.coroutines.runBlocking { core.listMachines().map { it.record.id }.toSet() }
    for ((id, address) in machines) {
        if (id !in known) kotlinx.coroutines.runBlocking { core.addMachine(id, address, null, null) }
    }
    var users: UserManager? = null
    if (auth) {
        users = UserManager(FileUserStore(base.resolve("users.json")))
        // shown once, on the very first start
        users.bootstrap()?.let { println("bootstrap-token=$it") }
    } else {
        System.err.println("WARNING: no --auth: everybody who can reach the port is administrator")
    }
    val server = ManagementServer(core, port, users, cacheMaxUnusedDays = cacheDays)
    Runtime.getRuntime().addShutdownHook(Thread({ server.close() }, "management-shutdown"))
    server.start()
    println("management-port=${server.port}")
    System.out.flush()
    Thread.currentThread().join()
}
