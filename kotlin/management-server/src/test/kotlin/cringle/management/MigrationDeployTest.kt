// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.engine.v1.FabricRuntimeState
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A failed migration of a data folder (#259): the state, the message, the retry and the update strategy. */
@Tag("integration")
class MigrationDeployTest : ServiceTestBase() {
    private val data get() = dir.resolve("home/data/mig-app/app/1/c1")

    private fun fabric() = runBlocking { core.listFabrics(null, null).single { it.info.fabricId.value.startsWith("mig-app") } }

    @Test
    fun aFailedMigrationIsShownAndStartingTheFabricRetriesIt() {
        runBlocking { core.deploy("mig-app", "1.0.0", true, false, true) }
        assertEquals(FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING, fabric().info.state)
        Files.writeString(data.resolve("state"), "old")

        // a plugin with a processor: the old fabric is stopped first, because the processor works on the data folder
        val e = assertThrows<ManagementException> { runBlocking { core.deploy("mig-app", "2.0.0", true, false, true) } }
        assertTrue(e.message!!.contains("cannot convert 1.0.0 to 2.0.0"), e.message)
        val failed = fabric()
        assertEquals(FabricRuntimeState.FABRIC_RUNTIME_STATE_MIGRATION_FAILED, failed.info.state)
        assertTrue(failed.info.failure.contains("step 2.0.0: cannot convert") && failed.info.failure.contains("backup"), failed.info.failure)
        assertEquals("old", Files.readString(dir.resolve("home/data/mig-app/app/1/c1.backup-1.0.0/state")))

        // the operator fixes it; starting the fabric retries the migration
        Files.writeString(data.resolve("fixed"), "")
        runBlocking { core.startFabric(failed.machine, failed.engineId, failed.info.fabricId.value) }
        assertEquals(FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING, fabric().info.state)
        assertEquals("1.0.0->2.0.0", Files.readString(data.resolve("migrated")))
    }

    @Test
    fun anUpdateThatMigratesIsStopThenStart() {
        runBlocking { core.deploy("mig-app", "1.0.0", true, false, true) }
        Files.writeString(data.resolve("fixed"), "")
        val result = runBlocking { core.deploy("mig-app", "2.0.0", true, false, true) }
        assertTrue(result.strategy.startsWith("stop-then-start") && result.strategy.contains("migrates data"), result.strategy)
        assertEquals(FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING, fabric().info.state)
    }
}
