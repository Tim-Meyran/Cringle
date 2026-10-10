// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import cringle.contract.Block
import cringle.contract.FilesystemAccessException
import cringle.engine.drivers.FilesystemSandbox
import cringle.stdblocks.file.FileDelete
import cringle.stdblocks.file.FileExists
import cringle.stdblocks.file.FileList
import cringle.stdblocks.file.FileRead
import cringle.stdblocks.file.FileWatch
import cringle.stdblocks.file.FileWrite
import cringle.stdblocks.file.TempDir
import cringle.stdblocks.file.joinPath
import cringle.testkit.BlockTestHarness
import cringle.testkit.TestBlockPorts
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir as JunitTempDir

@OptIn(ExperimentalCoroutinesApi::class)
class FileBlocksTest {
    @JunitTempDir
    lateinit var shared: Path

    private val fs get() = FilesystemSandbox(shared, cringle.contract.BuiltinDriverTypes.FILESYSTEM_FABRIC)

    private fun ports(vararg names: String) = TestBlockPorts().also { p -> names.forEach { p.addPort(it) } }

    private fun harness(block: Block, ports: TestBlockPorts, config: Map<String, Any?> = emptyMap()) = BlockTestHarness(block, config = config, ports = ports)

    /** The file system calls run on real threads, the watcher on the test scheduler: run the scheduler until [condition] holds. */
    private fun kotlinx.coroutines.test.TestScope.settle(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            runCurrent()
            check(System.nanoTime() < deadline) { "the watcher did not settle" }
            Thread.sleep(10)
        }
    }

    @Test
    fun paths() {
        assertEquals("a/b", joinPath("a/", "/b"))
        assertEquals("a", joinPath("a", null))
        assertEquals("b", joinPath("", "b"))
        assertEquals("", joinPath(null, null))
    }

    @Test
    fun tempDirMakesFreshFoldersAndRemovesThemWhenDestroyed() = runTest {
        val ports = ports("create", "path")
        val h = harness(TempDir(fs), ports, mapOf("prefix" to "job"))
        h.init()
        h.start()
        h.sendMessage("create", 1L)
        h.sendMessage("create", 2L)
        val (a, b) = ports.tether("path").sentMessages.map { it as String }
        assertTrue(a.startsWith("tmp/job-") && b.startsWith("tmp/job-") && a != b, "$a $b")
        assertTrue(Files.isDirectory(shared.resolve(a)) && Files.isDirectory(shared.resolve(b)))
        fs.writeText("$a/inside.txt", "x")
        h.stop()
        h.destroy()
        assertFalse(Files.exists(shared.resolve(a)) || Files.exists(shared.resolve(b)), "the folders are removed, also with content")
        assertThrows<IllegalArgumentException> { harness(TempDir(fs), ports("create", "path"), mapOf("prefix" to "../evil")).init() }
    }

    @Test
    fun aFileTravelsThroughWriteReadListExistsAndDelete() = runTest {
        val write = ports("path", "in", "done")
        harness(FileWrite(fs), write, mapOf("name" to "note.txt")).runLifecycle {
            sendMessage("path", "docs")
            sendMessage("in", "hello")
            sendMessage("in", "again")
            assertEquals(listOf<Any>("docs/note.txt", "docs/note.txt"), write.tether("done").sentMessages)
        }
        assertEquals("again", fs.readText("docs/note.txt"), "a second text replaces the first")

        val append = ports("path", "in", "done")
        harness(FileWrite(fs), append, mapOf("append" to true)).runLifecycle {
            sendMessage("path", "docs/log.txt")
            sendMessage("in", "a")
            sendMessage("in", "b")
        }
        assertEquals("ab", fs.readText("docs/log.txt"))

        val read = ports("path", "text")
        harness(FileRead(fs), read, mapOf("name" to "note.txt")).runLifecycle {
            sendMessage("path", "docs")
            assertEquals(listOf<Any>("again"), read.tether("text").sentMessages)
        }

        val list = ports("path", "name")
        harness(FileList(fs), list).runLifecycle {
            sendMessage("path", "docs")
            assertEquals(listOf<Any>("docs/log.txt", "docs/note.txt"), list.tether("name").sentMessages)
        }

        val exists = ports("path", "yes", "no")
        harness(FileExists(fs), exists).runLifecycle {
            sendMessage("path", "docs/note.txt")
            sendMessage("path", "docs/none.txt")
            assertEquals(listOf<Any>("docs/note.txt"), exists.tether("yes").sentMessages)
            assertEquals(listOf<Any>("docs/none.txt"), exists.tether("no").sentMessages)
        }

        val delete = ports("path", "deleted")
        harness(FileDelete(fs), delete).runLifecycle {
            sendMessage("path", "docs")
            sendMessage("path", "docs")
            assertEquals(listOf<Any>("docs"), delete.tether("deleted").sentMessages, "the second delete finds nothing")
        }
        assertFalse(fs.exists("docs"))
    }

    @Test
    fun aPathThatLeavesTheFolderIsRefusedAndWriteNeedsAPath() = runTest {
        val write = ports("path", "in", "done")
        harness(FileWrite(fs), write).runLifecycle {
            assertThrows<IllegalStateException> { sendMessage("in", "no path yet") }
            sendMessage("path", "../outside.txt")
            assertThrows<FilesystemAccessException> { sendMessage("in", "x") }
        }
        assertFalse(Files.exists(shared.resolveSibling("outside.txt")))
        val delete = ports("path", "deleted")
        harness(FileDelete(fs), delete).runLifecycle {
            assertThrows<FilesystemAccessException> { sendMessage("path", "../") }
            sendMessage("path", "")
            assertEquals(emptyList<Any>(), delete.tether("deleted").sentMessages, "the shared folder itself is never deleted")
        }
        assertTrue(Files.isDirectory(shared))
    }

    @Test
    fun watchReportsWhatAppearsAndWhatGoesAway() = runTest {
        fs.writeText("in/old.txt", "x")
        val ports = ports("created", "removed")
        val h = harness(FileWatch(fs, StandardTestDispatcher(testScheduler)), ports, mapOf("path" to "in", "intervalMs" to 100, "reportExisting" to true))
        h.init()
        h.start()
        // the first look at the folder reports what is there; after it the watcher is in its loop
        settle { ports.tether("created").sentMessages.isNotEmpty() }
        assertEquals(listOf<Any>("in/old.txt"), ports.tether("created").sentMessages)
        fs.writeText("in/new.txt", "y")
        advanceTimeBy(100)
        settle { ports.tether("created").sentMessages.size == 2 }
        assertEquals(listOf<Any>("in/old.txt", "in/new.txt"), ports.tether("created").sentMessages)
        fs.delete("in/old.txt")
        advanceTimeBy(100)
        settle { ports.tether("removed").sentMessages.isNotEmpty() }
        assertEquals(listOf<Any>("in/old.txt"), ports.tether("removed").sentMessages)
        h.stop()
        h.destroy()

        // without reportExisting what was there at the start stays unreported
        val quiet = ports("created", "removed")
        val h2 = harness(FileWatch(fs, StandardTestDispatcher(testScheduler)), quiet, mapOf("path" to "in", "intervalMs" to 100))
        h2.init()
        h2.start()
        repeat(3) {
            advanceTimeBy(100)
            runCurrent()
            Thread.sleep(30)
        }
        runCurrent()
        assertEquals(emptyList<Any>(), quiet.tether("created").sentMessages)
        h2.stop()
        assertThrows<IllegalArgumentException> { harness(FileWatch(fs), ports("created", "removed"), mapOf("intervalMs" to 10)).init() }
    }
}
