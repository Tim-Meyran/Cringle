// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import cringle.contract.BlockContext
import cringle.contract.BlockId
import cringle.contract.BlockPorts
import cringle.contract.BuiltinDriverTypes
import cringle.contract.DwhDriver
import cringle.contract.DwhEntry
import cringle.contract.LogLevel
import cringle.contract.LoggingDriver
import cringle.contract.PortRef
import cringle.contract.Tether
import cringle.contract.TetherByteStream
import cringle.contract.TetherEvent
import cringle.contract.TetherStream
import cringle.contract.TetherType
import cringle.testkit.TestDriverSet
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** An output that nothing is connected to does not fail a block of the library (what the engine does to a send on such a port). */
class QuietOutputsTest {
    /** A tether like the engine's for a port without a connection: a send fails with the message the engine uses. */
    private class Unconnected(private val name: String, private val failure: () -> Throwable = { IllegalStateException("port '$name' of block 'b' is not connected in the blueprint") }) : Tether {
        override val type = TetherType.MESSAGE

        override suspend fun send(message: Any): Unit = throw failure()

        override suspend fun request(request: Any): Any = throw failure()

        override suspend fun openStream(): TetherStream = throw failure()

        override suspend fun openByteStream(): TetherByteStream = throw failure()
    }

    private fun context(config: Map<String, Any?> = emptyMap(), failure: (String) -> Throwable = { IllegalStateException("port '$it' of block 'b' is not connected in the blueprint") }) =
        object : BlockContext {
            override val blockId = BlockId("b")
            override val config = config
            override val ports = object : BlockPorts {
                override fun port(name: String): Tether = Unconnected(name) { failure(name) }

                override fun varArgPort(name: String): List<Tether> = emptyList()
            }
        }

    private fun created(name: String, drivers: TestDriverSet = TestDriverSet()) = StdBlockProvider().createBlock(name, drivers)

    @Test
    fun aBlockWithoutAnyConnectedOutputKeepsWorking() = runTest {
        val upper = created("text.upper")
        upper.init(context())
        upper.start()
        upper.onTetherEvent(TetherEvent.Message(PortRef("in"), "hello"))
        upper.onTetherEvent(TetherEvent.Message(PortRef("in"), "again"))
        upper.stop()
    }

    @Test
    fun theWritingBlocksWriteAndGoOnWithoutOut() = runTest {
        val lines = ArrayList<String>()
        val logging = object : LoggingDriver {
            override val type = BuiltinDriverTypes.LOGGING
            override suspend fun log(level: LogLevel, message: String, error: Throwable?) { lines += message }
        }
        val log = created("log.write", TestDriverSet().add(LoggingDriver::class, logging))
        log.init(context(mapOf("prefix" to "x: ")))
        log.onTetherEvent(TetherEvent.Message(PortRef("in"), "a"))
        log.onTetherEvent(TetherEvent.Message(PortRef("in"), "b"))
        assertEquals(listOf("x: a", "x: b"), lines, "every value is written, not only the first")

        val written = ArrayList<DwhEntry>()
        val dwh = object : DwhDriver {
            override val type = BuiltinDriverTypes.DWH
            override suspend fun write(entry: DwhEntry) { written += entry }
            override suspend fun read(since: Instant?, until: Instant?, limit: Int): List<DwhEntry> = written
        }
        val store = created("dwh.write", TestDriverSet().add(DwhDriver::class, dwh))
        store.init(context(mapOf("key" to "k")))
        store.onTetherEvent(TetherEvent.Message(PortRef("in"), 1L))
        store.onTetherEvent(TetherEvent.Message(PortRef("in"), 2L))
        assertEquals(listOf<Any>(1L, 2L), written.map { it.value })
    }

    @Test
    fun otherFailuresAreNotHidden() = runTest {
        val upper = created("text.upper")
        upper.init(context { IllegalStateException("the queue is full") })
        assertThrows<IllegalStateException> { upper.onTetherEvent(TetherEvent.Message(PortRef("in"), "x")) }
    }
}
