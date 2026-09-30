// SPDX-License-Identifier: Apache-2.0

package cringle.engine.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** A name that is no name must not build a path outside the fabric directory (#66). */
class FabricPathsTest {
    @TempDir
    lateinit var engineDir: Path

    @Test
    fun directoriesOfTheFabricAndItsBlocks() {
        val paths = FabricPaths(engineDir, "shop-1")
        assertEquals(engineDir.resolve("fabrics/shop-1/working"), paths.working)
        assertEquals(engineDir.resolve("fabrics/shop-1/logs"), paths.logs)
        assertEquals(engineDir.resolve("fabrics/shop-1/working/m1"), paths.blockWorking("m1"))
        assertEquals(engineDir.resolve("fabrics/shop-1/logs/m1"), paths.blockLogs("m1"))
    }

    @Test
    fun fabricIdMustStayBelowTheFabricDirectory() {
        for (id in listOf("..", "../evil", "a/../../evil", "shop-1/../../evil")) {
            val e = assertThrows<FabricException> { FabricPaths(engineDir, id) }
            assertTrue(e.message!!.contains("leaves"), e.message)
        }
        assertFalse(Files.exists(engineDir.resolve("evil")))
        assertFalse(Files.exists(engineDir.resolve("fabrics")))
    }

    @Test
    fun blockIdMustStayBelowTheDirectoryOfItsKind() {
        val paths = FabricPaths(engineDir, "shop-1")
        for (id in listOf("..", "../evil", "a/../../evil", "m1/../..")) {
            assertThrows<FabricException> { paths.blockWorking(id) }
            assertThrows<FabricException> { paths.blockLogs(id) }
        }
        assertEquals(engineDir.resolve("fabrics/shop-1/working/m1"), paths.blockWorking("m1"))
        assertFalse(Files.exists(engineDir.resolve("fabrics/shop-1/evil")))
    }

    @Test
    fun createStopsAtTheFirstBlockThatLeavesTheFabric() {
        val paths = FabricPaths(engineDir, "shop-1")
        assertThrows<FabricException> { paths.create(listOf("m1", "../evil")) }
        assertTrue(Files.isDirectory(engineDir.resolve("fabrics/shop-1/working/m1")))
        assertFalse(Files.exists(engineDir.resolve("fabrics/shop-1/evil")))
    }
}
