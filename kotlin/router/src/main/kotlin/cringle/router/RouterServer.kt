// SPDX-License-Identifier: Apache-2.0

package cringle.router

import com.google.protobuf.Timestamp
import cringle.common.v1.EngineId
import cringle.common.v1.FabricLifecycleState
import cringle.common.v1.FabricStateSummary
import cringle.router.v1.AddRemoteRouterRequest
import cringle.router.v1.AddRemoteRouterResponse
import cringle.router.v1.EngineEntry
import cringle.router.v1.ListEnginesRequest
import cringle.router.v1.ListEnginesResponse
import cringle.router.v1.ListRemoteRoutersRequest
import cringle.router.v1.ListRemoteRoutersResponse
import cringle.router.v1.LookupFabricRequest
import cringle.router.v1.LookupFabricResponse
import cringle.router.v1.RegisterEngineRequest
import cringle.router.v1.RegisterEngineResponse
import cringle.router.v1.RegistryServiceGrpcKt
import cringle.router.v1.RemoteRouterEntry
import cringle.router.v1.RemoveRemoteRouterRequest
import cringle.router.v1.RemoveRemoteRouterResponse
import cringle.router.v1.SendHeartbeatRequest
import cringle.router.v1.SendHeartbeatResponse
import cringle.router.v1.UnregisterEngineRequest
import cringle.router.v1.UnregisterEngineResponse
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Federation: asks remote routers for their local engines and caches the answer in the [registry]. */
public class RemoteRouters(
    private val registry: Registry,
    private val channel: (String) -> ManagedChannel = { address ->
        val (host, port) = address.substringBeforeLast(':') to address.substringAfterLast(':').toInt()
        NettyChannelBuilder.forAddress(host, port).usePlaintext().build()
    },
) {
    /** Refreshes the cache of the remote router at [address]; a failure is recorded, not thrown. */
    public suspend fun refresh(address: String) {
        val ch = try {
            channel(address)
        } catch (e: Exception) {
            registry.updateRemote(address, null, "cannot connect: ${e.message}")
            return
        }
        try {
            val response = RegistryServiceGrpcKt.RegistryServiceCoroutineStub(ch)
                .withDeadlineAfter(10, TimeUnit.SECONDS)
                .listEngines(ListEnginesRequest.newBuilder().setIncludeRemote(false).build())
            registry.updateRemote(
                address,
                response.enginesList.map { e ->
                    EngineRecord(
                        e.engineId.value, e.name, e.managementAddress,
                        e.fabricsList.map { FabricSummary(it.fabricId.value, it.blueprintName, it.state.name) },
                    ) to if (e.reachability == cringle.router.v1.Reachability.REACHABILITY_REACHABLE) Reachability.REACHABLE else Reachability.UNREACHABLE
                },
                null,
            )
        } catch (e: StatusException) {
            registry.updateRemote(address, null, "${e.status.code}: ${e.status.description ?: e.message}")
        } catch (e: io.grpc.StatusRuntimeException) {
            registry.updateRemote(address, null, "${e.status.code}: ${e.status.description ?: e.message}")
        } finally {
            ch.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    /** Refreshes all known remote routers. */
    public suspend fun refreshAll() {
        registry.remotes().forEach { refresh(it.address) }
    }

    /** Refreshes all remote routers every [interval] in [scope]. */
    public fun startPeriodic(scope: CoroutineScope, interval: Duration): Job = scope.launch {
        while (true) {
            refreshAll()
            delay(interval.toMillis())
        }
    }
}

/**
 * A router as a library: registry storage, gRPC server and federation. Plaintext on the loopback interface until trust
 * management exists (#13). The router can run in its own process or inside the daemon (Architecture 4.3).
 */
public class RouterServer(
    registryFile: Path,
    port: Int = 0,
    private val clock: Clock = Clock.systemUTC(),
    heartbeatTimeout: Duration = Duration.ofSeconds(15),
    private val refreshInterval: Duration = Duration.ofSeconds(30),
) {
    /** The registry of this router. */
    public val registry: Registry = Registry(FileRegistryStore(registryFile), clock, heartbeatTimeout)

    /** Federation with remote routers. */
    public val remoteRouters: RemoteRouters = RemoteRouters(registry)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server: Server = NettyServerBuilder
        .forAddress(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        .addService(Service(registry, remoteRouters))
        .build()

    /** The port the server listens on; valid after [start]. */
    public val port: Int get() = server.port

    /** Starts the server and the periodic refresh of remote routers. */
    public fun start(): RouterServer {
        server.start()
        remoteRouters.startPeriodic(scope, refreshInterval)
        return this
    }

    /** Stops the server and the periodic refresh. */
    public fun stop() {
        scope.cancel()
        server.shutdown()
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
    }

    private class Service(private val registry: Registry, private val remotes: RemoteRouters) :
        RegistryServiceGrpcKt.RegistryServiceCoroutineImplBase() {
        private val addressPattern = Regex("[A-Za-z0-9._-]+:[0-9]{1,5}")

        private fun ts(i: Instant?): Timestamp = if (i == null) Timestamp.getDefaultInstance() else Timestamp.newBuilder().setSeconds(i.epochSecond).setNanos(i.nano).build()

        private fun entry(v: EngineView): EngineEntry = EngineEntry.newBuilder()
            .setEngineId(EngineId.newBuilder().setValue(v.record.id))
            .setName(v.record.name)
            .setManagementAddress(v.record.managementAddress)
            .setReachability(
                if (v.reachability == Reachability.REACHABLE) cringle.router.v1.Reachability.REACHABILITY_REACHABLE else cringle.router.v1.Reachability.REACHABILITY_UNREACHABLE,
            )
            .setLastHeartbeat(ts(v.lastHeartbeat))
            .addAllFabrics(
                v.record.fabrics.map {
                    FabricStateSummary.newBuilder()
                        .setFabricId(cringle.common.v1.FabricId.newBuilder().setValue(it.fabricId))
                        .setBlueprintName(it.blueprint)
                        .setState(runCatching { FabricLifecycleState.valueOf(it.state) }.getOrDefault(FabricLifecycleState.FABRIC_LIFECYCLE_STATE_UNSPECIFIED))
                        .build()
                },
            )
            .setOriginRouter(v.record.origin.orEmpty())
            .build()

        private fun remoteEntry(r: RemoteRouterRecord): RemoteRouterEntry = RemoteRouterEntry.newBuilder()
            .setAddress(r.address)
            .setCachedEngines(r.engines.size)
            .setLastRefresh(ts(r.lastRefresh))
            .setLastError(r.lastError.orEmpty())
            .build()

        override suspend fun registerEngine(request: RegisterEngineRequest): RegisterEngineResponse {
            if (request.engineId.value.isBlank()) throw StatusException(Status.INVALID_ARGUMENT.withDescription("engine_id is required"))
            registry.register(request.engineId.value, request.name.ifBlank { request.engineId.value }, request.managementAddress)
            return RegisterEngineResponse.getDefaultInstance()
        }

        override suspend fun unregisterEngine(request: UnregisterEngineRequest): UnregisterEngineResponse {
            if (!registry.unregister(request.engineId.value)) throw StatusException(Status.NOT_FOUND.withDescription("engine '${request.engineId.value}' is not registered"))
            return UnregisterEngineResponse.getDefaultInstance()
        }

        override suspend fun sendHeartbeat(request: SendHeartbeatRequest): SendHeartbeatResponse {
            val hb = request.heartbeat
            try {
                registry.heartbeat(hb.engineId.value, hb.fabricStatesList.map { FabricSummary(it.fabricId.value, it.blueprintName, it.state.name) })
            } catch (e: EngineNotRegisteredException) {
                throw StatusException(Status.NOT_FOUND.withDescription(e.message))
            }
            return SendHeartbeatResponse.getDefaultInstance()
        }

        override suspend fun lookupFabric(request: LookupFabricRequest): LookupFabricResponse {
            val found = registry.lookupFabric(request.fabricId.value)
                ?: throw StatusException(Status.NOT_FOUND.withDescription("no engine runs fabric '${request.fabricId.value}'"))
            return LookupFabricResponse.newBuilder().setEngine(entry(found)).build()
        }

        override suspend fun listEngines(request: ListEnginesRequest): ListEnginesResponse =
            ListEnginesResponse.newBuilder()
                .addAllEngines(registry.engines(request.includeRemote, request.onlyReachable).map { entry(it) })
                .build()

        override suspend fun addRemoteRouter(request: AddRemoteRouterRequest): AddRemoteRouterResponse {
            if (!addressPattern.matches(request.address)) throw StatusException(Status.INVALID_ARGUMENT.withDescription("address must be host:port, got '${request.address}'"))
            registry.addRemote(request.address)
            remotes.refresh(request.address)
            return AddRemoteRouterResponse.newBuilder().setRouter(remoteEntry(registry.remotes().first { it.address == request.address })).build()
        }

        override suspend fun removeRemoteRouter(request: RemoveRemoteRouterRequest): RemoveRemoteRouterResponse {
            if (!registry.removeRemote(request.address)) throw StatusException(Status.NOT_FOUND.withDescription("remote router '${request.address}' is not known"))
            return RemoveRemoteRouterResponse.getDefaultInstance()
        }

        override suspend fun listRemoteRouters(request: ListRemoteRoutersRequest): ListRemoteRoutersResponse =
            ListRemoteRoutersResponse.newBuilder().addAllRouters(registry.remotes().map { remoteEntry(it) }).build()
    }
}
