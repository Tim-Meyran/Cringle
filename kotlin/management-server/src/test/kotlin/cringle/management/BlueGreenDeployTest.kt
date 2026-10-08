// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.engine.v1.FabricRuntimeState
import io.grpc.Status
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The update of a project (#226): the new fabrics run next to the old ones and replace them when they run; otherwise stop-then-start. */
@Tag("integration")
class BlueGreenDeployTest : ServiceTestBase() {
    private fun redeploy(project: String, blueGreen: Boolean = true) = runBlocking { core.deploy(project, "", true, false, blueGreen) }

    private fun states(): Map<String, FabricRuntimeState> = runBlocking { core.listFabrics(null, null).associate { it.info.fabricId.value to it.info.state } }

    @Test
    fun theNewFabricsRunNextToTheOldOnesAndTheColorsAlternate() {
        val first = redeploy("recorded-app")
        assertEquals("first deploy", first.strategy)
        assertEquals(setOf("recorded-app-app-1"), states().keys)

        // while the new fabric is being put in place the old one still runs
        val seenWhileDeploying = ArrayList<Map<String, FabricRuntimeState>>()
        core.beforeFabricDeploy = { seenWhileDeploying += states() }
        val second = redeploy("recorded-app")
        core.beforeFabricDeploy = {}
        assertEquals("blue-green", second.strategy)
        assertEquals(listOf(mapOf("recorded-app-app-1" to FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING)), seenWhileDeploying)
        assertEquals(mapOf("recorded-app-app-1b" to FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING), states())
        assertEquals(listOf("recorded-app-app-1b"), second.fabrics.map { it.info.fabricId.value })
        assertEquals(setOf("recorded-app-app-1b"), core.deployedFabrics().map { it.fabricId }.toSet())

        // and back to the first color
        assertEquals("blue-green", redeploy("recorded-app").strategy)
        assertEquals(mapOf("recorded-app-app-1" to FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING), states())
    }

    @Test
    fun aFailedUpdateLeavesTheOldFabricRunningAndNothingElse() {
        redeploy("recorded-app")
        core.beforeFabricDeploy = { throw ManagementException(Status.Code.ABORTED, "the new version cannot start") }
        val failure = assertThrows<ManagementException> { redeploy("recorded-app") }
        core.beforeFabricDeploy = {}
        assertTrue(failure.message!!.contains("the new version cannot start") && failure.message!!.contains("keep running"), failure.message)
        assertEquals(mapOf("recorded-app-app-1" to FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING), states())
        assertEquals(setOf("recorded-app-app-1"), core.deployedFabrics().map { it.fabricId }.toSet())
        // the next update works
        assertEquals("blue-green", redeploy("recorded-app").strategy)
    }

    @Test
    fun theRecordingModeIsCarriedToTheNewFabric() {
        redeploy("recorded-app")
        runBlocking { core.setRecording("recorded-app-app-1", true, null) }
        redeploy("recorded-app")
        // the old id has no recording mode any more, the new one has it
        val store = ManagementStore(dir.resolve("state.json")).load()
        assertEquals(listOf("recorded-app-app-1b"), store.recordings.map { it.fabricId })
    }

    @Test
    fun aServiceProviderIsUpdatedStopThenStartWithItsIdKept() {
        assertEquals("first deploy", redeploy("orders-service").strategy)
        val second = redeploy("orders-service")
        assertTrue(second.strategy.startsWith("stop-then-start") && second.strategy.contains("provides a service"), second.strategy)
        assertEquals(setOf("orders-service-service-1"), states().keys)
    }

    @Test
    fun aBlockWithAnExclusiveResourceIsUpdatedStopThenStart() {
        redeploy("excl-app")
        val second = redeploy("excl-app")
        assertTrue(second.strategy.startsWith("stop-then-start") && second.strategy.contains("exclusive resources") && second.strategy.contains("SERIAL 'COM3'"), second.strategy)
        assertEquals(setOf("excl-app-app-1"), states().keys)
    }

    @Test
    fun blueGreenCanBeSwitchedOffAndAStopThenStartGoesBackToTheFirstColor() {
        redeploy("recorded-app")
        redeploy("recorded-app") // now the second color
        assertEquals(setOf("recorded-app-app-1b"), states().keys)
        val off = redeploy("recorded-app", blueGreen = false)
        assertTrue(off.strategy.startsWith("stop-then-start") && off.strategy.contains("switched off"), off.strategy)
        assertEquals(setOf("recorded-app-app-1"), states().keys)
        assertFalse(states().values.any { it != FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING })
    }

    @Test
    fun withoutStartTheOldFabricsAreReplacedWithoutRunningBoth() {
        redeploy("recorded-app")
        val r = runBlocking { core.deploy("recorded-app", "", false, false, true) }
        assertTrue(r.strategy.contains("not started"), r.strategy)
        assertEquals(setOf("recorded-app-app-1"), states().keys)
    }
}
