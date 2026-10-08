// SPDX-License-Identifier: Apache-2.0

package cringle.engine.dwh

import cringle.contract.BlockDefinition
import cringle.contract.BlockId
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import cringle.engine.tether.TetherConfig
import cringle.engine.tether.TetherNetwork
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.DeliveryPolicy
import cringle.packaging.Endpoint
import cringle.packaging.RecordConfig
import cringle.packaging.TetherDef
import cringle.schema.SchemaRegistry
import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Recording of tether traffic in the data warehouse (#193). */
class TetherRecordingTest {
    @TempDir
    lateinit var dir: Path

    private val string = SchemaRef("cringle.std", "String")
    private val types = TetherType.entries.toSet()
    private val src = BlockDefinition("src", emptyList(), listOf(PortDefinition("out1", PortDirection.OUT, types, string), PortDefinition("out2", PortDirection.OUT, types, string)), emptyList())
    private val dst = BlockDefinition("dst", emptyList(), listOf(PortDefinition("in1", PortDirection.IN, types, string), PortDefinition("in2", PortDirection.IN, types, string)), emptyList())
    private val dwh by lazy { Dwh(dir.resolve("dwh")) }
    private val recorder by lazy { TetherRecorder(dwh, "f1") }

    @AfterEach
    fun close() {
        recorder.close()
    }

    private fun network(record1: RecordConfig?, type: TetherType = TetherType.MESSAGE): TetherNetwork {
        val blueprint = Blueprint(
            "bp",
            listOf(BlueprintBlock("s", "p/src"), BlueprintBlock("d", "p/dst")),
            listOf(
                TetherDef(type, Endpoint("s", "out1"), Endpoint("d", "in1"), DeliveryPolicy.DROP, record = record1),
                TetherDef(type, Endpoint("s", "out2"), Endpoint("d", "in2"), DeliveryPolicy.DROP),
            ),
        )
        val n = TetherNetwork.create(blueprint, mapOf("s" to src, "d" to dst), TetherConfig(SchemaRegistry(), observer = recorder)) { _, _ -> }
        runBlocking {
            n.open { _, event -> if (event is cringle.contract.TetherEvent.Request) event.respond("re:" + event.value) }
        }
        return n
    }

    private fun send(n: TetherNetwork, port: String, vararg values: String) = runBlocking {
        val tether = n.tether(BlockId("s"), src.ports.first { it.name == port }, null)
        values.forEach { tether.send(it) }
    }

    private fun recorded(): Map<String, List<String>> = dwh.partitions().associate { p ->
        p.partition.name to dwh.query(p.partition).map { (it.payload as JsonPrimitive).content }
    }

    private fun settle(n: TetherNetwork) {
        // the messages are delivered by the pump of the network, then written by the recorder
        Thread.sleep(200)
        runBlocking { recorder.flush() }
    }

    @Test
    fun aTetherWithARecordDefinitionIsRecordedAndOneWithoutIsNot() {
        network(RecordConfig(Duration.ofDays(3), 5000)).use { n ->
            send(n, "out1", "a", "b", "c")
            send(n, "out2", "x")
            settle(n)
            assertEquals(mapOf("s.out1 -> d.in1" to listOf("a", "b", "c")), recorded())
            val partition = dwh.partitions().single()
            assertEquals(DwhPartition("f1", DwhKind.TETHER, "s.out1 -> d.in1"), partition.partition)
            assertEquals(Retention(Duration.ofDays(3), 5000), partition.retention)
            assertEquals(mapOf("kind" to "message"), dwh.query(partition.partition, limit = 1).single().tags)
        }
    }

    @Test
    fun theRecordingModeRecordsEveryTypedTetherUntilItIsSwitchedOff() {
        network(null).use { n ->
            send(n, "out1", "before")
            settle(n)
            assertEquals(emptyMap<String, List<String>>(), recorded())
            recorder.setMode(true, RecordConfig(maxBytes = 4000))
            send(n, "out1", "one")
            send(n, "out2", "two")
            settle(n)
            assertEquals(mapOf("s.out1 -> d.in1" to listOf("one"), "s.out2 -> d.in2" to listOf("two")), recorded())
            assertTrue(dwh.partitions().all { it.retention == Retention(null, 4000) })
            recorder.setMode(false, null)
            send(n, "out1", "after")
            settle(n)
            assertEquals(listOf("one"), recorded().getValue("s.out1 -> d.in1"))
        }
    }

    @Test
    fun requestsAndResponsesAreRecordedWithTheirKind() {
        network(RecordConfig(), TetherType.REQUEST_RESPONSE).use { n ->
            val answer = runBlocking { n.tether(BlockId("s"), src.ports.first { it.name == "out1" }, null).request("ping") }
            assertEquals("re:ping", answer)
            settle(n)
            val records = dwh.query(DwhPartition("f1", DwhKind.TETHER, "s.out1 -> d.in1"))
            assertEquals(listOf("request" to "ping", "response" to "re:ping"), records.map { it.tags.getValue("kind") to (it.payload as JsonPrimitive).content })
        }
    }

    @Test
    fun aFullQueueDropsRecordsAndCountsThemInsteadOfSlowingTheTether() {
        val slow = TetherRecorder(Dwh(dir.resolve("dwh-slow")), "f1", capacity = 1, warn = { warned += it })
        val info = cringle.engine.tether.TetherInfo("t", TetherType.MESSAGE, Endpoint("s", "o"), Endpoint("d", "i"), null, RecordConfig())
        repeat(5000) { slow.observe(info, cringle.engine.tether.TrafficKind.MESSAGE, "m$it") }
        slow.close()
        assertTrue(slow.dropped.get() > 0, "some of 5000 records cannot be queued at once")
        assertEquals(1, warned.size)
    }

    private val warned = java.util.concurrent.CopyOnWriteArrayList<String>()
}
