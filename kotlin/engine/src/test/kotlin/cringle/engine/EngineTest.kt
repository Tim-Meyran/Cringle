// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import cringle.engine.v1.ConfigureRequest
import cringle.engine.v1.EngineManagementServiceGrpcKt.EngineManagementServiceCoroutineStub
import cringle.engine.v1.EngineState
import cringle.engine.v1.GetStatusRequest
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class EngineTest {
    @TempDir
    lateinit var home: Path

    private fun args(id: String, name: String? = null) = EngineArgs(id, name, home, 0)

    private fun <T> withClient(engine: Engine, body: suspend (EngineManagementServiceCoroutineStub) -> T): T {
        val channel: ManagedChannel = TestClient(home.resolve("client-tls")).channel(engine)
        try {
            return runBlocking { body(EngineManagementServiceCoroutineStub(channel)) }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun engineStartsWithoutAnyFlagAndItsManagementApiIsMtls() {
        // there is no unencrypted mode: the management API is mTLS and the router channel is mTLS (RegistryLinkTest)
        val engine = Engine.create(args("e1", "Edge"), emptyMap()).start()
        try {
            val status = withClient(engine) { it.getStatus(GetStatusRequest.getDefaultInstance()) }
            assertEquals("e1", status.engineId.value)
            assertEquals("Edge", status.name)
            assertEquals(EngineState.ENGINE_STATE_RUNNING, status.state)
        } finally {
            engine.stop()
        }
    }

    /** #60: the management API is mutual TLS; a caller whose key is not in the trust store of the engine is refused. */
    @Test
    fun theManagementApiRefusesAClientWithoutATrustEntry() {
        val engine = Engine.create(args("e1", "Edge"), emptyMap()).start()
        try {
            val stranger = TestClient(home.resolve("stranger-tls"))
            val channel = stranger.channelWithoutTrustEntry(engine)
            try {
                val e = assertThrows<io.grpc.StatusException> {
                    runBlocking { EngineManagementServiceCoroutineStub(channel).getStatus(GetStatusRequest.getDefaultInstance()) }
                }
                assertEquals(io.grpc.Status.Code.UNAVAILABLE, e.status.code)
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            }
        } finally {
            engine.stop()
        }
    }

    /** #60: a plaintext client cannot talk to the management API either. */
    @Test
    fun theManagementApiRefusesAPlaintextClient() {
        val engine = Engine.create(args("e1", "Edge"), emptyMap()).start()
        try {
            val channel = ManagedChannelBuilder.forAddress("127.0.0.1", engine.managementPort).usePlaintext().build()
            try {
                val e = assertThrows<io.grpc.StatusException> {
                    runBlocking { EngineManagementServiceCoroutineStub(channel).getStatus(GetStatusRequest.getDefaultInstance()) }
                }
                assertEquals(io.grpc.Status.Code.UNAVAILABLE, e.status.code)
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            }
        } finally {
            engine.stop()
        }
    }

    @Test
    fun statusReportsIdentityConfigAndCertificate() {
        val engine = Engine.create(args("e1", "Edge")).start()
        try {
            val status = withClient(engine) { it.getStatus(GetStatusRequest.getDefaultInstance()) }
            assertEquals("e1", status.engineId.value)
            assertEquals("Edge", status.name)
            assertEquals(EngineState.ENGINE_STATE_RUNNING, status.state)
            assertEquals(engine.identity.fingerprint, status.certificate.fingerprint)
            assertEquals("CN=engine:e1", status.certificate.subject)
            assertEquals("", status.routerAddress)
            assertTrue(status.startedAt.seconds > 0)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun configureChangesAndPersistsTheRouterAddress() {
        val engine = Engine.create(args("e1")).start()
        try {
            withClient(engine) {
                assertEquals("r:1", it.configure(ConfigureRequest.newBuilder().setRouterAddress("r:1").build()).routerAddress)
                assertEquals("r:1", it.getStatus(GetStatusRequest.getDefaultInstance()).routerAddress)
                // unset leaves the value unchanged
                assertEquals("r:1", it.configure(ConfigureRequest.getDefaultInstance()).routerAddress)
            }
        } finally {
            engine.stop()
        }
        assertEquals("r:1", EngineConfig.loadOrCreate(CringleHome.engineDir(home, "e1"), "e1", null).routerAddress)
        val again = Engine.create(args("e1")).start()
        try {
            withClient(again) {
                assertEquals("r:1", it.getStatus(GetStatusRequest.getDefaultInstance()).routerAddress)
                assertEquals("", it.configure(ConfigureRequest.newBuilder().setRouterAddress("").build()).routerAddress)
            }
        } finally {
            again.stop()
        }
    }

    @Test
    fun secondStartWithSameIdReusesConfigAndIdentity() {
        val first = Engine.create(args("e1", "Name")).start()
        val fingerprint = first.identity.fingerprint
        first.stop()
        val second = Engine.create(args("e1", "Ignored")).start()
        try {
            assertEquals(fingerprint, second.identity.fingerprint)
            assertEquals("Name", second.config.name)
        } finally {
            second.stop()
        }
    }

    @Test
    fun enginesWithDifferentIdsDoNotInterfere() {
        val a = Engine.create(args("a")).start()
        val b = Engine.create(args("b")).start()
        try {
            assertNotEquals(a.managementPort, b.managementPort)
            assertNotEquals(a.identity.fingerprint, b.identity.fingerprint)
            withClient(a) { it.configure(ConfigureRequest.newBuilder().setRouterAddress("only-a:1").build()) }
            assertEquals("", withClient(b) { it.getStatus(GetStatusRequest.getDefaultInstance()) }.routerAddress)
            assertEquals("b", withClient(b) { it.getStatus(GetStatusRequest.getDefaultInstance()) }.engineId.value)
            assertTrue(Files.isDirectory(home.resolve("engines/a")) && Files.isDirectory(home.resolve("engines/b")))
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun stopRemovesPortFileAndIsRepeatable() {
        val engine = Engine.create(args("e1")).start()
        val portFile = home.resolve("engines/e1/${Engine.PORT_FILE}")
        assertEquals(engine.managementPort.toString(), Files.readString(portFile).trim())
        engine.stop()
        assertFalse(Files.exists(portFile))
        engine.stop()
    }

    @Test
    fun malformedEnrollmentSecretThrowsEngineArgsException() {
        val malformed = listOf(
            "abc",                              // wrong length
            "A".repeat(64),                      // uppercase hex
            "z".repeat(64),                      // non-hex characters
        )
        for (value in malformed) {
            val ex = assertThrows<EngineArgsException> {
                Engine.create(args("e1"), mapOf("CRINGLE_ENROLLMENT_SECRET" to value))
            }
            assertTrue(
                ex.message!!.contains("CRINGLE_ENROLLMENT_SECRET") || ex.message!!.contains("64 lowercase hex"),
                "message should mention the variable or the format, got: ${ex.message}",
            )
        }
    }
}
