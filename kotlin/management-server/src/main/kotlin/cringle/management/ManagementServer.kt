// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.common.TlsHelper
import cringle.common.v1.EngineId
import cringle.common.v1.FabricId
import cringle.management.v1.AddMachineRequest
import cringle.management.v1.AddTrustedComponentRequest
import cringle.management.v1.RemoveTrustRequest
import cringle.management.v1.RemoveTrustResponse
import cringle.management.v1.CreateEngineRequest
import cringle.management.v1.DeleteEngineRequest
import cringle.management.v1.DeleteEngineResponse
import cringle.management.v1.DeployFabricRequest
import cringle.management.v1.EngineRef
import cringle.management.v1.FabricRef
import cringle.management.v1.ListEnginesRequest
import cringle.management.v1.ListEnginesResponse
import cringle.management.v1.ListFabricsRequest
import cringle.management.v1.ListFabricsResponse
import cringle.management.v1.ListMachinesRequest
import cringle.management.v1.ListMachinesResponse
import cringle.management.v1.MachineInfo
import cringle.management.v1.MachineRequest
import cringle.management.v1.ManagedEngine
import cringle.management.v1.ManagedFabric
import cringle.management.v1.ManagedLogEntry
import cringle.management.v1.ManagementServiceGrpcKt
import cringle.management.v1.QueryLogsRequest
import cringle.management.v1.QueryLogsResponse
import cringle.management.v1.RecoverRequest
import cringle.management.v1.RecoverResponse
import cringle.management.v1.RemoveFabricResponse
import cringle.management.v1.RemoveMachineResponse
import cringle.repository.v1.DownloadRequest
import cringle.repository.v1.DownloadResponse
import cringle.repository.v1.GetPackageRequest
import cringle.repository.v1.ListPackagesRequest
import cringle.repository.v1.ListPackagesResponse
import cringle.repository.v1.ListVersionsRequest
import cringle.repository.v1.ListVersionsResponse
import cringle.repository.v1.PackageMetadata
import cringle.repository.v1.PackageMetadataList
import cringle.repository.v1.PublishRequest
import cringle.repository.v1.PublishResponse
import cringle.repository.v1.SetPluginTrustRequest
import cringle.router.users.AuthInterceptor
import cringle.router.users.Permission
import cringle.router.users.UserManager
import cringle.router.v1.AddRemoteRouterRequest
import cringle.router.v1.AddRemoteRouterResponse
import cringle.router.v1.ListRemoteRoutersRequest
import cringle.router.v1.ListRemoteRoutersResponse
import cringle.router.v1.ListTrustRequest
import cringle.router.v1.ListTrustResponse
import cringle.router.v1.TrustEntryInfo
import cringle.router.v1.RemoveRemoteRouterRequest
import cringle.router.v1.RemoveRemoteRouterResponse
import io.grpc.Server
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The gRPC facade of the [ManagementCore]. The server speaks TLS 1.3 with the identity of [core]. Without [users] the API is open to everybody who can connect; with [users] every
 * method needs the permission listed in [REQUIRED_PERMISSIONS]. On [start] the recorded Engines and fabrics are
 * recovered in the background if [recoverOnStart] is set. With [users] it also serves the user management service (`cringle.user.v1.UserService`); [recovery] gives the result.
 */
