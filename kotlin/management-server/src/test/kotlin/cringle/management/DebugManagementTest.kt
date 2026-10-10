// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.management.v1.GetDebugStateRequest
import cringle.management.v1.ResumeFabricRequest
import cringle.management.v1.SetBreakpointRequest
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Remote debugging through the ManagementServer (#323): a breakpoint holds the values of a tether, resume and step release them. */
@Tag("integration")
class DebugManagementTest : ServiceTestBase() {
    private val fabric = "recorded-app-app-1"
    private val tether = "c2.out -> s2.in"

    private fun state() = runBlocking { s.getDebugState(GetDebugStateRequest.newBuilder().setFabric(fabric).build()) }

    private fun breakpoint(on: Boolean, name: String = tether) =
        runBlocking { s.setBreakpoint(SetBreakpointRequest.newBuilder().setFabric(fabric).setTether(name).setEnabled(on).build()) }

    private fun resume(one: Boolean) = runBlocking { s.resumeFabric(ResumeFabricRequest.newBuilder().setFabric(fabric).setOne(one).build()).released }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 60_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out: $what\n" + diagnostics() }
            Thread.sleep(100)
        }
    }

    @Test
    fun aBreakpointHoldsTheValuesAndResumeAndStepReleaseThem() {
        deploy("recorded-app")
        assertTrue(state().breakpointsList.isEmpty() && state().heldList.isEmpty())
        breakpoint(true)
        await("a held value") { state().heldCount >= 1 }
        val held = state().heldList.first()
        assertEquals(tether, held.tether)
        assertEquals("c2.out", held.from)
        assertEquals("s2.in", held.to)
        assertTrue(held.payload.isNotEmpty())
        assertEquals(listOf(tether), state().breakpointsList)

        // step: the oldest value goes, the breakpoint stays, the next value is held
        val before = state().heldList.map { it.id }.toSet()
        assertEquals(1, resume(true))
        await("the next value is held") { state().heldList.any { it.id !in before } || state().heldCount >= before.size }
        assertEquals(listOf(tether), state().breakpointsList)

        // removing the breakpoint releases everything and holds nothing more
        breakpoint(false)
        await("nothing held") { state().heldCount == 0 }
        assertTrue(state().breakpointsList.isEmpty())
    }

    @Test
    fun anUnknownTetherAndAnUnknownFabricAreRefused() {
        deploy("recorded-app")
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows<StatusException> { breakpoint(true, "x.out -> y.in") }.status.code)
        val unknown = assertThrows<StatusException> { runBlocking { s.getDebugState(GetDebugStateRequest.newBuilder().setFabric("nope").build()) } }
        assertTrue(unknown.status.code == Status.Code.NOT_FOUND || unknown.status.code == Status.Code.INVALID_ARGUMENT, unknown.status.toString())
    }
}
