// SPDX-License-Identifier: Apache-2.0

package cringle.engine.drivers

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.BlockDefinition
import cringle.contract.BlockId
import cringle.contract.BlockProvider
import cringle.contract.DriverSet
import cringle.contract.FilesystemAccessException
import cringle.contract.FilesystemDriver
import cringle.contract.LogLevel
import cringle.contract.LoggingDriver
import cringle.contract.PortInUseException
import cringle.contract.TcpDriver
import cringle.engine.fabric.BlockResolver
import cringle.engine.fabric.FabricPaths
import cringle.engine.fabric.FabricRuntime
import cringle.engine.fabric.FabricSpec
import cringle.engine.fabric.PluginTrust
import cringle.engine.fabric.ResolvedBlock
import cringle.engine.fabric.WatchdogConfig
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class BuiltinDriversTest {
    @TempDir
    lateinit var dir: Path

    private fun drivers() = BuiltinDrivers(dir.resolve("engine"))

    private fun set(d: BuiltinDrivers, fabric: String, block: String, vararg ids: String): DriverSet =
        d.factoryFor(fabric, FabricPaths(dir.resolve("engine"), fabric)).driversFor(BlockId(block), ids.toList())

    // ---- logging ----

    @Test
    fun logsCanBeQueriedByFabricAndBlock(): Unit = runBlocking {
        drivers().use { d ->
            val a = set(d, "f1", "a", "logging")[LoggingDriver::class]
            val b = set(d, "f1", "b", "logging")[LoggingDriver::class]
            val c = set(d, "f2", "a", "logging")[LoggingDriver::class]
            a.log(LogLevel.INFO, "from a")
            b.log(LogLevel.WARN, "from b\nsecond line\twith tab")
            c.log(LogLevel.ERROR, "from other fabric", IllegalStateException("boom"))
            assertEquals(listOf("from a", "from b\nsecond line\twith tab"), d.logging.query(LogQuery(fabric = "f1")).map { it.message })
            assertEquals(listOf("from a"), d.logging.query(LogQuery(fabric = "f1", block = "a")).map { it.message })
            assertEquals(1, d.logging.query(LogQuery(block = "a", fabric = "f2")).size)
            assertTrue(d.logging.query(LogQuery(fabric = "f2")).single().message.contains("IllegalStateException: boom"))
            assertEquals(2, d.logging.query(LogQuery(minLevel = LogLevel.WARN)).size)
            assertEquals(1, d.logging.query(LogQuery(limit = 1)).size)
            val entry = d.logging.query(LogQuery(fabric = "f1", block = "b")).single()
            assertEquals("f1", entry.fabric)
            assertEquals("b", entry.block)
            assertEquals(LogLevel.WARN, entry.level)
        }
    }

    @Test
    fun logsSurviveARestartAndAreMirroredIntoTheBlockLogDirectory(): Unit = runBlocking {
        drivers().use { set(it, "f", "a", "logging")[LoggingDriver::class].log(LogLevel.INFO, "persisted") }
        drivers().use { d ->
            assertEquals(listOf("persisted"), d.logging.query().map { it.message })
            val blockLog = FabricPaths(dir.resolve("engine"), "f").blockLogs("a").resolve("block.log")
            assertTrue(Files.readString(blockLog).contains("persisted"))
        }
    }

    // ---- filesystem ----

    @Test
    fun filesystemWorksInsideTheBlockDirectory(): Unit = runBlocking {
        drivers().use { d ->
            val fs = set(d, "f", "a", "filesystem")[FilesystemDriver::class]
            fs.writeText("sub/dir/x.txt", "hello")
            fs.append("sub/dir/x.txt", " world".toByteArray())
            assertEquals("hello world", fs.readText("sub/dir/x.txt"))
            assertEquals(listOf("dir"), fs.list("sub"))
            assertTrue(fs.exists("sub/dir/x.txt"))
            assertTrue(fs.delete("sub/dir/x.txt"))
            assertFalse(fs.exists("sub/dir/x.txt"))
            fs.writeBytes("b.bin", byteArrayOf(1, 2))
            assertArrayEquals(byteArrayOf(1, 2), fs.readBytes("b.bin"))
            // the file lives in the block's working directory
            assertTrue(Files.exists(FabricPaths(dir.resolve("engine"), "f").blockWorking("a").resolve("b.bin")))
        }
    }

    @Test
    fun filesystemEscapeAttemptsAreBlocked(): Unit = runBlocking {
        drivers().use { d ->
            val fs = set(d, "f", "a", "filesystem")[FilesystemDriver::class]
            val other = set(d, "f", "b", "filesystem")[FilesystemDriver::class]
            other.writeText("secret.txt", "s3cret")
            Files.writeString(dir.resolve("outside.txt"), "outside")
            val attempts = listOf(
                "../outside.txt",
                "../b/secret.txt",
                "../../../../outside.txt",
                "a/../../b/secret.txt",
                "/etc/passwd",
                "\\windows\\system.ini",
                "C:/Windows/win.ini",
                "x\u0000y",
            )
            for (p in attempts) {
                assertThrows<FilesystemAccessException>("read $p") { runBlocking { fs.readText(p) } }
                assertThrows<FilesystemAccessException>("write $p") { runBlocking { fs.writeText(p, "x") } }
                assertThrows<FilesystemAccessException>("delete $p") { runBlocking { fs.delete(p) } }
            }
            assertThrows<FilesystemAccessException> { runBlocking { fs.list("..") } }
            assertEquals("s3cret", other.readText("secret.txt"))
            assertEquals("outside", Files.readString(dir.resolve("outside.txt")))
        }
    }

    @Test
    fun filesystemBlocksSymbolicLinksOutOfTheDirectory(): Unit = runBlocking {
        drivers().use { d ->
            val fs = set(d, "f", "a", "filesystem")[FilesystemDriver::class]
            fs.createDirectories("")
            val root = FabricPaths(dir.resolve("engine"), "f").blockWorking("a")
            val outside = Files.createDirectories(dir.resolve("elsewhere"))
            Files.writeString(outside.resolve("data.txt"), "data")
            try {
                Files.createSymbolicLink(root.resolve("link"), outside)
            } catch (e: Exception) {
                return@use // symbolic links are not available on this system (for example Windows without privilege)
            }
            assertThrows<FilesystemAccessException> { runBlocking { fs.readText("link/data.txt") } }
            assertThrows<FilesystemAccessException> { runBlocking { fs.writeText("link/new.txt", "x") } }
        }
    }

    // ---- tcp ----

    @Test
    fun secondBlockAskingForTheSamePortGetsADeterministicError(): Unit = runBlocking {
        drivers().use { d ->
            val a = set(d, "f1", "a", "tcp")[TcpDriver::class]
            val b = set(d, "f2", "b", "tcp")[TcpDriver::class]
            val listener = a.listen(0)
            val port = listener.port
            val e = assertThrows<PortInUseException> { runBlocking { b.listen(port) } }
            assertEquals(port, e.port)
            assertEquals("port $port is already used by block 'a' of fabric 'f1'; block 'b' of fabric 'f2' cannot use it", e.message)
            assertEquals("block 'a' of fabric 'f1'", d.tcp.ports.owner(port))
            listener.close()
            assertNull(d.tcp.ports.owner(port))
            b.listen(port).close() // free again
        }
    }

    @Test
    fun portUsedByAnotherProcessIsReported(): Unit = runBlocking {
        drivers().use { d ->
            java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { foreign ->
                val a = set(d, "f", "a", "tcp")[TcpDriver::class]
                val e = assertThrows<PortInUseException> { runBlocking { a.listen(foreign.localPort) } }
                assertTrue(e.message!!.contains("another process"), e.message)
                assertNull(d.tcp.ports.owner(foreign.localPort))
            }
        }
    }

    @Test
    fun tcpConnectionsExchangeBytes(): Unit = runBlocking {
        drivers().use { d ->
            val server = set(d, "f", "server", "tcp")[TcpDriver::class]
            val client = set(d, "f", "client", "tcp")[TcpDriver::class]
            val listener = server.listen(0)
            val c = client.connect("127.0.0.1", listener.port)
            val s = withTimeout(10.seconds) { listener.connections.first() }
            c.write("ping".toByteArray())
            assertEquals("ping", String(withTimeout(10.seconds) { s.incoming.first() }))
            s.write("pong".toByteArray())
            assertEquals("pong", String(withTimeout(10.seconds) { c.incoming.first() }))
            c.close()
            assertEquals(emptyList<ByteArray>(), withTimeout(10.seconds) { s.incoming.toList() })
        }
    }

    // ---- integration with the fabric runtime ----

    @Test
    fun blocksGetTheirDriversAndPortsAreFreedWhenTheFabricStops(): Unit = runBlocking {
        val d = drivers()
        val definition = BlockDefinition("srv", emptyList(), emptyList(), listOf("logging", "filesystem", "tcp"))
        var port = 0
        val provider = object : BlockProvider {
            override val definitions = listOf(definition)
            override fun createBlock(definitionName: String, drivers: DriverSet): Block = object : Block {
                override suspend fun start() {
                    port = drivers[TcpDriver::class].listen(0).port
                    drivers[LoggingDriver::class].log(LogLevel.INFO, "listening")
                    drivers[FilesystemDriver::class].writeText("state.txt", "up")
                }
            }
        }
        val paths = FabricPaths(dir.resolve("engine"), "fab")
        FabricRuntime(
            FabricSpec(
                id = "fab",
                blueprint = Blueprint("bp", listOf(BlueprintBlock("s", "p/srv")), emptyList()),
                resolver = BlockResolver { ResolvedBlock(provider, definition, PluginTrust.TRUSTED) },
                drivers = d.factoryFor("fab", paths),
                paths = paths,
                watchdog = WatchdogConfig(enabled = false),
            ),
        ).use { fabric ->
            fabric.start()
            assertEquals("block 's' of fabric 'fab'", d.tcp.ports.owner(port))
            assertEquals(listOf("listening"), d.logging.query(LogQuery(fabric = "fab", block = "s")).map { it.message })
            assertEquals("up", Files.readString(paths.blockWorking("s").resolve("state.txt")))
            fabric.stop()
            assertNull(d.tcp.ports.owner(port))
        }
        d.close()
    }

    @Test
    fun unknownAndUndeclaredDriversAreRejected() {
        drivers().use { d ->
            val e = assertThrows<IllegalArgumentException> { set(d, "f", "a", "serial") }
            assertTrue(e.message!!.contains("unknown driver 'serial'"), e.message)
            val onlyLog = set(d, "f", "a", "logging")
            assertThrows<IllegalArgumentException> { onlyLog[TcpDriver::class] }
        }
    }
}
