// SPDX-License-Identifier: Apache-2.0

package cringle.common

import com.google.protobuf.timestamp
import cringle.common.v1.BlockId
import cringle.common.v1.CertificateInfo
import cringle.common.v1.CringleError
import cringle.common.v1.EngineHeartbeat
import cringle.common.v1.EngineId
import cringle.common.v1.EngineMetrics
import cringle.common.v1.FabricId
import cringle.common.v1.FabricLifecycleState
import cringle.common.v1.FabricStateSummary
import cringle.common.v1.Hash
import cringle.common.v1.HashAlgorithm
import cringle.common.v1.MachineId
import cringle.common.v1.PaginationRequest
import cringle.common.v1.PaginationResponse
import cringle.common.v1.PluginRef
import cringle.common.v1.ProjectRef
import cringle.common.v1.Version
import cringle.common.v1.VersionRange
import cringle.common.v1.blockId
import cringle.common.v1.certificateInfo
import cringle.common.v1.cringleError
import cringle.common.v1.engineHeartbeat
import cringle.common.v1.engineId
import cringle.common.v1.engineMetrics
import cringle.common.v1.fabricId
import cringle.common.v1.fabricStateSummary
import cringle.common.v1.hash
import cringle.common.v1.machineId
import cringle.common.v1.paginationRequest
import cringle.common.v1.paginationResponse
import cringle.common.v1.pluginRef
import cringle.common.v1.projectRef
import cringle.common.v1.version
import cringle.common.v1.versionRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class CommonProtoTypesTest {

    @Test
    fun testIdentifiers() {
        val engine: EngineId = engineId { value = "engine-1" }
        val machine: MachineId = machineId { value = "machine-1" }
        val fabric: FabricId = fabricId { value = "fabric-1" }
        val block: BlockId = blockId { value = "block-1" }
        val project: ProjectRef = projectRef {
            name = "my-project"
            version = "1.0.0"
        }
        val plugin: PluginRef = pluginRef {
            name = "my-plugin"
            version = "2.0.0"
        }

        assertEquals("engine-1", engine.value)
        assertEquals("machine-1", machine.value)
        assertEquals("fabric-1", fabric.value)
        assertEquals("block-1", block.value)
        assertEquals("my-project", project.name)
        assertEquals("1.0.0", project.version)
        assertEquals("my-plugin", plugin.name)
        assertEquals("2.0.0", plugin.version)
    }

    @Test
    fun testVersionAndRange() {
        val v: Version = version {
            raw = "1.2.3-beta.1+build.42"
            major = 1
            minor = 2
            patch = 3
            preRelease = "beta.1"
            buildMetadata = "build.42"
        }
        val range: VersionRange = versionRange {
            expression = "^1.2.0"
        }

        assertEquals("1.2.3-beta.1+build.42", v.raw)
        assertEquals(1, v.major)
        assertEquals(2, v.minor)
        assertEquals(3, v.patch)
        assertEquals("beta.1", v.preRelease)
        assertEquals("build.42", v.buildMetadata)
        assertEquals("^1.2.0", range.expression)
    }

    @Test
    fun testHash() {
        val h: Hash = hash {
            algorithm = HashAlgorithm.HASH_ALGORITHM_SHA256
            digest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        }

        assertEquals(HashAlgorithm.HASH_ALGORITHM_SHA256, h.algorithm)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", h.digest)
    }

    @Test
    fun testCertificateInfo() {
        val cert: CertificateInfo = certificateInfo {
            fingerprint = "SHA256:abc123"
            subject = "CN=engine-1"
            issuer = "CN=cringle-ca"
            serialNumber = "123456789"
            notBefore = timestamp { seconds = 1000 }
            notAfter = timestamp { seconds = 2000 }
        }

        assertEquals("SHA256:abc123", cert.fingerprint)
        assertEquals("CN=engine-1", cert.subject)
        assertEquals(1000L, cert.notBefore.seconds)
        assertEquals(2000L, cert.notAfter.seconds)
    }

    @Test
    fun testErrorAndPagination() {
        val error: CringleError = cringleError {
            code = "NOT_FOUND"
            message = "Resource not found"
            details["resourceId"] = "res-123"
        }
        val pageReq: PaginationRequest = paginationRequest {
            pageSize = 50
            pageToken = "tok-1"
        }
        val pageResp: PaginationResponse = paginationResponse {
            nextPageToken = "tok-2"
            totalItems = 100
        }

        assertEquals("NOT_FOUND", error.code)
        assertEquals("Resource not found", error.message)
        assertEquals("res-123", error.detailsMap["resourceId"])
        assertEquals(50, pageReq.pageSize)
        assertEquals("tok-1", pageReq.pageToken)
        assertEquals("tok-2", pageResp.nextPageToken)
        assertEquals(100L, pageResp.totalItems)
    }

    @Test
    fun testEngineHeartbeat() {
        val hb: EngineHeartbeat = engineHeartbeat {
            engineId = engineId { value = "engine-1" }
            timestamp = timestamp { seconds = 1700000000 }
            fabricStates += fabricStateSummary {
                fabricId = fabricId { value = "fabric-1" }
                state = FabricLifecycleState.FABRIC_LIFECYCLE_STATE_RUNNING
                blueprintName = "main-blueprint"
            }
            metrics = engineMetrics {
                cpuUsagePercent = 12.5
                memoryUsedBytes = 1024 * 1024 * 64
                memoryMaxBytes = 1024 * 1024 * 512
                tetherMessagesPerSec = 450
            }
        }

        assertNotNull(hb)
        assertEquals("engine-1", hb.engineId.value)
        assertEquals(1, hb.fabricStatesCount)
        assertEquals(FabricLifecycleState.FABRIC_LIFECYCLE_STATE_RUNNING, hb.fabricStatesList[0].state)
        assertEquals("main-blueprint", hb.fabricStatesList[0].blueprintName)
        assertEquals(12.5, hb.metrics.cpuUsagePercent)
    }
}
