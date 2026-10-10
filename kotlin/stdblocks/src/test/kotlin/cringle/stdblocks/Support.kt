// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import cringle.testkit.BlockTestHarness
import cringle.testkit.TestDriverSet

/** A block of the library in the test harness with ports made from its definition. */
internal class Run(val harness: BlockTestHarness) {
    suspend fun send(port: String, value: Any) = harness.sendMessage(port, value)

    fun out(port: String): List<Any> = harness.ports.tether(port).sentMessages
}

internal suspend fun std(name: String, config: Map<String, Any?> = emptyMap(), body: suspend Run.() -> Unit) {
    val harness = BlockTestHarness.forProvider(StdBlockProvider(), name, TestDriverSet(), config)
    harness.runLifecycle { Run(harness).body() }
}
