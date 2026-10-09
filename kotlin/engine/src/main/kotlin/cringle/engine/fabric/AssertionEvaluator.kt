// SPDX-License-Identifier: Apache-2.0

package cringle.engine.fabric

import cringle.packaging.Assertion
import cringle.packaging.BlockRunning
import cringle.packaging.FabricRunning
import cringle.packaging.NoErrors
import cringle.packaging.TetherFlow
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** What an assertion says about the fabric right now: [UNKNOWN] when it cannot be judged yet (the fabric is not running, or the window is not full). */
public enum class AssertionState { OK, VIOLATED, UNKNOWN }

/** The result of one assertion: [id] (its name, or type and target), [since] is the time of the last change of [state]. */
public data class AssertionResult(
    val id: String,
    val type: String,
    val state: AssertionState,
    val since: Instant,
    val detail: String,
)

/** What the evaluator looks at: the state of the fabric and its blocks and the counters of the metrics (#187). */
public data class AssertionInput(
    val fabric: FabricState,
    val blocks: Map<String, BlockState>,
    /** Cumulative number of messages per local tether id (`TetherStats.messages`). */
    val tetherMessages: Map<String, Long>,
    /** Cumulative number of failures of the blocks and tethers of the fabric. */
    val errors: Long,
)

/**
 * Evaluates the assertions of a blueprint (Architecture 16.3) one snapshot after the other. It has no thread of its own: the
 * caller calls [evaluate] regularly, so the windows of `tether-flow` and the quiet time of `no-errors` are measured between the calls.
 *
 * - `fabric-running`: the fabric is `RUNNING`. `block-running`: the block is `RUNNING`.
 * - `tether-flow`: the message counter of the tether rose by at least `min` within the last `perSeconds` seconds; [AssertionState.UNKNOWN]
 *   until the window has elapsed once.
 * - `no-errors`: no error was counted during the last [ERROR_QUIET] (a crash that is long ago does not violate it forever).
 * - Nothing is judged while the fabric is created, starting, stopping or stopped: all results are [AssertionState.UNKNOWN].
 */
public class AssertionEvaluator(private val assertions: List<Assertion>, private val clock: Clock = Clock.systemUTC()) {
    private class Sample(val at: Instant, val count: Long)

    private val last = HashMap<Int, Pair<AssertionState, Instant>>()
    private val samples = HashMap<Int, ArrayDeque<Sample>>()
    private var lastErrors: Long? = null
    private var lastErrorRise: Instant? = null

    /** The id of [assertion]: its name, or the type with its block or tether. */
    public fun idOf(assertion: Assertion): String = assertion.name ?: when (assertion) {
        is FabricRunning, is NoErrors -> assertion.type
        is BlockRunning -> "${assertion.type}:${assertion.block}"
        is TetherFlow -> "${assertion.type}:${assertion.tether}"
    }

    /** Judges every assertion against [input]; the list has the order of the blueprint. */
    @Synchronized
    public fun evaluate(input: AssertionInput): List<AssertionResult> {
        val now = clock.instant()
        val judged = input.fabric !in NOT_JUDGED
        if (!judged) {
            samples.clear()
            lastErrors = null
            lastErrorRise = null
        } else {
            val previous = lastErrors
            if (previous != null && input.errors > previous) lastErrorRise = now
            lastErrors = input.errors
        }
        return assertions.mapIndexed { index, assertion ->
            val (state, detail) = if (judged) judge(index, assertion, input, now) else AssertionState.UNKNOWN to "the fabric is ${input.fabric.name.lowercase()}"
            val since = last[index]?.takeIf { it.first == state }?.second ?: now
            last[index] = state to since
            AssertionResult(idOf(assertion), assertion.type, state, since, detail)
        }
    }

    private fun judge(index: Int, assertion: Assertion, input: AssertionInput, now: Instant): Pair<AssertionState, String> = when (assertion) {
        is FabricRunning ->
            if (input.fabric == FabricState.RUNNING) AssertionState.OK to "the fabric is running"
            else AssertionState.VIOLATED to "the fabric is ${input.fabric.name.lowercase().replace('_', ' ')}"
        is BlockRunning -> {
            val state = input.blocks[assertion.block]
            when {
                state == null -> AssertionState.UNKNOWN to "block ${assertion.block} not found"
                state == BlockState.RUNNING -> AssertionState.OK to "block ${assertion.block} is running"
                else -> AssertionState.VIOLATED to "block ${assertion.block} is ${state.name.lowercase()}"
            }
        }
        is TetherFlow -> flow(index, assertion, input, now)
        is NoErrors -> {
            val rise = lastErrorRise
            if (rise != null && Duration.between(rise, now) < ERROR_QUIET) {
                AssertionState.VIOLATED to "an error was counted ${Duration.between(rise, now).seconds} s ago"
            } else {
                AssertionState.OK to "no error in the last ${ERROR_QUIET.seconds} s"
            }
        }
    }

    private fun flow(index: Int, assertion: TetherFlow, input: AssertionInput, now: Instant): Pair<AssertionState, String> {
        val count = input.tetherMessages[assertion.tether] ?: return AssertionState.UNKNOWN to "tether ${assertion.tether} not found"
        val window = Duration.ofSeconds(assertion.perSeconds)
        val history = samples.getOrPut(index) { ArrayDeque() }
        history.addLast(Sample(now, count))
        // keep the newest sample that is at least one window old as the base, and everything after it
        while (history.size > 1 && history[1].at <= now.minus(window)) history.removeFirst()
        val base = history.first()
        if (base.at > now.minus(window)) {
            return AssertionState.UNKNOWN to "collecting data (${Duration.between(base.at, now).seconds} of ${assertion.perSeconds} s)"
        }
        val delta = count - base.count
        return if (delta >= assertion.min) AssertionState.OK to "$delta messages in ${assertion.perSeconds} s (at least ${assertion.min})"
        else AssertionState.VIOLATED to "$delta of at least ${assertion.min} messages in ${assertion.perSeconds} s"
    }

    private companion object {
        val NOT_JUDGED = setOf(FabricState.CREATED, FabricState.STARTING, FabricState.STOPPING, FabricState.STOPPED)
        val ERROR_QUIET: Duration = Duration.ofSeconds(60)
    }
}