public class ManagementServer(
    public val core: ManagementCore,
    port: Int = 0,
    users: UserManager? = null,
    private val recoverOnStart: Boolean = true,
    /** If set, package versions that no fabric uses and that were not used for this many days are removed regularly. */
    private val cacheMaxUnusedDays: Long? = null,
    private val cleanupInterval: java.time.Duration = java.time.Duration.ofHours(1),
    /** Called with the token of every authenticated call; used to delete the bootstrap token file after its first use. */
    onAuthenticated: (token: String) -> Unit = {},
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val service = Service()
    private val server: Server = NettyServerBuilder
        .forAddress(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        .maxInboundMessageSize(1024 * 1024)
        // TLS 1.3 with the identity of the server; a client needs no certificate (the user is authenticated by the token), the
        // CLI pins the fingerprint of the key. A client that does present a certificate is checked for validity only.
        .sslContext(TlsHelper.serverCredentials(core.identity, core.trustStore, requireTrustedClients = false))
        .addService(
            service.bindService().let { definition ->
                if (users == null) definition else ServerInterceptors.intercept(definition, AuthInterceptor(users, REQUIRED_PERMISSIONS, onAuthenticated = onAuthenticated))
            },
        )
        .apply {
            // user management (#22) is served here, so that clients need one address only
            if (users != null) {
                addService(ServerInterceptors.intercept(cringle.router.users.UserGrpcService(users).bindService(), AuthInterceptor(users, cringle.router.users.UserGrpcService.REQUIRED_PERMISSIONS, onAuthenticated = onAuthenticated)))
            }
        }
        .build()

    /** The result of the recovery that ran at [start]; `null` before start or if recovery is switched off. */
    @Volatile
    public var recovery: Deferred<RecoveryReport>? = null
        private set

    /** The port of the server; valid after [start]. */
    public val port: Int get() = server.port

    /** Starts the server and, in the background, the recovery. */
    public fun start(): ManagementServer {
        server.start()
        if (recoverOnStart) recovery = scope.async { core.recover() }
        if (cacheMaxUnusedDays != null) {
            scope.launch {
                while (true) {
                    delay(cleanupInterval.toMillis())
                    runCatching { core.cleanupCache(null, cacheMaxUnusedDays * 86_400) }
                }
            }
        }
        return this
    }

    /** Stops the server. Engines and fabrics keep running. */
    override fun close() {
        scope.cancel()
        server.shutdown()
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) server.shutdownNow()
        core.close()
    }

    private fun ref(machine: String, engine: String) = EngineRef.newBuilder().setMachineId(machine).setEngineId(EngineId.newBuilder().setValue(engine)).build()

    private fun machineInfo(v: MachineView) = MachineInfo.newBuilder()
        .setMachineId(v.record.id).setDaemonAddress(v.record.daemonAddress).setHost(v.record.host)
        .setRepositoryAddress(v.record.repositoryAddress.orEmpty()).setReachable(v.reachable).setLastError(v.lastError).build()

    private fun engineInfo(v: EngineView): ManagedEngine {
        val b = ManagedEngine.newBuilder().setMachineId(v.machine).setProcess(v.process).setAutostart(v.autostart).addAllRoles(v.roles).putAllLabels(v.labels)
        if (v.status != null) b.setStatus(v.status)
        return b.build()
    }

    private fun fabricInfo(v: FabricView) = ManagedFabric.newBuilder()
        .setMachineId(v.machine).setEngineId(EngineId.newBuilder().setValue(v.engineId)).setInfo(v.info).setDesiredRunning(v.desiredRunning).build()

    private inner class Service : ManagementServiceGrpcKt.ManagementServiceCoroutineImplBase() {
        private suspend fun <T> guard(body: suspend () -> T): T = try {
            body()
        } catch (e: ManagementException) {
            throw StatusException(e.code.toStatus().withDescription(e.message))
        }

        override suspend fun addMachine(request: AddMachineRequest): MachineInfo = guard {
            machineInfo(core.addMachine(request.machineId, request.daemonAddress, request.host, request.repositoryAddress))
        }

        override suspend fun removeMachine(request: MachineRequest): RemoveMachineResponse = guard {
            core.removeMachine(request.machineId)
            RemoveMachineResponse.getDefaultInstance()
        }

        override suspend fun listMachines(request: ListMachinesRequest): ListMachinesResponse =
            ListMachinesResponse.newBuilder().addAllMachines(core.listMachines().map(::machineInfo)).build()

        override suspend fun createEngine(request: CreateEngineRequest): ManagedEngine = guard {
            engineInfo(
                core.createEngine(
                    request.machineId, request.engineId, request.name, if (request.hasAutostart()) request.autostart else true,
                    request.rolesList, request.labelsMap,
                ),
            )
        }

        override suspend fun setEngineTags(request: cringle.management.v1.SetEngineTagsRequest): ManagedEngine = guard {
            engineInfo(core.setEngineTags(request.engine.machineId, request.engine.engineId.value, request.rolesList, request.labelsMap))
        }

        override suspend fun deploy(request: cringle.management.v1.DeployProjectRequest): cringle.management.v1.DeployProjectResponse = guard {
            val r = core.deploy(request.project, request.versionRange, if (request.hasStart()) request.start else true, request.relock)
            cringle.management.v1.DeployProjectResponse.newBuilder().setProject(r.project).setVersion(r.version).setLock(r.lock).addAllFabrics(r.fabrics.map(::fabricInfo)).build()
        }

        override suspend fun undeploy(request: cringle.management.v1.UndeployRequest): cringle.management.v1.UndeployResponse = guard {
            cringle.management.v1.UndeployResponse.newBuilder().addAllRemoved(core.undeploy(request.project)).build()
        }

        override suspend fun cleanupCache(request: cringle.management.v1.CleanupCacheRequest): cringle.management.v1.CleanupCacheResponse = guard {
            val (removed, problems) = core.cleanupCache(request.machineId, request.minUnusedSeconds)
            cringle.management.v1.CleanupCacheResponse.newBuilder().addAllRemoved(removed).addAllProblems(problems).build()
        }

        override suspend fun startEngine(request: EngineRef): ManagedEngine = guard { engineInfo(core.startEngine(request.machineId, request.engineId.value)) }

        override suspend fun stopEngine(request: EngineRef): ManagedEngine = guard { engineInfo(core.stopEngine(request.machineId, request.engineId.value)) }

        override suspend fun deleteEngine(request: DeleteEngineRequest): DeleteEngineResponse = guard {
            core.deleteEngine(request.engine.machineId, request.engine.engineId.value, request.deleteData)
            DeleteEngineResponse.getDefaultInstance()
        }

        override suspend fun listEngines(request: ListEnginesRequest): ListEnginesResponse = guard {
            ListEnginesResponse.newBuilder().addAllEngines(core.listEngines(request.machineId).map(::engineInfo)).build()
        }

        override suspend fun getEngine(request: EngineRef): ManagedEngine = guard { engineInfo(core.getEngine(request.machineId, request.engineId.value)) }

        override suspend fun deployFabric(request: DeployFabricRequest): ManagedFabric = guard {
            fabricInfo(core.deployFabric(request.engine.machineId, request.engine.engineId.value, request.deploy, request.start))
        }

        override suspend fun startFabric(request: FabricRef): ManagedFabric = guard {
            fabricInfo(core.startFabric(request.engine.machineId, request.engine.engineId.value, request.fabricId.value))
        }

        override suspend fun stopFabric(request: FabricRef): ManagedFabric = guard {
            fabricInfo(core.stopFabric(request.engine.machineId, request.engine.engineId.value, request.fabricId.value))
        }

        override suspend fun removeFabric(request: FabricRef): RemoveFabricResponse = guard {
            core.removeFabric(request.engine.machineId, request.engine.engineId.value, request.fabricId.value)
            RemoveFabricResponse.getDefaultInstance()
        }

        override suspend fun getFabric(request: FabricRef): ManagedFabric = guard {
            fabricInfo(core.getFabric(request.engine.machineId, request.engine.engineId.value, request.fabricId.value))
        }

        override suspend fun bind(request: cringle.management.v1.Binding): cringle.management.v1.Binding = guard {
            bindingInfo(core.bind(request.consumerProject, request.service, request.targetsList))
        }

        override suspend fun unbind(request: cringle.management.v1.UnbindRequest): cringle.management.v1.UnbindResponse = guard {
            core.unbind(request.consumerProject, request.service)
            cringle.management.v1.UnbindResponse.getDefaultInstance()
        }

        override suspend fun listBindings(request: cringle.management.v1.ListBindingsRequest): cringle.management.v1.ListBindingsResponse = guard {
            cringle.management.v1.ListBindingsResponse.newBuilder().addAllBindings(core.listBindings(request.consumerProject).map(::bindingInfo)).build()
        }

        private fun bindingInfo(b: BindingRecord): cringle.management.v1.Binding =
            cringle.management.v1.Binding.newBuilder().setConsumerProject(b.consumerProject).setService(b.service).addAllTargets(b.targets).build()

        override suspend fun listFabrics(request: ListFabricsRequest): ListFabricsResponse = guard {
            val e = request.engine
            ListFabricsResponse.newBuilder().addAllFabrics(core.listFabrics(e.machineId, e.engineId.value).map(::fabricInfo)).build()
        }

        override suspend fun queryDwh(request: cringle.management.v1.QueryDwhRequest): cringle.management.v1.QueryDwhResponse = guard {
            cringle.management.v1.QueryDwhResponse.newBuilder().addAllRecords(core.queryDwh(request).recordsList).build()
        }

        override suspend fun listDwhPartitions(request: cringle.management.v1.ListDwhPartitionsRequest): cringle.management.v1.ListDwhPartitionsResponse = guard {
            val (partitions, problems) = core.listDwhPartitions(request.fabric)
            cringle.management.v1.ListDwhPartitionsResponse.newBuilder()
                .addAllPartitions(
                    partitions.map { (machine, engine, p) ->
                        cringle.management.v1.ManagedDwhPartition.newBuilder().setMachineId(machine).setEngineId(EngineId.newBuilder().setValue(engine)).setPartition(p).build()
                    },
                )
                .addAllProblems(problems)
                .build()
        }

        override suspend fun setRecording(request: cringle.management.v1.SetRecordingRequest): cringle.management.v1.SetRecordingResponse = guard {
            core.setRecording(request.fabric, request.all, if (request.hasDefaultRetention()) request.defaultRetention else null)
            cringle.management.v1.SetRecordingResponse.getDefaultInstance()
        }

        override suspend fun setDwhRetention(request: cringle.management.v1.SetDwhRetentionRequest): cringle.management.v1.SetDwhRetentionResponse = guard {
            core.setDwhRetention(request)
            cringle.management.v1.SetDwhRetentionResponse.getDefaultInstance()
        }

        override suspend fun setLogCollection(request: cringle.management.v1.SetLogCollectionRequest): cringle.management.v1.SetLogCollectionResponse = guard {
            core.setLogCollection(request.engine.machineId, request.engine.engineId.value, request.enabled)
            cringle.management.v1.SetLogCollectionResponse.getDefaultInstance()
        }

        override suspend fun getMetrics(request: cringle.management.v1.GetMetricsRequest): cringle.management.v1.GetMetricsResponse = guard {
            val result = core.getMetrics(request.engine.machineId, request.engine.engineId.value)
            cringle.management.v1.GetMetricsResponse.newBuilder()
                .addAllMetrics(
                    result.metrics.map {
                        cringle.management.v1.ManagedMetrics.newBuilder().setMachineId(it.machine).setEngineId(EngineId.newBuilder().setValue(it.engineId)).setMetrics(it.metrics).build()
                    },
                )
                .addAllProblems(result.problems)
                .build()
        }

        override suspend fun queryLogs(request: QueryLogsRequest): QueryLogsResponse = guard {
            val engineRequest = cringle.engine.v1.QueryLogsRequest.newBuilder()
                .setFabric(request.fabric).setBlock(request.block).setMinLevel(request.minLevel).setLimit(request.limit)
            if (request.hasSince()) engineRequest.since = request.since
            val result = core.queryLogs(request.engine.machineId, request.engine.engineId.value, engineRequest.build())
            QueryLogsResponse.newBuilder()
                .addAllEntries(result.entries.map { ManagedLogEntry.newBuilder().setMachineId(it.machine).setEngineId(EngineId.newBuilder().setValue(it.engineId)).setEntry(it.entry).setCollected(it.collected).build() })
                .addAllProblems(result.problems)
                .build()
        }

        override suspend fun addRemoteRouter(request: AddRemoteRouterRequest): AddRemoteRouterResponse = guard { core.router().addRemoteRouter(request) }

        override suspend fun removeRemoteRouter(request: RemoveRemoteRouterRequest): RemoveRemoteRouterResponse = guard { core.router().removeRemoteRouter(request) }

        override suspend fun listRemoteRouters(request: ListRemoteRoutersRequest): ListRemoteRoutersResponse = guard { core.router().listRemoteRouters(request) }

        override suspend fun listTrust(request: ListTrustRequest): ListTrustResponse = guard { ListTrustResponse.newBuilder().addAllEntries(core.listTrust()).build() }

        override suspend fun addTrustedComponent(request: AddTrustedComponentRequest): TrustEntryInfo =
            guard { core.addTrustedComponent(request.fingerprint, request.name, request.kind, request.address) }

        override suspend fun removeTrust(request: RemoveTrustRequest): RemoveTrustResponse =
            guard { RemoveTrustResponse.newBuilder().setRemovedEntries(core.removeTrust(request.fingerprint)).build() }

        override suspend fun publishPackage(requests: Flow<PublishRequest>): PublishResponse = guard { core.repository().publishPackage(requests) }

        override suspend fun listPackages(request: ListPackagesRequest): ListPackagesResponse = guard { core.repository().listPackages(request) }

        override suspend fun listVersions(request: ListVersionsRequest): ListVersionsResponse = guard { core.repository().listVersions(request) }

        override suspend fun getPackage(request: GetPackageRequest): PackageMetadata = guard { core.repository().getPackage(request) }

        override suspend fun setPluginTrust(request: SetPluginTrustRequest): PackageMetadataList = guard { core.repository().setPluginTrust(request) }

        override fun downloadPackage(request: DownloadRequest): Flow<DownloadResponse> = flow {
            val stub = try {
                core.repository()
            } catch (e: ManagementException) {
                throw StatusException(e.code.toStatus().withDescription(e.message))
            }
            stub.downloadPackage(request).collect { emit(it) }
        }

        override suspend fun recover(request: RecoverRequest): RecoverResponse {
            val report = core.recover()
            return RecoverResponse.newBuilder().setEnginesStarted(report.enginesStarted).setFabricsRestored(report.fabricsRestored).addAllProblems(report.problems).build()
        }
    }

    public companion object {
        private const val S = "cringle.management.v1.ManagementService/"

        /** Permission per method for the [AuthInterceptor]. */
        public val REQUIRED_PERMISSIONS: Map<String, Permission> = mapOf(
            "AddMachine" to Permission.ADMINISTER,
            "RemoveMachine" to Permission.ADMINISTER,
            "ListMachines" to Permission.READ,
            "CreateEngine" to Permission.OPERATE,
            "StartEngine" to Permission.OPERATE,
            "StopEngine" to Permission.OPERATE,
            "DeleteEngine" to Permission.OPERATE,
            "ListEngines" to Permission.READ,
            "GetEngine" to Permission.READ,
            "SetEngineTags" to Permission.OPERATE,
            "Deploy" to Permission.OPERATE,
            "Undeploy" to Permission.OPERATE,
            "Bind" to Permission.OPERATE,
            "Unbind" to Permission.OPERATE,
            "ListBindings" to Permission.READ,
            "CleanupCache" to Permission.OPERATE,
            "DeployFabric" to Permission.OPERATE,
            "StartFabric" to Permission.OPERATE,
            "StopFabric" to Permission.OPERATE,
            "RemoveFabric" to Permission.OPERATE,
            "GetFabric" to Permission.READ,
            "ListFabrics" to Permission.READ,
            "QueryLogs" to Permission.READ,
            "GetMetrics" to Permission.READ,
            "SetLogCollection" to Permission.OPERATE,
            "QueryDwh" to Permission.READ,
            "ListDwhPartitions" to Permission.READ,
            "SetRecording" to Permission.OPERATE,
            "SetDwhRetention" to Permission.OPERATE,
            "AddRemoteRouter" to Permission.ADMINISTER,
            "RemoveRemoteRouter" to Permission.ADMINISTER,
            "ListRemoteRouters" to Permission.READ,
            "ListTrust" to Permission.READ,
            "AddTrustedComponent" to Permission.ADMINISTER,
            "RemoveTrust" to Permission.ADMINISTER,
            "PublishPackage" to Permission.OPERATE,
            "ListPackages" to Permission.READ,
            "ListVersions" to Permission.READ,
            "GetPackage" to Permission.READ,
            "SetPluginTrust" to Permission.ADMINISTER,
            "DownloadPackage" to Permission.READ,
            "Recover" to Permission.OPERATE,
        ).mapKeys { S + it.key }
    }
}
