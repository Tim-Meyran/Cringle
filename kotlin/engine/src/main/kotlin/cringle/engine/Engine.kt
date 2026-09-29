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
import cringle.common.v1.FabricStateSummary
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
 * Until trust management exists (#13) the management server is plaintext and only allowed in insecure dev mode,
 * where it listens on the loopback interface only.
 */
public class Engine private constructor(
    private val dir: Path,
    identity: EngineIdentity,
    config: EngineConfig,
    requestedPort: Int,
    home: Path,
    private val heartbeatInterval: Duration,
) {
    /** The fabrics of this engine. */
    /** The built-in drivers of this engine (logging, filesystem, TCP). */
    public val drivers: BuiltinDrivers = BuiltinDrivers(dir)

    public val fabrics: FabricManager = FabricManager(LocalFabricDeployer(home, dir, builtin = drivers))

    /** The package cache of the machine (shared by all engines with the same Cringle home). */
    public val cache: PackageCache = PackageCache(home).also { it.clearEngine(config.id) }

    private val lock = Any()
    private var currentConfig = config
    private val startedAt: Instant = Instant.now()

    @Volatile
    private var state = EngineState.ENGINE_STATE_RUNNING

    /** The identity of this engine. */
    public val identity: EngineIdentity = identity

    private val server: Server = NettyServerBuilder
        .forAddress(InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort))
        .addService(ManagementService())
        .build()

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
                        FabricState.FAILED -> FabricLifecycleState.FABRIC_LIFECYCLE_STATE_FAILED
                    },
                )
                .build()
        }
    }

    /** The engine's current configuration. */
    public val config: EngineConfig get() = synchronized(lock) { currentConfig }

    /** Port of the management server; valid after [start]. */
    public val managementPort: Int get() = server.port

    /** Starts the management server and publishes its port in `<engine dir>/management.port`. */
    public fun start(): Engine {
        server.start()
        started = true
        Files.writeString(dir.resolve(PORT_FILE), managementPort.toString() + "\n")
        synchronized(lock) { restartLink() }
        return this
    }

    /** Stops the management server gracefully and removes the port file. Safe to call more than once. */
    public fun stop() {
        state = EngineState.ENGINE_STATE_STOPPING
        fabrics.close()
        drivers.close()
        synchronized(lock) {
            link?.stop()
            link = null
        }
        server.shutdown()
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        Files.deleteIfExists(dir.resolve(PORT_FILE))
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
                router,
                heartbeatInterval,
            ) { fabricStates() }.also { it.start() }
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
                .build()
        }

        override suspend fun configure(request: ConfigureRequest): ConfigureResponse {
            if (request.hasRouterAddress()) setRouterAddress(request.routerAddress.takeIf { it.isNotEmpty() })
            return ConfigureResponse.newBuilder().setRouterAddress(config.routerAddress.orEmpty()).build()
        }

        override suspend fun deployFabric(request: DeployFabricRequest): FabricInfo {
            val artifacts = artifactsOf(request)
            if (request.hasSource()) {
                val source = request.source
                val fetcher = RepositoryFetcher(source.repositoryAddress, source.token.takeIf { it.isNotEmpty() })
                try {
                    for (artifact in source.artifactsList.map(::artifactOf)) cache.ensure(artifact, fetcher)
                } catch (e: PackageCacheException) {
                    throw StatusException((if (e.hashMismatch) Status.DATA_LOSS else Status.FAILED_PRECONDITION).withDescription(e.message))
                } finally {
                    fetcher.close()
                }
            }
            val info = deployLocal(request)
            cache.recordUsage(config.id, request.fabricId.value, artifacts)
            return info
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
            val removed = withContext(Dispatchers.IO) { cache.cleanup(Duration.ofSeconds(request.minUnusedSeconds.coerceAtLeast(0))) }
            return cringle.engine.v1.CleanupCacheResponse.newBuilder().addAllRemoved(removed).build()
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
            info(fabrics.deploy(DeployRequest(request.fabricId.value, request.project.name, request.project.version, request.blueprint, plugins)))
        }

        override suspend fun startFabric(request: FabricRequest): FabricInfo = fabricCall { info(fabrics.start(request.fabricId.value)) }

        override suspend fun stopFabric(request: FabricRequest): FabricInfo = fabricCall { info(fabrics.stop(request.fabricId.value)) }

        override suspend fun removeFabric(request: FabricRequest): RemoveFabricResponse = fabricCall {
            fabrics.remove(request.fabricId.value)
            cache.clearUsage(config.id, request.fabricId.value)
            RemoveFabricResponse.getDefaultInstance()
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
            )
            val entries = withContext(Dispatchers.IO) { drivers.logging.query(query) }
            return cringle.engine.v1.QueryLogsResponse.newBuilder().addAllEntries(
                entries.map {
                    cringle.engine.v1.LogEntry.newBuilder()
                        .setTimestamp(timestamp(it.timestamp)).setFabric(it.fabric).setBlock(it.block).setMessage(it.message)
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
                FabricState.FAILED -> FabricRuntimeState.FABRIC_RUNTIME_STATE_FAILED
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

        /**
         * Prepares an engine for [args]: resolves the home, loads or creates config and identity. The server is not
         * started yet. Throws [EngineArgsException] if insecure dev mode is missing.
         */
        public fun create(
            args: EngineArgs,
            env: Map<String, String> = System.getenv(),
            heartbeatInterval: Duration = Duration.ofSeconds(5),
        ): Engine {
            if (!args.insecureDevMode) {
                throw EngineArgsException(
                    "secure (mTLS) management is not available yet (issue #13); start with --insecure-dev-mode",
                )
            }
            val dir = CringleHome.engineDir(CringleHome.resolve(args.home, env), args.id)
            Files.createDirectories(dir)
            val config = EngineConfig.loadOrCreate(dir, args.id, args.name)
            val identity = EngineIdentity.loadOrCreate(dir, args.id)
            return Engine(dir, identity, config, args.managementPort, CringleHome.resolve(args.home, env), heartbeatInterval)
        }

        private fun timestamp(i: Instant): Timestamp = Timestamp.newBuilder().setSeconds(i.epochSecond).setNanos(i.nano).build()
    }
}
