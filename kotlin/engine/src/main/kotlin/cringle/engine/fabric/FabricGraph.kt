// SPDX-License-Identifier: Apache-2.0

package cringle.engine.fabric

import cringle.contract.TetherType

/** A port of a block as the monitor shows it. [count] is the number of tethers of a VarArg port, 1 for a plain port. */
public data class GraphPort(val name: String, val out: Boolean, val tetherTypes: List<TetherType>, val varArg: Boolean, val count: Int)

/** A block of a running fabric with its state and its numbers. */
public data class GraphBlock(
    val id: String,
    val block: String,
    val state: BlockState,
    val restarts: Int,
    val lastError: String,
    val errors: Long,
    val isolation: String,
    val ports: List<GraphPort>,
)

/**
 * A tether of a running fabric: [from] and [to] are `block.port[index]` (an end on another engine is named with its address), [id] is the name that the
 * debugger and the data warehouse use. The numbers are cumulative; [capturable] says whether the monitor can show the values.
 */
public data class GraphTether(
    val id: String,
    val type: TetherType,
    val fromBlock: String,
    val fromPort: String,
    val fromIndex: Int,
    val toBlock: String,
    val toPort: String,
    val toIndex: Int,
    val remote: Boolean,
    val messages: Long,
    val bytes: Long,
    val errors: Long,
    val lastActivityMillis: Long,
    val capturable: Boolean,
)

/** What the monitor draws: the blocks and tethers of a fabric with their state and activity. */
public data class FabricGraph(val fabricId: String, val blueprint: String, val state: FabricState, val failure: String, val blocks: List<GraphBlock>, val tethers: List<GraphTether>)

/** The recent values of one tether for the monitor: [capturable] is false for a tether whose values are not copied (TCP, serial); newest first. */
public data class TrafficResult(val capturable: Boolean, val records: List<TrafficMonitor.Record>)
