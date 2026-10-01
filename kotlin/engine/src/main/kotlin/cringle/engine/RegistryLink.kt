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
import cringle.common.Identity
import cringle.common.TlsHelper
import cringle.common.TrustStore
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channel: ManagedChannel = createChannel()
    private val stub = RegistryServiceGrpcKt.RegistryServiceCoroutineStub(channel)
    private val id = EngineId.newBuilder().setValue(engineId).build()
    private var job: Job? = null

    /** Creates a RegistryLink with mTLS support.
     * 
     * @param engineId the engine ID
     * @param name the engine name
     * @param managementAddress the management address
     * @param routerAddress the router address
     * @param interval the heartbeat interval
     * @param fabrics function to get current fabric states
     * @param identity the engine's identity for TLS
     * @param trustStore the trust store for router verification
     * @param enrollmentSecret the one-time enrollment secret (optional, only for first registration)
     */
    internal constructor(
        engineId: String,
        name: String,
        managementAddress: String,
        routerAddress: String,
        interval: Duration,
        fabrics: () -> List<FabricStateSummary>,
        identity: Identity,
        trustStore: TrustStore,
        enrollmentSecret: ByteArray?,
    ) : this(engineId, name, managementAddress, routerAddress, interval, fabrics) {
        this._identity = identity
        this._trustStore = trustStore
        this._enrollmentSecret = enrollmentSecret
    }
    
    private var _identity: Identity? = null
    private var _trustStore: TrustStore? = null
    private var _enrollmentSecret: ByteArray? = null
    
    private val identity: Identity?
        get() = _identity
    private val trustStore: TrustStore?
        get() = _trustStore
    private val enrollmentSecret: ByteArray?
        get() = _enrollmentSecret

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
                        if (_identity != null && _trustStore != null) {
                            builder.setCertificate(_identity!!.certificate.encoded)
                            _enrollmentSecret?.let { builder.setEnrollmentSecret(ByteString.copyFrom(it)) }
                        }
                        
                        stub.registerEngine(builder.build())
                        registered = true
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
        return if (_identity != null && _trustStore != null) {
            NettyChannelBuilder.forTarget(routerAddress)
                .apply {
                    sslContext(TlsHelper.channelCredentials(_identity!!, _trustStore!!))
                }
                .build()
        } else {
            NettyChannelBuilder.forTarget(routerAddress).usePlaintext().build()
        }
    }
}
