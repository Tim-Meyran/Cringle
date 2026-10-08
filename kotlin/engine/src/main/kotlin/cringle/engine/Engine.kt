// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import com.google.protobuf.Timestamp
import cringle.common.v1.CertificateInfo
import cringle.common.v1.BlockId
import cringle.common.v1.EngineId
import cringle.common.v1.FabricId
import cringle.common.v1.FabricLifecycleState
import cringle.contract.LogLevel
import cringle.engine.drivers.BuiltinDrivers
import cringle.engine.drivers.LogQuery
import cringle.engine.fabric.BlockState
import cringle.engine.fabric.DeployPlugin
import cringle.engine.fabric.DeployRequest
import cringle.engine.fabric.FabricException
import cringle.engine.fabric.FabricManager
import cringle.engine.fabric.FabricNotFoundException
import cringle.engine.fabric.FabricState
import cringle.engine.fabric.FabricStatus
import cringle.engine.fabric.LocalFabricDeployer
import cringle.engine.fabric.PluginTrust
import cringle.engine.tether.FabricNotResolvedException
import cringle.engine.tether.FabricResolver
import cringle.engine.tether.RemoteTetherDriver
import cringle.engine.tether.RemoteTetherOptions
import cringle.common.v1.FabricStateSummary
import cringle.common.EngineTls
import cringle.common.TlsHelper
import cringle.common.TrustStore
import cringle.repository.RepositoryTls
import cringle.engine.v1.ConfigureRequest
import cringle.engine.v1.ConfigureResponse
import cringle.engine.v1.EngineManagementServiceGrpcKt
import cringle.engine.v1.EngineState
import cringle.engine.v1.BlockInfo
import cringle.engine.v1.BlockRuntimeState
import cringle.engine.v1.DeployFabricRequest
import cringle.engine.v1.FabricInfo
import cringle.engine.v1.FabricRequest
import cringle.engine.v1.FabricRuntimeState
import cringle.engine.v1.GetStatusRequest
import cringle.engine.v1.ListFabricsRequest
import cringle.engine.v1.ListFabricsResponse
import cringle.engine.v1.RemoveFabricResponse
import cringle.engine.v1.GetStatusResponse
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One engine process: its directory, config, identity and management server.
 *
 * The management server speaks mutual TLS on the loopback interface (Architecture chapters 5 and 18): it presents the
 * identity of the engine and accepts only peers in `<engine dir>/trust.json` (the daemon and the management server,
 * entered by the daemon before the engine starts). The trust store is also the one the router link uses.
 */
