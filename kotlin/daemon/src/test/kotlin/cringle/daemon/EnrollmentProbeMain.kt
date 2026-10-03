// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import java.nio.file.Files
import java.nio.file.Paths

/**
 * A stand-in for an engine process (started by `EngineSupervisorEnrollmentTest`): records what the daemon gave it
 * (the enrollment secret from the environment, its command line, whether the daemon had announced it before it
 * started) in `<home>/probe/<id>/`, reports a management port and waits.
 */
fun main(args: Array<String>) {
    val home = Paths.get(args[args.indexOf("--home") + 1])
    val id = args[args.indexOf("--id") + 1]
    val dir = Files.createDirectories(home.resolve("probe").resolve(id))
    Files.writeString(dir.resolve("announced-before-start"), Files.exists(home.resolve("announced-$id")).toString())
    Files.writeString(dir.resolve("secret"), System.getenv("CRINGLE_ENROLLMENT_SECRET") ?: "none")
    Files.writeString(dir.resolve("args"), args.joinToString(" "))
    println("management-port=4711")
    System.out.flush()
    Thread.sleep(120_000)
}
