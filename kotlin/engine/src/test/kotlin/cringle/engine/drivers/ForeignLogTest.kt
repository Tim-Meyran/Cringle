// SPDX-License-Identifier: Apache-2.0

package cringle.engine.drivers

import cringle.contract.LogEntry
import cringle.contract.LogLevel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Log files that foreign processes write into the log folder of a block are part of the log query (#189). */
class ForeignLogTest {
    @TempDir
    lateinit var dir: Path

    private val modified = Instant.parse("2026-10-02T12:00:00Z")

    private fun file(fabric: String, block: String, name: String, text: String): Path {
        val folder = Files.createDirectories(dir.resolve("fabrics/$fabric/logs/$block"))
        return Files.writeString(folder.resolve(name), text).also { Files.setLastModifiedTime(it, FileTime.from(modified)) }
    }

    @Test
    fun linesBecomeEntriesWithSourceLevelAndTime() {
        file("f1", "b1", "app.log", "2026-10-01T10:00:00Z ERROR boom\nWARN something odd\nplain line\n\n")
        val entries = LoggingService(dir).query()
        assertEquals(3, entries.size)
        val boom = entries.single { "boom" in it.message }
        assertEquals(LogLevel.ERROR, boom.level)
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"), boom.timestamp)
        assertEquals(listOf("app.log"), entries.map { it.source }.distinct())
        assertEquals(listOf("f1"), entries.map { it.fabric }.distinct())
        assertEquals(listOf("b1"), entries.map { it.block }.distinct())
        assertEquals(LogLevel.WARN, entries.single { "odd" in it.message }.level)
        val plain = entries.single { it.message == "plain line" }
        assertEquals(LogLevel.INFO, plain.level)
        assertEquals(modified, plain.timestamp)
    }

    @Test
    fun theMirrorFileOddNamesAndOtherExtensionsAreNotRead() {
        file("f1", "b1", "block.log", "mirror of the store\n")
        file("f1", "b1", "a b.log", "space in the name\n")
        file("f1", "b1", "notes.txt", "not a log\n")
        file("f1", "b1", "ok.log", "fine\n")
        assertEquals(listOf("fine"), LoggingService(dir).query().map { it.message })
    }

    @Test
    fun filtersBySinceFabricBlockAndLevelApplyToForeignLines() {
        file("f1", "b1", "a.log", "2026-10-01T10:00:00Z DEBUG old debug\n2026-10-03T10:00:00Z ERROR new error\n")
        file("f1", "b2", "a.log", "other block\n")
        file("f2", "b1", "a.log", "other fabric\n")
        val service = LoggingService(dir)
        assertEquals(setOf("old debug", "new error", "other block", "other fabric"), service.query().map { it.message.substringAfter("DEBUG ").substringAfter("ERROR ") }.toSet())
        assertEquals(listOf("other block"), service.query(LogQuery(fabric = "f1", block = "b2")).map { it.message })
        assertEquals(2, service.query(LogQuery(fabric = "f1", block = "b1")).size)
        assertEquals(listOf("2026-10-03T10:00:00Z ERROR new error"), service.query(LogQuery(fabric = "f1", block = "b1", minLevel = LogLevel.WARN)).map { it.message })
        assertEquals(listOf("2026-10-03T10:00:00Z ERROR new error"), service.query(LogQuery(fabric = "f1", block = "b1", since = Instant.parse("2026-10-02T00:00:00Z"))).map { it.message })
    }

    @Test
    fun aBigFileIsCutToItsTailAndTheFirstPartialLineIsDropped() {
        val line = "x".repeat(99) + "\n"
        val text = (0 until 20_000).joinToString("") { "%05d ".format(it) + line }
        file("f1", "b1", "big.log", text)
        val entries = LoggingService(dir).query(LogQuery(limit = 100_000))
        assertTrue(entries.size in 9_000..10_500, "about the last megabyte: ${entries.size}")
        assertEquals("19999 ", entries.last().message.take(6))
        assertTrue(entries.all { it.message.length == 105 }, "no partial line")
    }

    @Test
    fun foreignLinesAreMergedWithTheEntriesOfTheStoreByTime() {
        val service = LoggingService(dir)
        service.append(LogEntry(Instant.parse("2026-10-01T09:00:00Z"), "f1", "b1", LogLevel.INFO, "from the driver, early"))
        service.append(LogEntry(Instant.parse("2026-10-01T11:00:00Z"), "f1", "b1", LogLevel.INFO, "from the driver, late"))
        file("f1", "b1", "p.log", "2026-10-01T10:00:00Z INFO from the process\n")
        val entries = service.query()
        assertEquals(listOf("from the driver, early", "2026-10-01T10:00:00Z INFO from the process", "from the driver, late"), entries.map { it.message })
        assertEquals(listOf("", "p.log", ""), entries.map { it.source })
    }
}
