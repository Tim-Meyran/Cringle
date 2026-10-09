// SPDX-License-Identifier: Apache-2.0

package cringle.management

import io.grpc.Status
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Going back to an earlier version of a project (#228). Versions 2 and 3 of `acme-mig` have an update and a downgrade processor, version 1 has none. */
@Tag("integration")
class RollbackTest : ServiceTestBase() {
    private val data get() = dir.resolve("home/data/mig-app/app/1/c1")

    private fun deploy(version: String) = runBlocking { core.deploy("mig-app", version, true, false, true) }

    private fun runningVersion() = core.deployedFabrics().filter { it.project == "mig-app" }.map { it.version }.distinct().single()

    @Test
    fun nothingToGoBackToAfterTheFirstDeploy() {
        deploy("1.0.0")
        val e = assertThrows<ManagementException> { runBlocking { core.rollback("mig-app") } }
        assertTrue(e.message!!.contains("no earlier version"), e.message)
        assertEquals(emptyList<String>(), core.rollbackTargets("mig-app"))
        assertThrows<ManagementException> { runBlocking { core.rollback("recorded-app") } } // not deployed
    }

    @Test
    fun aVersionThatWasNeverDeployedIsRefused() {
        deploy("1.0.0")
        Files.writeString(data.resolve("fixed"), "")
        deploy("2.0.0")
        val e = assertThrows<ManagementException> { runBlocking { core.rollback("mig-app", "3.0.0") } }
        assertEquals(Status.Code.NOT_FOUND, e.code)
        assertTrue(e.message!!.contains("can go back to: 1.0.0"), e.message)
    }

    @Test
    fun aMissingDowngradeProcessorRefusesBeforeAnythingIsTouched() {
        deploy("1.0.0")
        Files.writeString(data.resolve("fixed"), "")
        deploy("2.0.0")
        val e = assertThrows<ManagementException> { runBlocking { core.rollback("mig-app") } }
        assertTrue(e.message!!.contains("plugin acme-mig 2.0.0 migrates data on update, but 1.0.0 has no downgrade processor"), e.message)
        assertEquals("2.0.0", runningVersion())
        assertTrue(Files.readString(data.resolve("migrated")).startsWith("1.0.0->2.0.0"))
    }

    @Test
    fun rollbackRunsTheDowngradeProcessorWithTheLockOfTheEarlierVersion() {
        deploy("1.0.0")
        Files.writeString(data.resolve("fixed"), "")
        deploy("2.0.0")
        deploy("3.0.0")
        assertEquals(listOf("2.0.0", "1.0.0"), core.rollbackTargets("mig-app"))
        val r = runBlocking { core.rollback("mig-app") }
        assertEquals("2.0.0", r.version)
        assertTrue(r.strategy.startsWith("stop-then-start") && r.strategy.contains("migrates data"), r.strategy)
        assertEquals("2.0.0", runningVersion())
        assertEquals("3.0.0->2.0.0", Files.readString(data.resolve("migrated")))
        assertTrue(r.lock.contains("acme-mig"), r.lock)
        // and forward again: 3.0.0 is in the history
        assertEquals(listOf("3.0.0", "1.0.0"), core.rollbackTargets("mig-app"))
    }
}