public class Engine private constructor(
    private val dir: Path,
    identity: EngineIdentity,
    config: EngineConfig,
    requestedPort: Int,
    home: Path,
    private val heartbeatInterval: Duration,
    /** The peers that may call the management API of this engine (and, with [tls], the router the engine trusts). */
    public val trustStore: TrustStore,
    private val tls: EngineTls,
    private val enrollmentSecret: ByteArray?,
    requestedTetherPort: Int,
    tetherOptions: RemoteTetherOptions,
) {
    /** The fabrics of this engine. */
    /** The built-in drivers of this engine (logging, filesystem, TCP). */
    public val drivers: BuiltinDrivers = BuiltinDrivers(dir)

    /**
     * The tethers between engines (`spec/tether.md`): serves the senders that the fabrics of this engine allow, over mutual
     * TLS with the identity of this engine. Its trust store (`tether-trust.json`) is separate from [trustStore] and starts
     * empty with every process.
     */
    public val remoteTethers: RemoteTetherDriver =
        RemoteTetherDriver(
            identity.common, TrustStore(dir.resolve("tether-trust.json")), dir.resolve("tether-peers"), requestedTetherPort,
            resolver = FabricResolver { fabricId ->
                val registry = synchronized(lock) { link } ?: throw FabricNotResolvedException("this engine has no router to ask for the fabric '$fabricId'")
                registry.lookupFabric(fabricId)
            },
            options = tetherOptions,
        )

    public val fabrics: FabricManager = FabricManager(LocalFabricDeployer(home, dir, builtin = drivers, remoteTethers = remoteTethers))

    /** The package cache of the machine (shared by all engines with the same Cringle home). */
    public val cache: PackageCache = PackageCache(home).also {
        it.clearEngine(config.id)
        // what an earlier crash left in the cache (old downloads, unfinished installs and removals)
        it.sweepLeftovers()
    }

    /** The data warehouse of this engine (`<engine dir>/dwh`, #188); its retention is applied every [DWH_RETENTION_INTERVAL_SECONDS] seconds. */
    public val dwh: cringle.engine.dwh.Dwh get() = drivers.dwh
    private var retention: java.util.concurrent.ScheduledExecutorService? = null

    private val lock = Any()
    private var currentConfig = config
    private val startedAt: Instant = Instant.now()

    @Volatile
    private var state = EngineState.ENGINE_STATE_RUNNING

    /** The identity of this engine. */
    public val identity: EngineIdentity = identity

    private val certificateWatcher = cringle.common.CertificateWatcher(identity.common)

    private val server: Server = NettyServerBuilder
        .forAddress(InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort))
        .sslContext(TlsHelper.serverCredentials(identity.common, trustStore))
        .addService(ManagementService())
        .build()

    private val vitals = cringle.engine.metrics.VitalsSampler(
        { fabrics.stats() },
        { fabrics.list().count { it.state == cringle.engine.fabric.FabricState.RUNNING } },
    )
    private var link: RegistryLink? = null
    private var started = false

    /** Supplies the fabric states that heartbeats to the router report; by default the fabrics of this engine. */
    @Volatile
    public var fabricStates: () -> List<FabricStateSummary> = {
        fabrics.list().map {
            FabricStateSummary.newBuilder()
                .setFabricId(FabricId.newBuilder().setValue(it.id))
                .setBlueprintName(it.blueprint)
                .setState(
                    when (it.state) {
                        FabricState.CREATED -> FabricLifecycleState.FABRIC_LIFECYCLE_STATE_INITIALIZING
                        FabricState.STARTING -> FabricLifecycleState.FABRIC_LIFECYCLE_STATE_INITIALIZING
                        FabricState.RUNNING -> FabricLifecycleState.FABRIC_LIFECYCLE_STATE_RUNNING
                        FabricState.STOPPING -> FabricLifecycleState.FABRIC_LIFECYCLE_STATE_STOPPING
                        FabricState.STOPPED -> FabricLifecycleState.FABRIC_LIFECYCLE_STATE_STOPPED
                        FabricState.FAILED, FabricState.MIGRATION_FAILED -> FabricLifecycleState.FABRIC_LIFECYCLE_STATE_FAILED
                    },
                )
                .build()
        }
    }

    /** The engine's current configuration. */
    public val config: EngineConfig get() = synchronized(lock) { currentConfig }

    /** Port of the management server; valid after [start]. */
    public val managementPort: Int get() = server.port

    /** Port of the server for tethers between engines; valid after [start]. */
    public val tetherPort: Int get() = remoteTethers.port

    /**
     * Starts the management server and the server for tethers between engines and publishes their ports in
     * `<engine dir>/management.port` and `<engine dir>/tether.port`.
     */
    public fun start(): Engine {
        certificateWatcher.start()
        server.start()
        remoteTethers.start()
        started = true
        retention = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dwh-retention").also { it.isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ runCatching { dwh.applyRetention() } }, DWH_RETENTION_INTERVAL_SECONDS, DWH_RETENTION_INTERVAL_SECONDS, TimeUnit.SECONDS)
        }
        Files.writeString(dir.resolve(PORT_FILE), managementPort.toString() + "\n")
        Files.writeString(dir.resolve(TETHER_PORT_FILE), tetherPort.toString() + "\n")
        synchronized(lock) { restartLink() }
        return this
    }

    /** Stops the management server gracefully and removes the port file. Safe to call more than once. */
    public fun stop() {
        state = EngineState.ENGINE_STATE_STOPPING
        certificateWatcher.close()
        retention?.shutdownNow()
        retention = null
        fabrics.close()
        drivers.close()
        synchronized(lock) {
            link?.stop()
            link = null
        }
        server.shutdown()
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        remoteTethers.close()
        Files.deleteIfExists(dir.resolve(PORT_FILE))
        Files.deleteIfExists(dir.resolve(TETHER_PORT_FILE))
    }

    /** Sets (or, with `null`, clears) the router this engine registers at; persists the config and reconnects. */
    public fun setRouterAddress(address: String?) {
        synchronized(lock) {
            currentConfig = currentConfig.copy(routerAddress = address).also { it.save(dir) }
            restartLink()
        }
    }

    /** Must be called with [lock] held. Registers at the configured router, if any and if the server is running. */
    private fun restartLink() {
        link?.stop()
        link = null
        val router = currentConfig.routerAddress
        if (router != null && state != EngineState.ENGINE_STATE_STOPPING && started) {
            link = RegistryLink(
                currentConfig.id,
                currentConfig.name,
                "127.0.0.1:$managementPort",
                "127.0.0.1:$tetherPort",
                router,
                heartbeatInterval,
                { fabricStates() },
                { vitals.sample() },
                tls,
                enrollmentSecret,
            ).also { it.start() }
        }
    }

    /** Blocks until the management server has terminated. */
    public fun awaitTermination() {
        server.awaitTermination()
    }

    private inner class ManagementService : EngineManagementServiceGrpcKt.EngineManagementServiceCoroutineImplBase() {
        override suspend fun getStatus(request: GetStatusRequest): GetStatusResponse {
            val cfg = config
            val cert = identity.certificate
            return GetStatusResponse.newBuilder()
                .setEngineId(EngineId.newBuilder().setValue(cfg.id))
                .setName(cfg.name)
                .setState(state)
                .setStartedAt(timestamp(startedAt))
                .setCertificate(
                    CertificateInfo.newBuilder()
                        .setFingerprint(identity.fingerprint)
                        .setSubject(cert.subjectX500Principal.name)
                        .setIssuer(cert.issuerX500Principal.name)
                        .setNotBefore(timestamp(cert.notBefore.toInstant()))
                        .setNotAfter(timestamp(cert.notAfter.toInstant()))
                        .setSerialNumber(cert.serialNumber.toString(16)),
                )
                .setRouterAddress(cfg.routerAddress.orEmpty())
                .setPublicKeyFingerprint(identity.publicKeyFingerprint)
                .setTetherPort(tetherPort)
                .build()
        }

        override suspend fun configure(request: ConfigureRequest): ConfigureResponse {
            if (request.hasRouterAddress()) setRouterAddress(request.routerAddress.takeIf { it.isNotEmpty() })
            return ConfigureResponse.newBuilder().setRouterAddress(config.routerAddress.orEmpty()).build()
        }

        override suspend fun deployFabric(request: DeployFabricRequest): FabricInfo {
            if (request.hasSource()) {
                val source = request.source
                val fetcher = RepositoryFetcher(source.repositoryAddress, source.token.takeIf { it.isNotEmpty() }, RepositoryTls(identity.common, trustStore))
                val created = try {
                    cache.ensureForDeploy(config.id, request.fabricId.value, source.artifactsList.map(::artifactOf), fetcher)
                } catch (e: PackageCacheException) {
                    throw StatusException((if (e.hashMismatch) Status.DATA_LOSS else Status.FAILED_PRECONDITION).withDescription(e.message))
                } finally {
                    fetcher.close()
                }
                try {
                    val info = deployLocal(request)
                    if (!created) cache.recordUsage(config.id, request.fabricId.value, source.artifactsList.map(::artifactOf))
                    return info
                } catch (e: Throwable) {
                    if (created) cache.clearUsage(config.id, request.fabricId.value)
                    throw e
                }
            } else {
                val info = deployLocal(request)
                cache.recordUsage(config.id, request.fabricId.value, artifactsOf(request))
                return info
            }
        }

        private fun artifactOf(a: cringle.engine.v1.PackageArtifact) = Artifact(
            if (a.kind == cringle.engine.v1.ArtifactKind.ARTIFACT_KIND_PROJECT) ArtifactType.PROJECT else ArtifactType.PLUGIN,
            a.name, a.version, a.sha256,
        )

        /** The packages the fabric uses: the project and all plugins (hashes are unknown without a source and not needed then). */
        private fun artifactsOf(request: DeployFabricRequest): List<Artifact> =
            listOf(Artifact(ArtifactType.PROJECT, request.project.name, request.project.version, "")) +
                request.pluginsList.map { Artifact(ArtifactType.PLUGIN, it.plugin.name, it.plugin.version, "") }

        override suspend fun cleanupCache(request: cringle.engine.v1.CleanupCacheRequest): cringle.engine.v1.CleanupCacheResponse {
            val report = withContext(Dispatchers.IO) { cache.cleanupReport(Duration.ofSeconds(request.minUnusedSeconds.coerceAtLeast(0))) }
            return cringle.engine.v1.CleanupCacheResponse.newBuilder().addAllRemoved(report.removed).addAllFailed(report.failed).build()
        }

        private suspend fun deployLocal(request: DeployFabricRequest): FabricInfo = fabricCall(FabricException::class.java to Status.INVALID_ARGUMENT) {
            val plugins = request.pluginsList.map {
                DeployPlugin(
                    it.plugin.name,
                    it.plugin.version,
                    when (it.trust) {
                        cringle.engine.v1.PluginTrust.PLUGIN_TRUST_TRUSTED -> PluginTrust.TRUSTED
                        else -> PluginTrust.UNTRUSTED // unspecified counts as untrusted: fail closed
                    },
                )
            }
            info(fabrics.deploy(DeployRequest(request.fabricId.value, request.project.name, request.project.version, request.blueprint, plugins, request.serviceCallersList,
                request.serviceBindingsList.map(::serviceBinding),
            )))
        }

        private fun serviceBinding(b: cringle.engine.v1.ServiceBinding): cringle.engine.fabric.ServiceBinding =
            cringle.engine.fabric.ServiceBinding(b.service, b.fabric, b.block, b.port, b.fingerprint, b.fallbacksList.map { f -> cringle.engine.fabric.ServiceBinding(b.service, f.fabric, f.block, f.port, f.fingerprint) })

        override suspend fun updateServiceBindings(request: cringle.engine.v1.UpdateServiceBindingsRequest): cringle.engine.v1.UpdateServiceBindingsResponse =
            fabricCall(FabricException::class.java to Status.INVALID_ARGUMENT) {
                fabrics.updateServiceBindings(request.fabricId.value, request.bindingsList.map(::serviceBinding))
                cringle.engine.v1.UpdateServiceBindingsResponse.getDefaultInstance()
            }

        override suspend fun setServiceCallers(request: cringle.engine.v1.SetServiceCallersRequest): cringle.engine.v1.SetServiceCallersResponse =
            fabricCall(FabricException::class.java to Status.INVALID_ARGUMENT) {
                request.fingerprintsList.forEach {
                    if (!Regex("[0-9a-f]{64}").matches(it)) throw FabricException("invalid fingerprint '$it': expected 64 lowercase hex characters")
                }
                fabrics.setServiceCallers(request.fabricId.value, request.fingerprintsList)
                cringle.engine.v1.SetServiceCallersResponse.getDefaultInstance()
            }

        override suspend fun startFabric(request: FabricRequest): FabricInfo = fabricCall { info(fabrics.start(request.fabricId.value)) }

        override suspend fun stopFabric(request: FabricRequest): FabricInfo = fabricCall { info(fabrics.stop(request.fabricId.value)) }

        override suspend fun removeFabric(request: FabricRequest): RemoveFabricResponse = fabricCall {
            fabrics.remove(request.fabricId.value)
            cache.clearUsage(config.id, request.fabricId.value)
            RemoveFabricResponse.getDefaultInstance()
        }

        private fun recordConfig(r: cringle.engine.v1.DwhRetention): cringle.packaging.RecordConfig = cringle.packaging.RecordConfig(
            r.maxAgeMs.takeIf { it > 0 }?.let { java.time.Duration.ofMillis(it) },
            r.maxBytes.takeIf { it > 0 },
        )

        override suspend fun setRecording(request: cringle.engine.v1.SetRecordingRequest): cringle.engine.v1.SetRecordingResponse =
            fabricCall(FabricException::class.java to Status.INVALID_ARGUMENT) {
                fabrics.setRecording(request.fabricId.value, request.all, if (request.hasDefaultRetention()) recordConfig(request.defaultRetention) else null)
                cringle.engine.v1.SetRecordingResponse.getDefaultInstance()
            }

        override suspend fun setDwhRetention(request: cringle.engine.v1.SetDwhRetentionRequest): cringle.engine.v1.SetDwhRetentionResponse =
            fabricCall(FabricException::class.java to Status.INVALID_ARGUMENT) {
                fabrics.status(request.fabricId.value) // NOT_FOUND for an unknown fabric
                val kind = when (request.kind) {
                    cringle.engine.v1.DwhKind.DWH_KIND_BLOCK -> cringle.engine.dwh.DwhKind.BLOCK
                    cringle.engine.v1.DwhKind.DWH_KIND_TETHER -> cringle.engine.dwh.DwhKind.TETHER
                    else -> throw FabricException("the kind of the partition must be block or tether")
                }
                if (request.name.isBlank()) throw FabricException("the name of the partition must not be empty")
                val config = recordConfig(request.retention)
                dwh.setRetention(cringle.engine.dwh.DwhPartition(request.fabricId.value, kind, request.name), cringle.engine.dwh.Retention(config.maxAge, config.maxBytes))
                cringle.engine.v1.SetDwhRetentionResponse.getDefaultInstance()
            }

        private fun dwhKind(kind: cringle.engine.v1.DwhKind): cringle.engine.dwh.DwhKind = when (kind) {
            cringle.engine.v1.DwhKind.DWH_KIND_BLOCK -> cringle.engine.dwh.DwhKind.BLOCK
            cringle.engine.v1.DwhKind.DWH_KIND_TETHER -> cringle.engine.dwh.DwhKind.TETHER
            else -> throw StatusException(Status.INVALID_ARGUMENT.withDescription("the kind of the partition must be block or tether"))
        }

        override suspend fun queryDwh(request: cringle.engine.v1.QueryDwhRequest): cringle.engine.v1.QueryDwhResponse {
            if (request.fabricId.value.isBlank() || request.name.isBlank()) throw StatusException(Status.INVALID_ARGUMENT.withDescription("fabric and name of the partition are required"))
            fun instant(t: com.google.protobuf.Timestamp) = Instant.ofEpochSecond(t.seconds, t.nanos.toLong())
            val records = withContext(Dispatchers.IO) {
                dwh.query(
                    cringle.engine.dwh.DwhPartition(request.fabricId.value, dwhKind(request.kind), request.name),
                    if (request.hasSince()) instant(request.since) else null,
                    if (request.hasUntil()) instant(request.until) else null,
                    if (request.limit > 0) request.limit else 1000,
                )
            }
            return cringle.engine.v1.QueryDwhResponse.newBuilder().addAllRecords(
                records.map {
                    cringle.engine.v1.DwhRecord.newBuilder().setTimestamp(timestamp(it.timestamp)).setPayloadJson(it.payload.toString()).putAllTags(it.tags).build()
                },
            ).build()
        }

        override suspend fun listDwhPartitions(request: cringle.engine.v1.ListDwhPartitionsRequest): cringle.engine.v1.ListDwhPartitionsResponse {
            val all = withContext(Dispatchers.IO) { dwh.partitions() }
            return cringle.engine.v1.ListDwhPartitionsResponse.newBuilder().addAllPartitions(
                all.filter { request.fabric.isEmpty() || it.partition.fabric == request.fabric }.map {
                    cringle.engine.v1.DwhPartitionInfo.newBuilder().setFabric(it.partition.fabric).setName(it.partition.name).setBytes(it.bytes)
                        .setKind(if (it.partition.kind == cringle.engine.dwh.DwhKind.BLOCK) cringle.engine.v1.DwhKind.DWH_KIND_BLOCK else cringle.engine.v1.DwhKind.DWH_KIND_TETHER)
                        .setRetention(cringle.engine.v1.DwhRetention.newBuilder().setMaxAgeMs(it.retention.maxAge?.toMillis() ?: 0).setMaxBytes(it.retention.maxBytes ?: 0))
                        .build()
                },
            ).build()
        }

        override suspend fun getMetrics(request: cringle.engine.v1.GetMetricsRequest): cringle.engine.v1.GetMetricsResponse {
            val e = cringle.engine.metrics.MetricsCollector.engine()
            val b = cringle.engine.v1.EngineMetrics.newBuilder()
                .setSampledAt(timestamp(Instant.now())).setProcessCpuLoad(e.processCpuLoad)
                .setHeapUsedBytes(e.heapUsedBytes).setHeapMaxBytes(e.heapMaxBytes).setThreadCount(e.threadCount)
            for (f in fabrics.stats()) {
                b.addFabrics(
                    cringle.engine.v1.FabricMetrics.newBuilder().setFabricId(FabricId.newBuilder().setValue(f.id)).setCpuTimeNs(f.cpuTimeNanos).setErrors(f.errors)
                        .addAllBlocks(f.blocks.map { cringle.engine.v1.BlockMetrics.newBuilder().setBlockId(it.id).setErrors(it.errors).build() })
                        .addAllTethers(f.tethers.map { cringle.engine.v1.TetherMetrics.newBuilder().setTetherId(it.id).setType(it.type.name).setMessages(it.messages).setBytes(it.bytes).setErrors(it.errors).build() }),
                )
            }
            return cringle.engine.v1.GetMetricsResponse.newBuilder().setMetrics(b).build()
        }

        override suspend fun listFabrics(request: ListFabricsRequest): ListFabricsResponse =
            ListFabricsResponse.newBuilder().addAllFabrics(fabrics.list().map { info(it) }).build()

        override suspend fun getFabricStatus(request: FabricRequest): FabricInfo = fabricCall { info(fabrics.status(request.fabricId.value)) }

        override suspend fun queryLogs(request: cringle.engine.v1.QueryLogsRequest): cringle.engine.v1.QueryLogsResponse {
            val minLevel = when (request.minLevel) {
                cringle.engine.v1.LogLevel.LOG_LEVEL_INFO -> LogLevel.INFO
                cringle.engine.v1.LogLevel.LOG_LEVEL_WARN -> LogLevel.WARN
                cringle.engine.v1.LogLevel.LOG_LEVEL_ERROR -> LogLevel.ERROR
                else -> LogLevel.DEBUG
            }
            val query = LogQuery(
                fabric = request.fabric.takeIf { it.isNotEmpty() },
                block = request.block.takeIf { it.isNotEmpty() },
                minLevel = minLevel,
                since = if (request.hasSince()) Instant.ofEpochSecond(request.since.seconds, request.since.nanos.toLong()) else null,
                limit = if (request.limit > 0) request.limit else 1000,
                driverOnly = request.driverOnly,
            )
            val entries = withContext(Dispatchers.IO) { drivers.logging.query(query) }
            return cringle.engine.v1.QueryLogsResponse.newBuilder().addAllEntries(
                entries.map {
                    cringle.engine.v1.LogEntry.newBuilder()
                        .setTimestamp(timestamp(it.timestamp)).setFabric(it.fabric).setBlock(it.block).setMessage(it.message).setSource(it.source)
                        .setLevel(
                            when (it.level) {
                                LogLevel.DEBUG -> cringle.engine.v1.LogLevel.LOG_LEVEL_DEBUG
                                LogLevel.INFO -> cringle.engine.v1.LogLevel.LOG_LEVEL_INFO
                                LogLevel.WARN -> cringle.engine.v1.LogLevel.LOG_LEVEL_WARN
                                LogLevel.ERROR -> cringle.engine.v1.LogLevel.LOG_LEVEL_ERROR
                            },
                        )
                        .build()
                },
            ).build()
        }
    }

    private suspend fun <T> fabricCall(vararg mapping: Pair<Class<out Throwable>, Status>, body: suspend () -> T): T {
        try {
            return body()
        } catch (e: FabricNotFoundException) {
            throw StatusException(Status.NOT_FOUND.withDescription(e.message))
        } catch (e: FabricException) {
            val status = mapping.firstOrNull { it.first.isInstance(e) }?.second ?: Status.FAILED_PRECONDITION
            throw StatusException(status.withDescription(e.message))
        } catch (e: IllegalStateException) {
            throw StatusException(Status.FAILED_PRECONDITION.withDescription(e.message))
        }
    }

    private fun info(s: FabricStatus): FabricInfo = FabricInfo.newBuilder()
        .setFabricId(FabricId.newBuilder().setValue(s.id))
        .setBlueprint(s.blueprint)
        .setState(
            when (s.state) {
                FabricState.CREATED -> FabricRuntimeState.FABRIC_RUNTIME_STATE_CREATED
                FabricState.STARTING -> FabricRuntimeState.FABRIC_RUNTIME_STATE_STARTING
                FabricState.RUNNING -> FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING
                FabricState.STOPPING -> FabricRuntimeState.FABRIC_RUNTIME_STATE_STOPPING
                FabricState.STOPPED -> FabricRuntimeState.FABRIC_RUNTIME_STATE_STOPPED
                FabricState.FAILED, FabricState.MIGRATION_FAILED -> FabricRuntimeState.FABRIC_RUNTIME_STATE_FAILED
            },
        )
        .addAllBlocks(
            s.blocks.map { b ->
                BlockInfo.newBuilder()
                    .setBlockId(BlockId.newBuilder().setValue(b.id))
                    .setState(
                        when (b.state) {
                            BlockState.CREATED -> BlockRuntimeState.BLOCK_RUNTIME_STATE_CREATED
                            BlockState.STARTING -> BlockRuntimeState.BLOCK_RUNTIME_STATE_STARTING
                            BlockState.RUNNING -> BlockRuntimeState.BLOCK_RUNTIME_STATE_RUNNING
                            BlockState.RESTARTING -> BlockRuntimeState.BLOCK_RUNTIME_STATE_RESTARTING
                            BlockState.STOPPING -> BlockRuntimeState.BLOCK_RUNTIME_STATE_STOPPING
                            BlockState.STOPPED -> BlockRuntimeState.BLOCK_RUNTIME_STATE_STOPPED
                            BlockState.FAILED -> BlockRuntimeState.BLOCK_RUNTIME_STATE_FAILED
                        },
                    )
                    .setRestarts(b.restarts)
                    .setLastError(b.lastError.orEmpty())
                    .build()
            },
        )
        .setFailure(s.failure.orEmpty())
        .build()

    public companion object {
        /** Name of the file that holds the management port of a running engine. */
        public const val PORT_FILE: String = "management.port"

        /** Name of the file that holds the port for tethers between engines of a running engine. */
        public const val TETHER_PORT_FILE: String = "tether.port"

        /** How often the retention of the DWH is applied. */
        public const val DWH_RETENTION_INTERVAL_SECONDS: Long = 60

        /**
         * Prepares an engine for [args]: resolves the home, loads or creates config and identity. The server is not
         * started yet. The management API and the router channel are always mTLS: the engine opens its trust store and reads
         * the enrollment secret from the environment.
         */
        public fun create(
            args: EngineArgs,
            env: Map<String, String> = System.getenv(),
            heartbeatInterval: Duration = Duration.ofSeconds(5),
            tetherOptions: RemoteTetherOptions = RemoteTetherOptions(),
        ): Engine {
            val dir = CringleHome.engineDir(CringleHome.resolve(args.home, env), args.id)
            Files.createDirectories(dir)
            val config = EngineConfig.loadOrCreate(dir, args.id, args.name)
            val identity = EngineIdentity.loadOrCreate(dir, args.id)
            val trustStore = TrustStore(dir.resolve("trust.json"))
            val tls = EngineTls(identity.common, trustStore)
            val enrollmentSecret = readEnrollmentSecret(env)
            return Engine(dir, identity, config, args.managementPort, CringleHome.resolve(args.home, env), heartbeatInterval, trustStore, tls, enrollmentSecret, args.tetherPort, tetherOptions)
        }

        /**
         * Reads `CRINGLE_ENROLLMENT_SECRET` from [env]. Returns `null` if the variable is absent (the engine will try
         * to register without a secret; the router accepts it if already enrolled, refuses otherwise). If present, the
         * value must be 64 lowercase hex characters; otherwise throws [EngineArgsException].
         */
        private fun readEnrollmentSecret(env: Map<String, String>): ByteArray? {
            val hex = env["CRINGLE_ENROLLMENT_SECRET"] ?: return null
            if (!Regex("[0-9a-f]{64}").matches(hex)) {
                throw EngineArgsException("CRINGLE_ENROLLMENT_SECRET must be 64 lowercase hex characters")
            }
            return ByteArray(32) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }
        }

        private fun timestamp(i: Instant): Timestamp = Timestamp.newBuilder().setSeconds(i.epochSecond).setNanos(i.nano).build()
    }
}
