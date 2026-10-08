// SPDX-License-Identifier: Apache-2.0

package cringle.engine.fabric

import cringle.contract.MigrationContext
import cringle.contract.MigrationException
import cringle.contract.MigrationScope
import cringle.contract.Processor
import cringle.contract.SteppedProcessor
import cringle.packaging.ProcessorSet
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class DataMigrationsTest {
    @TempDir
    lateinit var dir: Path

    private val log = ArrayList<String>()

    /** 1.0.0 -> 2.0.0 -> 3.0.0: two steps; the one for 3.0.0 can be made to fail. */
    private class TwoSteps(val failAt3: Boolean) : SteppedProcessor() {
        init {
            step("2.0.0") { c -> Files.writeString(c.dataDirectory.resolve("state"), "v2") }
            step("3.0.0") { c ->
                if (failAt3) throw MigrationException("cannot convert state")
                Files.writeString(c.dataDirectory.resolve("state"), Files.readString(c.dataDirectory.resolve("state")) + "+v3")
            }
        }
    }

    private fun block(version: String, failAt3: Boolean = false, processors: ProcessorSet = ProcessorSet(update = "Up", downgrade = "Down")) =
        MigrationUnit(MigrationScope.PLUGIN, "b", "acme", version, dir.resolve("b"), processors) { TwoSteps(failAt3) }

    private fun run(vararg units: MigrationUnit) = DataMigrations(dir, units.toList()).run { log += it }

    private fun state() = Files.readString(dir.resolve("b").resolve("state"))

    @Test
    fun firstRunOnlyRecordsTheVersion() {
        Files.createDirectories(dir.resolve("b"))
        run(block("1.0.0"))
        assertFalse(Files.exists(dir.resolve("b").resolve("state")))
        assertTrue(Files.readString(dir.resolve(".cringle/versions.properties")).contains("block.b=acme@1.0.0"))
    }

    @Test
    fun updateRunsBothStepsAfterABackup() {
        Files.createDirectories(dir.resolve("b"))
        Files.writeString(dir.resolve("b/old"), "data")
        run(block("1.0.0"))
        run(block("3.0.0"))
        assertEquals("v2+v3", state())
        assertEquals("data", Files.readString(dir.resolve("b.backup-1.0.0/old")))
        assertFalse(Files.exists(dir.resolve("b.backup-1.0.0/state")))
        run(block("3.0.0")) // nothing is due any more
        assertEquals(1, log.count { it.endsWith("done") })
    }

    @Test
    fun failureKeepsTheVersionAndTheBackupAndARetryRunsAgain() {
        Files.createDirectories(dir.resolve("b"))
        Files.writeString(dir.resolve("b/old"), "data")
        run(block("1.0.0"))
        val e = assertThrows<MigrationFailedException> { run(block("3.0.0", failAt3 = true)) }
        assertTrue(e.message!!.contains("step 3.0.0: cannot convert state"), e.message)
        assertTrue(e.message!!.contains("b.backup-1.0.0"), e.message)
        assertTrue(Files.readString(dir.resolve(".cringle/versions.properties")).contains("block.b=acme@1.0.0"))
        assertTrue(Files.isDirectory(dir.resolve("b.backup-1.0.0")))
        // the operator fixes the cause; the retry keeps the first backup
        Files.writeString(dir.resolve("b/state"), "v2")
        run(block("3.0.0"))
        assertEquals("v2+v3", state())
        assertTrue(Files.readString(dir.resolve(".cringle/versions.properties")).contains("block.b=acme@3.0.0"))
        assertTrue(log.any { it.contains("keeping the backup") })
    }

    @Test
    fun downgradeUsesTheDowngradeProcessor() {
        Files.createDirectories(dir.resolve("b"))
        run(block("3.0.0"))
        var seen: MigrationContext? = null
        val down = MigrationUnit(MigrationScope.PLUGIN, "b", "acme", "2.0.0", dir.resolve("b"), ProcessorSet(update = "Up", downgrade = "Down")) { name ->
            assertEquals("Down", name)
            Processor { seen = it }
        }
        run(down)
        assertEquals("3.0.0", seen!!.from)
        assertEquals("2.0.0", seen!!.to)
        assertTrue(Files.isDirectory(dir.resolve("b.backup-3.0.0")))
    }

    @Test
    fun noProcessorOrAnotherPluginOnlyRecords() {
        Files.createDirectories(dir.resolve("b"))
        run(block("1.0.0"))
        run(block("2.0.0", processors = ProcessorSet()))
        run(MigrationUnit(MigrationScope.PLUGIN, "b", "other", "9.0.0", dir.resolve("b"), ProcessorSet(update = "Up")) { error("must not run") })
        assertFalse(Files.exists(dir.resolve("b.backup-1.0.0")))
        assertTrue(Files.readString(dir.resolve(".cringle/versions.properties")).contains("block.b=other@9.0.0"))
    }

    @Test
    fun projectProcessorSeesTheWholeInstanceAndItsBackupLeavesOutBlockBackups() {
        val base = dir.resolve("inst")
        Files.createDirectories(base.resolve("b"))
        Files.writeString(base.resolve("b/f"), "x")
        Files.createDirectories(base.resolve("b.backup-0.1.0"))
        fun project(version: String, make: (String) -> Processor) =
            MigrationUnit(MigrationScope.PROJECT, null, "shop", version, base, ProcessorSet(update = "P"), make)
        DataMigrations(base, listOf(project("1.0.0") { error("no") })).run { }
        var folder: Path? = null
        DataMigrations(base, listOf(project("1.1.0") { Processor { c -> folder = c.dataDirectory; assertEquals(null, c.blockId) } })).run { log += it }
        assertEquals(base, folder)
        val backup = dir.resolve("inst.backup-1.0.0")
        assertEquals("x", Files.readString(backup.resolve("b/f")))
        assertFalse(Files.exists(backup.resolve(".cringle")))
        assertFalse(Files.exists(backup.resolve("b.backup-0.1.0")))
    }

    @Test
    fun anUnexpectedExceptionIsReportedWithItsType() {
        Files.createDirectories(dir.resolve("b"))
        run(block("1.0.0"))
        val e = assertThrows<MigrationFailedException> {
            run(MigrationUnit(MigrationScope.PLUGIN, "b", "acme", "2.0.0", dir.resolve("b"), ProcessorSet(update = "Up")) { Processor { throw IllegalStateException("bad") } })
        }
        assertTrue(e.message!!.contains("IllegalStateException: bad"), e.message)
    }
}
