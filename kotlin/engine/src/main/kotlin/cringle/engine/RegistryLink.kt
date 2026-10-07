// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import cringle.common.v1.EngineHeartbeat
import cringle.common.v1.EngineId
import cringle.common.v1.FabricStateSummary
import cringle.router.v1.RegisterEngineRequest
import cringle.router.v1.RegistryServiceGrpcKt
import cringle.router.v1.SendHeartbeatRequest
import cringle.router.v1.UnregisterEngineRequest
import cringle.common.EngineTls
import cringle.common.TlsHelper
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/* *
 * Keeps an engine registered at a router: registers, sends heartbeats with the current fabric states, registers again
 * when the router has forgotten the engine (NOT_FOUND) and retries after connection errors.
 */
internal class RegistryLink(
    private val engineId: String,
    private val name: String,
    private val managementAddress: String,
    private val routerAddress: String,
    private val interval: Duration,
    private val fabrics: () -> List<FabricStateSummary>,
    private val tls: EngineTls,
    private var enrollmentSecret: ByteArray?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channel: ManagedChannel = createChannel()
    private val stub = RegistryServiceGrpcKt.RegistryServiceCoroutineStub(channel)
    private val id = EngineId.newBuilder().setValue(engineId).build()
    private var job: Job? = null

    /** Starts the register/heartbeat loop. */
    fun start() {
        job = scope.launch {
            var registered = false
            while (true) {
                try {
                    if (!registered) {
                        val builder = RegisterEngineRequest.newBuilder()
                            .setEngineId(id)
                            .setName(name)
                            .setManagementAddress(managementAddress)

                        // Add certificate and enrollment secret if using mTLS
                        builder.setCertificate(ByteString.copyFrom(tls.identity.certificate.encoded))
                        enrollmentSecret?.let { builder.setEnrollmentSecret(ByteString.copyFrom(it)) }

                        stub.registerEngine(builder.build())
                        registered = true
                        // the secret is one-time: clear it after a successful registration
                        enrollmentSecret = null
                    }
                    stub.sendHeartbeat(SendHeartbeatRequest.newBuilder().setHeartbeat(heartbeat()).build())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StatusException) {
                    // NOT_FOUND: the router lost the registration, register again. Anything else: retry later.
                    registered = registered && e.status.code != Status.Code.NOT_FOUND
                } catch (e: RuntimeException) {
                    registered = false
                }
                delay(interval.toMillis())
            }
        }
    }

    private fun heartbeat(): EngineHeartbeat {
        val now = Instant.now()
        return EngineHeartbeat.newBuilder()
            .setEngineId(id)
            .setTimestamp(Timestamp.newBuilder().setSeconds(now.epochSecond).setNanos(now.nano))
            .addAllFabricStates(fabrics())
            .build()
    }

    /** Stops the loop and unregisters from the router (best effort). */
    fun stop() {
        job?.cancel()
        try {
            runBlocking { stub.unregisterEngine(UnregisterEngineRequest.newBuilder().setEngineId(id).build()) }
        } catch (_: Exception) {
            // The router may be gone; the heartbeat timeout marks the engine unreachable then.
        }
        scope.cancel()
        channel.shutdown()
        if (!channel.awaitTermination(2, TimeUnit.SECONDS)) channel.shutdownNow()
    }

    private fun createChannel(): ManagedChannel {
        return NettyChannelBuilder.forTarget(routerAddress)
            .sslContext(TlsHelper.channelCredentials(tls.identity, tls.trustStore))
            .build()
    }
}
