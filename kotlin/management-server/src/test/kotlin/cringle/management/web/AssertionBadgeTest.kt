// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.engine.v1.AssertionState
import cringle.engine.v1.AssertionStatus
import cringle.engine.v1.FabricInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AssertionBadgeTest {
    private fun info(vararg states: AssertionState) = FabricInfo.newBuilder().addAllAssertions(states.map { AssertionStatus.newBuilder().setState(it).build() }).build()

    @Test
    fun theBadgeCountsTheViolatedAssertionsAndIsEmptyOtherwise() {
        assertEquals("", violatedBadge(info()).toString())
        assertEquals("", violatedBadge(info(AssertionState.ASSERTION_STATE_OK, AssertionState.ASSERTION_STATE_UNKNOWN)).toString())
        val badge = violatedBadge(info(AssertionState.ASSERTION_STATE_VIOLATED, AssertionState.ASSERTION_STATE_OK, AssertionState.ASSERTION_STATE_VIOLATED)).toString()
        assertTrue(badge.contains("2 violated") && badge.contains("bad"), badge)
    }
}
