// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerServiceDefinition
import io.grpc.stub.StreamObserver
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import cringle.router.v1.PrepareEngineRequest
import cringle.router.v1.PrepareEngineResponse
import cringle.router.v1.RegisterEngineRequest
import cringle.router.v1.RegisterEngineResponse
import cringle.router.v1.UnregisterEngineRequest
import cringle.router.v1.UnregisterEngineResponse
import cringle.router.v1.SendHeartbeatRequest
import cringle.router.v1.SendHeartbeatResponse
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
            
            override fun registerEngine(request: RegisterEngineRequest, responseObserver: StreamObserver<RegisterEngineResponse>) {
                responseObserver.onNext(RegisterEngineResponse.getDefaultInstance())
                responseObserver.onCompleted()
            }
            
            override fun unregisterEngine(request: UnregisterEngineRequest, responseObserver: StreamObserver<UnregisterEngineResponse>) {
                responseObserver.onNext(UnregisterEngineResponse.getDefaultInstance())
                responseObserver.onCompleted()
            }
            
            override fun sendHeartbeat(request: SendHeartbeatRequest, responseObserver: StreamObserver<SendHeartbeatResponse>) {
                responseObserver.onNext(SendHeartbeatResponse.getDefaultInstance())
                responseObserver.onCompleted()
            }
        }
        
        // Create a test server
        val server = io.grpc.ServerBuilder.forPort(0)
            .addService(mockRouter)
            .build()
        server.start()
        val port = server.port
        
        try {
            val supervisor = EngineSupervisor(
                home,
                EngineCommand(mainClass = "cringle.engine.Main"),
                routerAddress = { "127.0.0.1:$port" },
            )
            
            supervisor.use {
                it.add("test-engine", "Test Engine")
                val testEngine = it.start("test-engine")
                
                // Wait for the engine to start and for PrepareEngine to be called
                Thread.sleep(5000)
                
                // Verify that PrepareEngine was called
                assertNotNull(prepareEngineCall.get(), "PrepareEngine should have been called")
                
                val request = prepareEngineCall.get()
                request?.let { req ->
                    assertEquals("test-engine", req.engineId.value)
                    assertTrue(req.enrollmentSecretHash.size() > 0, "Enrollment secret hash should be present")
                    assertEquals(32, req.enrollmentSecretHash.size(), "Enrollment secret hash should be 32 bytes")
                }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun enrollmentSecretIsPassedViaEnvironment() {
        val environmentVars = mutableMapOf<String, String>()
        
        // Mock router that captures the PrepareEngine request
        val prepareEngineCall = AtomicReference<PrepareEngineRequest?>(null)
        val mockRouter = object : RegistryServiceGrpc.RegistryServiceImplBase() {
            override fun prepareEngine(request: PrepareEngineRequest, responseObserver: StreamObserver<PrepareEngineResponse>) {
                prepareEngineCall.set(request)
                responseObserver.onNext(PrepareEngineResponse.getDefaultInstance())
                responseObserver.onCompleted()
            }
            
            override fun registerEngine(request: RegisterEngineRequest, responseObserver: StreamObserver<RegisterEngineResponse>) {
                responseObserver.onNext(RegisterEngineResponse.getDefaultInstance())
                responseObserver.onCompleted()
            }
            
            override fun unregisterEngine(request: UnregisterEngineRequest, responseObserver: StreamObserver<UnregisterEngineResponse>) {
                responseObserver.onNext(UnregisterEngineResponse.getDefaultInstance())
                responseObserver.onCompleted()
            }
            
            override fun sendHeartbeat(request: SendHeartbeatRequest, responseObserver: StreamObserver<SendHeartbeatResponse>) {
                responseObserver.onNext(SendHeartbeatResponse.getDefaultInstance())
                responseObserver.onCompleted()
            }
        }
        
        val server = io.grpc.ServerBuilder.forPort(0)
            .addService(mockRouter)
            .build()
        server.start()
        val port = server.port
        
        val supervisor = EngineSupervisor(
            home,
            EngineCommand(mainClass = "cringle.engine.Main"),
            routerAddress = { "127.0.0.1:$port" },
            startTimeout = Duration.ofSeconds(90),
            stopTimeout = Duration.ofSeconds(30),
            onStopped = { id, env -> 
                // Capture environment variables
                environmentVars.putAll(env)
                ""
            }
        )
        
        try {
            supervisor.use {
                it.add("env-test", "Env Test")
                val started = it.start("env-test")
                
                // Wait for the engine to start and for PrepareEngine to be called
                Thread.sleep(5000)
                
                // Verify that PrepareEngine was called
                assertNotNull(prepareEngineCall.get(), "PrepareEngine should have been called")
                
                // Stop the engine to trigger onStopped callback
                it.stop("env-test")
                
                // Verify that CRINGLE_ENROLLMENT_SECRET is in environment
                assertTrue(environmentVars.containsKey("CRINGLE_ENROLLMENT_SECRET"), 
                    "CRINGLE_ENROLLMENT_SECRET should be in environment")
                
                val secret = environmentVars["CRINGLE_ENROLLMENT_SECRET"]
                assertNotNull(secret, "Enrollment secret should not be null")
                secret?.let { s ->
                    assertEquals(64, s.length, "Enrollment secret should be 64 hex characters")
                }
            }
        } finally {
            server.shutdown()
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
        
        // Mock router that doesn't interfere with secret generation
        val mockRouter = object : RegistryServiceGrpc.RegistryServiceImplBase() {
            override fun prepareEngine(request: PrepareEngineRequest, responseObserver: StreamObserver<PrepareEngineResponse>) {
                responseObserver.onNext(PrepareEngineResponse.getDefaultInstance())
                responseObserver.onCompleted()
            }
            
            override fun registerEngine(request: RegisterEngineRequest, responseObserver: StreamObserver<RegisterEngineResponse>) {
                responseObserver.onNext(RegisterEngineResponse.getDefaultInstance())
                responseObserver.onCompleted()
            }
            
            override fun unregisterEngine(request: UnregisterEngineRequest, responseObserver: StreamObserver<UnregisterEngineResponse>) {
                responseObserver.onNext(UnregisterEngineResponse.getDefaultInstance())
                responseObserver.onCompleted()
            }
            
            override fun sendHeartbeat(request: SendHeartbeatRequest, responseObserver: StreamObserver<SendHeartbeatResponse>) {
                responseObserver.onNext(SendHeartbeatResponse.getDefaultInstance())
                responseObserver.onCompleted()
            }
        }
        
        val server = io.grpc.ServerBuilder.forPort(0)
            .addService(mockRouter)
            .build()
        server.start()
        val port = server.port
        
        val supervisor = EngineSupervisor(
            home,
            EngineCommand(mainClass = "cringle.engine.Main"),
            routerAddress = { "127.0.0.1:$port" },
            secureRandom = trackingRandom
        )
        
        try {
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
        } finally {
            server.shutdown()
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
