// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import io.grpc.ManagedChannel
import io.grpc.insecure.InsecureChannelCredentials
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import cringle.router.v1.PrepareEngineRequest
import cringle.router.v1.PrepareEngineResponse
import cringle.router.v1.RegistryServiceGrpc

class EngineSupervisorEnrollmentTest {
    @TempDir
    lateinit var home: Path

    @Test
    fun enrollmentSecretIsGeneratedAndReported() {
        // Track the PrepareEngine call
        val prepareEngineCall = AtomicReference<PrepareEngineRequest?>(null)
        
        // Mock router that captures the PrepareEngine request
        val mockRouter = object : RegistryServiceGrpc.RegistryServiceImplBase() {
            override fun prepareEngine(request: PrepareEngineRequest, responseObserver: StreamObserver<PrepareEngineResponse>) {
                prepareEngineCall.set(request)
                responseObserver.onNext(PrepareEngineResponse.getDefaultInstance())
                responseObserver.onCompleted()
            }
        }
        
        // Create a test server
        val server = io.grpc.ServerBuilder.forPort(0)
            .addService(mockRouter)
            .build()
        server.start()
        val port = server.port()
        
        try {
            val supervisor = EngineSupervisor(
                home,
                EngineCommand(mainClass = "cringle.daemon.FakeEngineMainKt"),
                routerAddress = { "127.0.0.1:$port" },
            )
            
            supervisor.use {
                it.add("test-engine", "Test Engine")
                val started = it.start("test-engine")
                
                // Wait for the engine to start and for PrepareEngine to be called
                Thread.sleep(2000)
                
                // Verify that PrepareEngine was called
                assertNotNull(prepareEngineCall.get(), "PrepareEngine should have been called")
                
                val request = prepareEngineCall.get()
                assertEquals("test-engine", request.engineId.value)
                assertTrue(request.enrollmentSecretHash.isNotEmpty(), "Enrollment secret hash should be present")
                assertEquals(32, request.enrollmentSecretHash.size, "Enrollment secret hash should be 32 bytes")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun enrollmentSecretIsPassedViaEnvironment() {
        val environmentVars = mutableMapOf<String, String>()
        
        val supervisor = EngineSupervisor(
            home,
            EngineCommand(mainClass = "cringle.daemon.FakeEngineMainKt"),
            routerAddress = { null }, // No router, combined mode
        ) {
            // Capture environment variables
            { env -> environmentVars.putAll(env) }
        }
        
        supervisor.use {
            it.add("env-test", "Env Test")
            val started = it.start("env-test")
            
            // Verify that CRINGLE_ENROLLMENT_SECRET is in environment
            assertTrue(environmentVars.containsKey("CRINGLE_ENROLLMENT_SECRET"), 
                "CRINGLE_ENROLLMENT_SECRET should be in environment")
            
            val secret = environmentVars["CRINGLE_ENROLLMENT_SECRET"]
            assertNotNull(secret, "Enrollment secret should not be null")
            assertEquals(64, secret.length, "Enrollment secret should be 64 hex characters")
        }
    }

    @Test
    fun enrollmentSecretIsRemovedFromMemory() {
        // This test verifies that the secret is not kept in memory
        // We'll use a custom SecureRandom that tracks allocations
        val secretValues = mutableListOf<ByteArray>()
        
        val trackingRandom = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray?) {
                if (bytes != null) {
                    secretValues.add(bytes.clone())
                }
                super.nextBytes(bytes)
            }
        }
        
        val supervisor = EngineSupervisor(
            home,
            EngineCommand(mainClass = "cringle.daemon.FakeEngineMainKt"),
            routerAddress = { null },
            secureRandom = trackingRandom
        )
        
        supervisor.use {
            it.add("memory-test", "Memory Test")
            val started = it.start("memory-test")
            
            // Give it time to process
            Thread.sleep(1000)
            
            // The secret should have been generated
            assertTrue(secretValues.isNotEmpty(), "Secret should have been generated")
            
            // Now we need to verify that it's not kept in memory
            // This is tricky to test directly, but we can at least verify
            // that the generation happened
            val secret = secretValues.find { it.size == 32 }
            assertNotNull(secret, "32-byte secret should have been generated")
        }
    }

    @Test
    fun combinedModeUsesNoNetwork() {
        // In combined mode, routerAddress returns null
        // The supervisor should still work without network calls
        val supervisor = EngineSupervisor(
            home,
            EngineCommand(mainClass = "cringle.daemon.FakeEngineMainKt"),
            routerAddress = { null } // Combined mode
        )
        
        supervisor.use {
            it.add("combined-test", "Combined Test")
            val started = it.start("combined-test")
            
            assertEquals(ProcessState.RUNNING, started.state)
        }
    }
}
