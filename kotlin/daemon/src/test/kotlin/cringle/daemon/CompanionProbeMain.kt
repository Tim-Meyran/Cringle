// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

/** A stand-in for a companion program (started by `CompanionProcessTest`): prints a secret and a normal line, then ends or waits. */
fun main(args: Array<String>) {
    println("bootstrap-token=secret")
    println("hello from probe")
    System.out.flush()
    if ("stay" in args) Thread.sleep(120_000)
}
