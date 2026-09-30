// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import java.nio.file.Files
import java.nio.file.Paths

/**
 * A stand-in for an engine process (started by `EngineSupervisorTest`): reports a management port, writes much more to
 * standard output than a pipe holds and then leaves a file in its home. The file only appears if somebody reads the
 * output, because a process blocks on a full pipe.
 */
fun main(args: Array<String>) {
    val home = Paths.get(args[args.indexOf("--home") + 1])
    println("management-port=4711")
    val filler = "x".repeat(200)
    repeat(3_000) { println("line $it $filler") }
    System.out.flush()
    Files.writeString(home.resolve("output-written"), "done")
    Thread.sleep(120_000)
}
