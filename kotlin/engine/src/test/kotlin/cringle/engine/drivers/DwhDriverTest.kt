// SPDX-License-Identifier: Apache-2.0

package cringle.engine.drivers

import cringle.contract.BlockId
import cringle.contract.DriverSet
import cringle.contract.DwhDriver
import cringle.contract.DwhEntry
import cringle.contract.LoggingDriver
import cringle.engine.fabric.FabricPaths
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** The data warehouse driver of a block (#192). */
class DwhDriverTest {
    @TempDir
    lateinit var dir: Path

    private fun set(d: BuiltinDrivers, fabric: String, block: String, vararg ids: String): DriverSet =
        d.factoryFor(fabric, FabricPaths(dir.resolve("engine"), fabric)).driversFor(BlockId(block), ids.toList())

    @Test
    fun whatABlockWritesItReadsBackInOrderWithValueAndTags(): Unit = runBlocking {
        BuiltinDrivers(dir.resolve("engine")).use { d ->
            val dwh = set(d, "f1", "a", "dwh")[DwhDriver::class]
            val t0 = Instant.parse("2026-10-01T10:00:00Z")
            dwh.write(DwhEntry("order", mapOf("id" to 1, "lines" to listOf("x", "y"), "paid" to true), t0, mapOf("kind" to "order")))
            dwh.write(DwhEntry("note", "plain text", t0.plusSeconds(1)))
            val all = dwh.read()
            assertEquals(listOf("order", "note"), all.map { it.key })
            assertEquals(mapOf("id" to 1L, "lines" to listOf("x", "y"), "paid" to true), all[0].value)
            assertEquals(mapOf("kind" to "order"), all[0].tags)
            assertEquals("plain text", all[1].value)
            assertEquals(t0, all[0].timestamp)
            assertEquals(listOf("note"), dwh.read(limit = 1).map { it.key })
            assertEquals(listOf("note"), dwh.read(since = t0.plusSeconds(1)).map { it.key })
        }
    }

    @Test
    fun blocksAndFabricsDoNotSeeEachOthersRecords(): Unit = runBlocking {
        BuiltinDrivers(dir.resolve("engine")).use { d ->
            val a = set(d, "f1", "a", "dwh")[DwhDriver::class]
            val b = set(d, "f1", "b", "dwh")[DwhDriver::class]
            val other = set(d, "f2", "a", "dwh")[DwhDriver::class]
            a.write(DwhEntry("from-a", 1))
            b.write(DwhEntry("from-b", 2))
            other.write(DwhEntry("from-f2", 3))
            assertEquals(listOf("from-a"), a.read().map { it.key })
            assertEquals(listOf("from-b"), b.read().map { it.key })
            assertEquals(listOf("from-f2"), other.read().map { it.key })
        }
    }

    @Test
    fun aValueThatIsNotJsonLikeIsRejectedAndTheDriverIsOnlyGivenToBlocksThatDeclareIt(): Unit = runBlocking {
        BuiltinDrivers(dir.resolve("engine")).use { d ->
            val dwh = set(d, "f1", "a", "dwh")[DwhDriver::class]
            assertThrows<IllegalArgumentException> { runBlocking { dwh.write(DwhEntry("bad", Any())) } }
            assertTrue(dwh.read().isEmpty())
            val withoutIt = set(d, "f1", "a", "logging")
            assertTrue(withoutIt[LoggingDriver::class] is LoggingDriver)
            assertThrows<IllegalArgumentException> { withoutIt[DwhDriver::class] }
        }
    }
}
