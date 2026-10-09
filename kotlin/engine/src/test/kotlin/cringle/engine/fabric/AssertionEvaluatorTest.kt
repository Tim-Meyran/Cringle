// SPDX-License-Identifier: Apache-2.0

package cringle.engine.fabric

import cringle.packaging.BlockRunning
import cringle.packaging.FabricRunning
import cringle.packaging.NoErrors
import cringle.packaging.TetherFlow
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AssertionEvaluatorTest {
    private class ManualClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = now

        fun advance(seconds: Long) {
            now = now.plusSeconds(seconds)
        }
    }

    private val clock = ManualClock()

    private fun input(
        fabric: FabricState = FabricState.RUNNING,
        blocks: Map<String, BlockState> = mapOf("a" to BlockState.RUNNING),
        messages: Map<String, Long> = emptyMap(),
        errors: Long = 0,
    ) = AssertionInput(fabric, blocks, messages, errors)

    @Test
    fun fabricRunningIsOkWhileRunningAndViolatedWhenFailed() {
        val evaluator = AssertionEvaluator(listOf(FabricRunning()), clock)
        assertEquals(AssertionState.OK, evaluator.evaluate(input()).single().state)
        val failed = evaluator.evaluate(input(fabric = FabricState.FAILED)).single()
        assertEquals(AssertionState.VIOLATED, failed.state)
        assertEquals("fabric-running", failed.id)
        assertTrue(failed.detail.contains("failed"), failed.detail)
    }

    @Test
    fun blockRunningFollowsTheStateOfTheBlock() {
        val evaluator = AssertionEvaluator(listOf(BlockRunning("a"), BlockRunning("zzz")), clock)
        val ok = evaluator.evaluate(input())
        assertEquals("block-running:a", ok[0].id)
        assertEquals(AssertionState.OK, ok[0].state)
        assertEquals(AssertionState.UNKNOWN, ok[1].state)
        assertEquals("block zzz not found", ok[1].detail)
        val restarting = evaluator.evaluate(input(blocks = mapOf("a" to BlockState.RESTARTING)))
        assertEquals(AssertionState.VIOLATED, restarting[0].state)
        assertEquals("block a is restarting", restarting[0].detail)
    }

    @Test
    fun nothingIsJudgedWhileTheFabricIsNotRunningOrFailed() {
        val evaluator = AssertionEvaluator(listOf(FabricRunning(), BlockRunning("a"), NoErrors(), TetherFlow("a.out -> b.in", 1, 10)), clock)
        for (state in listOf(FabricState.CREATED, FabricState.STARTING, FabricState.STOPPING, FabricState.STOPPED)) {
            assertEquals(List(4) { AssertionState.UNKNOWN }, evaluator.evaluate(input(fabric = state)).map { it.state }, state.name)
        }
    }

    @Test
    fun tetherFlowIsUnknownUntilTheWindowIsFullThenFollowsTheCounter() {
        val tether = "a.out -> b.in"
        val evaluator = AssertionEvaluator(listOf(TetherFlow(tether, min = 5, perSeconds = 10)), clock)
        assertEquals(AssertionState.UNKNOWN, evaluator.evaluate(input(messages = mapOf(tether to 0))).single().state)
        clock.advance(5)
        val collecting = evaluator.evaluate(input(messages = mapOf(tether to 3))).single()
        assertEquals(AssertionState.UNKNOWN, collecting.state)
        assertEquals("collecting data (5 of 10 s)", collecting.detail)
        clock.advance(5)
        assertEquals(AssertionState.OK, evaluator.evaluate(input(messages = mapOf(tether to 8))).single().state)
        // 8 to 10 in the next window: too few
        clock.advance(10)
        val slow = evaluator.evaluate(input(messages = mapOf(tether to 10))).single()
        assertEquals(AssertionState.VIOLATED, slow.state)
        assertEquals("2 of at least 5 messages in 10 s", slow.detail)
        clock.advance(10)
        assertEquals(AssertionState.OK, evaluator.evaluate(input(messages = mapOf(tether to 30))).single().state)
        assertEquals(AssertionState.UNKNOWN, evaluator.evaluate(input(messages = emptyMap())).single().state)
    }

    @Test
    fun noErrorsIsViolatedByANewErrorAndRecoversAfterSixtySeconds() {
        val evaluator = AssertionEvaluator(listOf(NoErrors("quiet")), clock)
        val first = evaluator.evaluate(input(errors = 2)).single()
        assertEquals("quiet", first.id)
        // errors from before the first look do not count
        assertEquals(AssertionState.OK, first.state)
        clock.advance(5)
        assertEquals(AssertionState.VIOLATED, evaluator.evaluate(input(errors = 3)).single().state)
        clock.advance(59)
        assertEquals(AssertionState.VIOLATED, evaluator.evaluate(input(errors = 3)).single().state)
        clock.advance(1)
        assertEquals(AssertionState.OK, evaluator.evaluate(input(errors = 3)).single().state)
    }

    @Test
    fun sinceChangesOnlyWhenTheStateChanges() {
        val evaluator = AssertionEvaluator(listOf(FabricRunning()), clock)
        val start = clock.now
        assertEquals(start, evaluator.evaluate(input()).single().since)
        clock.advance(30)
        assertEquals(start, evaluator.evaluate(input()).single().since)
        clock.advance(30)
        val violated = evaluator.evaluate(input(fabric = FabricState.FAILED)).single()
        assertEquals(start.plus(Duration.ofSeconds(60)), violated.since)
        clock.advance(30)
        assertEquals(start.plus(Duration.ofSeconds(60)), evaluator.evaluate(input(fabric = FabricState.FAILED)).single().since)
    }
}
