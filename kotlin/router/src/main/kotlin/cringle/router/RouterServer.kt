// SPDX-License-Identifier: Apache-2.0

package cringle.router

import com.google.protobuf.Timestamp
import cringle.common.PublicKeyFingerprint
import cringle.common.TlsHelper
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.v1.EngineId
import cringle.common.v1.FabricLifecycleState
import cringle.common.v1.FabricStateSummary
import cringle.router.v1.AddRemoteRouterRequest
import cringle.router.v1.AddRemoteRouterResponse
import cringle.router.v1.EngineEntry
import cringle.router.users.AuthInterceptor
import cringle.router.users.Permission
import cringle.router.users.UserManager
import cringle.router.v1.ListEnginesRequest
import cringle.router.v1.ListTrustRequest
import cringle.router.v1.ListTrustResponse
import cringle.router.v1.PrepareEngineRequest
import cringle.router.v1.PrepareEngineResponse
import cringle.router.v1.RegistryServiceGrpc
import cringle.router.v1.RevokeRemoteRouterRequest
import cringle.router.v1.RevokeRemoteRouterResponse
import cringle.router.v1.TrustEntryInfo
import cringle.router.v1.TrustRemoteRouterRequest
import cringle.router.v1.TrustRemoteRouterResponse
import io.grpc.ServerInterceptors
import kotlinx.coroutines.withContext
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
    /** With TLS only trusted routers are asked, over TLS, and the engines they report become trusted through them. */
    private val tls: RouterTls? = null,
    private val channel: (String) -> ManagedChannel = { address ->
        val (host, port) = address.substringBeforeLast(':') to address.substringAfterLast(':').toInt()
        NettyChannelBuilder.forAddress(host, port).apply {
            // a router without TLS serves plaintext; the plaintext branch is the dev mode that #86 removes
            if (tls == null) usePlaintext() else sslContext(TlsHelper.channelCredentials(tls.identity, tls.trustStore))
        }.build()
    },
) {
    /** Refreshes the cache of the remote router at [address]; a failure is recorded, not thrown. */
    public suspend fun refresh(address: String) {
        if (tls != null && tls.trustStore.list().none { it.kind == TrustKind.ROUTER && it.address == address }) {
            registry.updateRemote(address, null, "remote router is not trusted")
            return
        }
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
                        fingerprint = e.fingerprint,
                    ) to if (e.reachability == cringle.router.v1.Reachability.REACHABILITY_REACHABLE) Reachability.REACHABLE else Reachability.UNREACHABLE
                },
                null,
            )
            if (tls != null) syncTrust(tls, address, response.enginesList)
        } catch (e: StatusException) {
            registry.updateRemote(address, null, "${e.status.code}: ${e.status.description ?: e.message}")
        } catch (e: io.grpc.StatusRuntimeException) {
            registry.updateRemote(address, null, "${e.status.code}: ${e.status.description ?: e.message}")
        } finally {
            ch.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    /**
     * Trust through a router (Architecture 5.1): every engine it reports with a fingerprint is trusted with
     * origin = the fingerprint of the router; entries of this router that it does not report any more are removed.
     * An engine that is trusted directly stays as it is.
     */
    private fun syncTrust(tls: RouterTls, address: String, engines: List<cringle.router.v1.EngineEntry>) {
        val router = tls.trustStore.list().firstOrNull { it.kind == TrustKind.ROUTER && it.address == address } ?: return
        val reported = engines.filter { PublicKeyFingerprint.pattern.matches(it.fingerprint) && it.fingerprint != router.fingerprint }.associateBy { it.fingerprint }
        for (old in tls.trustStore.list()) {
            if (old.origin == router.fingerprint && old.fingerprint !in reported) tls.trustStore.remove(old.fingerprint)
        }
        val known = tls.trustStore.list().associateBy { it.fingerprint }
        for ((fingerprint, e) in reported) {
            val existing = known[fingerprint]
            if (existing != null && existing.origin == null) continue
            if (existing != null && existing.origin == router.fingerprint) continue
            tls.trustStore.add(TrustEntry(fingerprint, e.name.ifBlank { e.engineId.value }, TrustKind.ENGINE, e.managementAddress.ifBlank { null }, router.fingerprint))
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
 * A router as a library: registry storage, gRPC server and federation. The router can run in its own process or inside
 * the daemon (Architecture 4.3).
 *
 * Without [tls] it is plaintext on the loopback interface, as before. With [tls] the server speaks TLS 1.3 and decides
 * for every call whom it serves, by the fingerprint of the key of the peer (see [TrustInterceptor] and `docs/trust.md`):
 * trust in remote routers, transitive trust in their engines, and enrollment of local engines.
 *
 * With [users] the trust methods that an operator calls (`AddRemoteRouter`, `TrustRemoteRouter`, `RevokeRemoteRouter`:
 * ADMINISTER, `ListTrust`: READ) need a token; everything else is not asked for a token.
 */
public class RouterServer(
    registryFile: Path,
    port: Int = 0,
    private val clock: Clock = Clock.systemUTC(),
    heartbeatTimeout: Duration = Duration.ofSeconds(15),
    private val refreshInterval: Duration = Duration.ofSeconds(30),
    private val tls: RouterTls? = null,
    users: UserManager? = null,
) {
    /** The registry of this router. */
    public val registry: Registry = Registry(FileRegistryStore(registryFile), clock, heartbeatTimeout)

    /** Federation with remote routers. */
    public val remoteRouters: RemoteRouters = RemoteRouters(registry, tls = tls)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val trustInterceptor: TrustInterceptor? = tls?.let {
        TrustInterceptor(it.trustStore, open = TOKEN_METHODS.keys + REGISTER_ENGINE, componentOnly = setOf(PREPARE_ENGINE))
    }
    private val server: Server = NettyServerBuilder
        .forAddress(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        .apply { if (tls != null) sslContext(TlsHelper.serverCredentials(tls.identity, tls.trustStore, requireTrustedClients = false)) }
        .addService(guarded(Service(registry, remoteRouters, tls, tls?.let { Enrollment(it.trustStore) }), users))
        .build()

    private fun guarded(service: Service, users: UserManager?) = ServerInterceptors.intercept(
        service,
        listOfNotNull(
            trustInterceptor,
            users?.let { AuthInterceptor(it, TOKEN_METHODS, open = ALL_METHODS - TOKEN_METHODS.keys) },
        ),
    )

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
        trustInterceptor?.close()
        server.shutdown()
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
    }

    private class Service(
        private val registry: Registry,
        private val remotes: RemoteRouters,
        private val tls: RouterTls?,
        private val enrollment: Enrollment?,
    ) : RegistryServiceGrpcKt.RegistryServiceCoroutineImplBase() {
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
            .setFingerprint(v.record.fingerprint)
            .build()

        private fun remoteEntry(r: RemoteRouterRecord): RemoteRouterEntry = RemoteRouterEntry.newBuilder()
            .setAddress(r.address)
            .setCachedEngines(r.engines.size)
            .setLastRefresh(ts(r.lastRefresh))
            .setLastError(r.lastError.orEmpty())
            .build()

        private fun requireTls(): RouterTls = tls ?: throw StatusException(Status.FAILED_PRECONDITION.withDescription("this router runs without TLS, so it has no trust"))

        /** With TLS the cached engines of a router that is not trusted are not shown. */
        private fun visible(v: EngineView): Boolean {
            val origin = v.record.origin ?: return true
            if (tls == null) return true
            return tls.trustStore.list().any { it.kind == TrustKind.ROUTER && it.address == origin }
        }

        override suspend fun registerEngine(request: RegisterEngineRequest): RegisterEngineResponse {
            if (request.engineId.value.isBlank()) throw StatusException(Status.INVALID_ARGUMENT.withDescription("engine_id is required"))
            val name = request.name.ifBlank { request.engineId.value }
            if (enrollment == null) {
                registry.register(request.engineId.value, name, request.managementAddress)
            } else {
                val fingerprint = enrollment.enroll(
                    request.engineId.value,
                    request.certificate.toByteArray(),
                    request.enrollmentSecret.toByteArray(),
                    TrustInterceptor.PEER.get(),
                    request.managementAddress,
                )
                registry.register(request.engineId.value, name, request.managementAddress, fingerprint)
            }
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
            val found = registry.lookupFabric(request.fabricId.value)?.takeIf { visible(it) }
                ?: throw StatusException(Status.NOT_FOUND.withDescription("no engine runs fabric '${request.fabricId.value}'"))
            return LookupFabricResponse.newBuilder().setEngine(entry(found)).build()
        }

        override suspend fun listEngines(request: ListEnginesRequest): ListEnginesResponse =
            ListEnginesResponse.newBuilder()
                .addAllEngines(registry.engines(request.includeRemote, request.onlyReachable).filter { visible(it) }.map { entry(it) })
                .build()

        /**
         * Shows the operator's [expected] fingerprint against the one the router at [address] presents and, if they
         * are the same, trusts that router. Returns the fingerprint.
         */
        private suspend fun trustRouter(address: String, expected: String): String {
            val tls = requireTls()
            if (!addressPattern.matches(address)) throw StatusException(Status.INVALID_ARGUMENT.withDescription("address must be host:port, got '$address'"))
            if (!PublicKeyFingerprint.pattern.matches(expected)) throw StatusException(Status.INVALID_ARGUMENT.withDescription("expected_fingerprint must be 64 lowercase hex characters"))
            val found = try {
                withContext(Dispatchers.IO) { TlsHelper.probeServerFingerprint(address.substringBeforeLast(':'), address.substringAfterLast(':').toInt()) }
            } catch (e: java.io.IOException) {
                throw StatusException(Status.UNAVAILABLE.withDescription("cannot get the certificate of $address: ${e.message}"))
            }
            if (found != expected) {
                throw StatusException(Status.FAILED_PRECONDITION.withDescription("fingerprint mismatch: expected $expected, found $found"))
            }
            tls.trustStore.add(TrustEntry(found, address, TrustKind.ROUTER, address))
            return found
        }

        override suspend fun addRemoteRouter(request: AddRemoteRouterRequest): AddRemoteRouterResponse {
            if (!addressPattern.matches(request.address)) throw StatusException(Status.INVALID_ARGUMENT.withDescription("address must be host:port, got '${request.address}'"))
            if (tls != null) {
                if (request.expectedFingerprint.isBlank()) {
                    throw StatusException(Status.INVALID_ARGUMENT.withDescription("expected_fingerprint is required: confirm the fingerprint of the router"))
                }
                trustRouter(request.address, request.expectedFingerprint)
            }
            registry.addRemote(request.address)
            remotes.refresh(request.address)
            return AddRemoteRouterResponse.newBuilder().setRouter(remoteEntry(registry.remotes().first { it.address == request.address })).build()
        }

        override suspend fun trustRemoteRouter(request: TrustRemoteRouterRequest): TrustRemoteRouterResponse =
            TrustRemoteRouterResponse.newBuilder().setFingerprint(trustRouter(request.address, request.expectedFingerprint)).build()

        override suspend fun revokeRemoteRouter(request: RevokeRemoteRouterRequest): RevokeRemoteRouterResponse {
            val tls = requireTls()
            val routers = tls.trustStore.list().filter { it.kind == TrustKind.ROUTER && it.address == request.address }
            val known = registry.remotes().any { it.address == request.address }
            if (routers.isEmpty() && !known) throw StatusException(Status.NOT_FOUND.withDescription("remote router '${request.address}' is neither trusted nor known"))
            var removed = 0
            for (router in routers) {
                removed += tls.trustStore.list().count { it.fingerprint == router.fingerprint || it.origin == router.fingerprint }
                tls.trustStore.remove(router.fingerprint)
            }
            registry.removeRemote(request.address)
            return RevokeRemoteRouterResponse.newBuilder().setRemovedEntries(removed).build()
        }

        override suspend fun listTrust(request: ListTrustRequest): ListTrustResponse = ListTrustResponse.newBuilder()
            .addAllEntries(
                requireTls().trustStore.list().map {
                    TrustEntryInfo.newBuilder()
                        .setFingerprint(it.fingerprint)
                        .setName(it.name)
                        .setKind(it.kind.name)
                        .setAddress(it.address.orEmpty())
                        .setOrigin(it.origin.orEmpty())
                        .setAddedAt(ts(it.addedAt))
                        .build()
                },
            )
            .build()

        override suspend fun prepareEngine(request: PrepareEngineRequest): PrepareEngineResponse {
            requireTls()
            enrollment!!.prepare(request.engineId.value, request.enrollmentSecretHash.toByteArray())
            return PrepareEngineResponse.getDefaultInstance()
        }

        override suspend fun removeRemoteRouter(request: RemoveRemoteRouterRequest): RemoveRemoteRouterResponse {
            if (!registry.removeRemote(request.address)) throw StatusException(Status.NOT_FOUND.withDescription("remote router '${request.address}' is not known"))
            return RemoveRemoteRouterResponse.getDefaultInstance()
        }

        override suspend fun listRemoteRouters(request: ListRemoteRoutersRequest): ListRemoteRoutersResponse =
            ListRemoteRoutersResponse.newBuilder().addAllRouters(registry.remotes().map { remoteEntry(it) }).build()
    }

    private companion object {
        const val SERVICE = "cringle.router.v1.RegistryService"
        const val REGISTER_ENGINE = "$SERVICE/RegisterEngine"
        const val PREPARE_ENGINE = "$SERVICE/PrepareEngine"

        /** The methods an operator calls, with the permission their token needs. */
        val TOKEN_METHODS: Map<String, Permission> = mapOf(
            "$SERVICE/AddRemoteRouter" to Permission.ADMINISTER,
            "$SERVICE/TrustRemoteRouter" to Permission.ADMINISTER,
            "$SERVICE/RevokeRemoteRouter" to Permission.ADMINISTER,
            "$SERVICE/ListTrust" to Permission.READ,
        )

        val ALL_METHODS: Set<String> = TrustInterceptor.methodNames(RegistryServiceGrpc.getServiceDescriptor().methods)
    }
}
