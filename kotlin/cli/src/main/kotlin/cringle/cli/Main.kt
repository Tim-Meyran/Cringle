// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import kotlin.system.exitProcess

/** Entry point of the `cringle` executable. */
public fun main(args: Array<String>) {
    cringle.common.logging.CringleLogging.initCommandLine()
    exitProcess(Cli(System.out, System.err).run(args.toList()))
}
