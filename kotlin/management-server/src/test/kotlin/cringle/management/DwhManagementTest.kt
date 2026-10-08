// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.engine.v1.DwhKind
import cringle.engine.v1.DwhRetention
import cringle.management.v1.ListDwhPartitionsRequest
import cringle.management.v1.QueryDwhRequest
import cringle.management.v1.RecoverRequest
import cringle.management.v1.SetDwhRetentionRequest
import cringle.management.v1.SetRecordingRequest
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The ManagementServer reads and controls the data warehouse of the engines (#194). */
@Tag("integration")
class DwhManagementTest : ServiceTestBase() {
    private val fabric = "recorded-app-app-1"
    private val recorded = "c.out -> s.in"
    private val other = "c2.out -> s2.in"

    private fun query(name: String, kind: DwhKind = DwhKind.DWH_KIND_TETHER, limit: Int = 0) = runBlocking {
        s.queryDwh(QueryDwhRequest.newBuilder().setFabric(fabric).setKind(kind).setName(name).setLimit(limit).build()).recordsList
    }

    private fun partitions() = runBlocking { s.listDwhPartitions(ListDwhPartitionsRequest.newBuilder().setFabric(fabric).build()).partitionsList.map { it.partition } }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 60_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out: $what\n" + diagnostics() }
            Thread.sleep(100)
        }
    }

    @Test
    fun aRecordedTetherIsReadWithItsRetentionAndTheRetentionCanBeChanged() {
        deploy("recorded-app")
        await("records of the recorded tether") { query(recorded).size >= 3 }
        val records = query(recorded)
        assertEquals("\"ping-c\"", records.first().payloadJson)
        assertEquals("message", records.first().tagsMap["kind"])
        assertEquals(2, query(recorded, limit = 2).size)
        // the tether without a record of its own is not recorded
        assertTrue(query(other).isEmpty())
        val list = partitions().single()
        assertEquals(recorded, list.name)
        assertEquals(DwhKind.DWH_KIND_TETHER, list.kind)
        assertEquals(1_000_000, list.retention.maxBytes)
        assertEquals(3L * 86_400_000, list.retention.maxAgeMs)
        assertTrue(list.bytes > 0)
        // all fabrics on all engines
        assertTrue(runBlocking { s.listDwhPartitions(ListDwhPartitionsRequest.getDefaultInstance()).partitionsList }.any { it.partition.name == recorded })

        runBlocking {
            s.setDwhRetention(
                SetDwhRetentionRequest.newBuilder().setFabric(fabric).setKind(DwhKind.DWH_KIND_TETHER).setName(recorded)
                    .setRetention(DwhRetention.newBuilder().setMaxBytes(2048)).build(),
            )
        }
        assertEquals(2048, partitions().single { it.name == recorded }.retention.maxBytes)
        assertEquals(0, partitions().single { it.name == recorded }.retention.maxAgeMs)
    }

    @Test
    fun theRecordingModeRecordsTheOtherTethersAndIsAppliedAgainAfterARedeployAndARecover() {
        deploy("recorded-app")
        await("records of the recorded tether") { query(recorded).isNotEmpty() }
        assertTrue(query(other).isEmpty())
        runBlocking {
            s.setRecording(SetRecordingRequest.newBuilder().setFabric(fabric).setAll(true).setDefaultRetention(DwhRetention.newBuilder().setMaxBytes(5000)).build())
        }
        await("records of the other tether") { query(other).isNotEmpty() }
        assertEquals(5000, partitions().single { it.name == other }.retention.maxBytes)

        // deployed again: a new fabric of the same id gets the mode again
        val before = query(other).size
        deploy("recorded-app")
        await("more records of the other tether after the redeploy") { query(other).size > before }

        // the engine restarts and the ManagementServer restores the fabric
        runBlocking {
            s.stopEngine(ref("e-a"))
            s.startEngine(ref("e-a"))
            s.recover(RecoverRequest.getDefaultInstance())
        }
        val afterRestore = query(other).size
        await("more records of the other tether after the restore") { query(other).size > afterRestore }

        // switched off: no more records
        runBlocking { s.setRecording(SetRecordingRequest.newBuilder().setFabric(fabric).setAll(false).build()) }
        Thread.sleep(600)
        val stopped = query(other).size
        Thread.sleep(1500)
        assertEquals(stopped, query(other).size)
    }

    @Test
    fun unknownFabricsAndBadRequestsAreRefused() {
        fun code(body: suspend () -> Unit) = assertThrows<StatusException> { runBlocking { body() } }.status.code
        assertEquals(Status.Code.NOT_FOUND, code { s.queryDwh(QueryDwhRequest.newBuilder().setFabric("nope").setKind(DwhKind.DWH_KIND_TETHER).setName("t").build()) })
        assertEquals(Status.Code.NOT_FOUND, code { s.listDwhPartitions(ListDwhPartitionsRequest.newBuilder().setFabric("nope").build()) })
        assertEquals(Status.Code.NOT_FOUND, code { s.setRecording(SetRecordingRequest.newBuilder().setFabric("nope").setAll(true).build()) })
        deploy("recorded-app")
        assertEquals(Status.Code.INVALID_ARGUMENT, code { s.queryDwh(QueryDwhRequest.newBuilder().setFabric(fabric).setName("t").build()) })
        assertEquals(Status.Code.INVALID_ARGUMENT, code { s.setDwhRetention(SetDwhRetentionRequest.newBuilder().setFabric(fabric).setName("t").build()) })
    }
}
