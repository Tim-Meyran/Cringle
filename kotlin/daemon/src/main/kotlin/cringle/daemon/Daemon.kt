// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import com.google.protobuf.ByteString
import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.common.LocalTrust
import cringle.common.TlsHelper
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.TrustStore
import cringle.common.v1.EngineId
import cringle.engine.CringleHome
import cringle.engine.EngineArgs
import cringle.engine.EngineIdentity
import cringle.router.RouterServer
import cringle.router.RouterTls
import cringle.router.v1.PrepareEngineRequest
import cringle.router.v1.RegistryServiceGrpcKt
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/**
 * The daemon of one machine (Architecture 4.1): keeps the list of engines, starts and supervises their processes and
 * offers this through [cringle.daemon.v1.DaemonServiceGrpcKt]. In combined mode ([router] set) it runs the router of the
 * machine in the same process and points every engine to it (Architecture 4.3); otherwise [routerAddress] is used.
 *
 * The gRPC server speaks mutual TLS on the loopback interface: it presents the identity of the daemon and accepts only
 * peers in the trust store of the daemon (`<home>/daemon/trust.json`, kind `COMPONENT`: the management server, the CLI).
 * Every engine the daemon creates gets its identity from the daemon and an `ENGINE` entry in that trust store, so the
 * daemon is the only caller of the engine API that needs no entry from outside.
 */
public class Daemon(
    private val home: Path,
    port: Int = 0,
    private val routerAddress: String? = null,
    combined: Boolean = false,
    command: EngineCommand = EngineCommand(),
    startTimeout: java.time.Duration = java.time.Duration.ofSeconds(90),
    stopTimeout: java.time.Duration = java.time.Duration.ofSeconds(30),
    /**
     * Trust the management server of the same home (`<home>/management`): its identity is created if it is missing and its key
     * entered as `COMPONENT`, here and in the router of combined mode, so that no fingerprint has to be copied (`LocalTrust`).
     */
    trustLocal: Boolean = false,
) : AutoCloseable {
    private val daemonDir = home.resolve("daemon")
    private val daemonIdentity: Identity = Identity.loadOrCreate(daemonDir, ComponentKind.DAEMON.commonName("daemon"))
    private val daemonTrustStore: TrustStore = TrustStore(daemonDir.resolve("trust.json"))
    private val certificateWatchers = arrayListOf(cringle.common.CertificateWatcher(daemonIdentity))

    /** The fingerprint of the management server of this home when [trustLocal] is on, `null` otherwise. */
    private val localManagement: String? = if (trustLocal) {
        LocalTrust.ensure(home.resolve("management"), ComponentKind.MANAGEMENT.commonName("management")).also {
            LocalTrust.trust(daemonTrustStore, it, "management", TrustKind.COMPONENT)
        }
    } else null

    /** The fingerprint of the key of the daemon, which the engines and the management server have to trust. */
    public val identityFingerprint: String get() = daemonIdentity.publicKeyFingerprint

    /** The peers the daemon accepts and the engines it knows. */
    public val trustStore: TrustStore get() = daemonTrustStore

    /** The router that runs inside this process in combined mode, `null` otherwise. */
    public val router: RouterServer? = if (combined) {
        val routerDir = home.resolve("router")
        val routerIdentity = Identity.loadOrCreate(routerDir, ComponentKind.ROUTER.commonName("router"))
        certificateWatchers += cringle.common.CertificateWatcher(routerIdentity)
        val routerTrustStore = TrustStore(routerDir.resolve("trust.json"))
        RouterServer(
            routerDir.resolve("registry.json"),
            tls = RouterTls(routerIdentity, routerTrustStore),
        )
    } else null

    private val register = EngineRegister(daemonDir.resolve("engines.json"))
    private val engines = LinkedHashMap<String, RegisteredEngine>()
    private val random = SecureRandom()

    /** Supervises the engine processes. */
    public val supervisor: EngineSupervisor = EngineSupervisor(
        home,
        command,
        { router?.let { "127.0.0.1:${it.port}" } ?: routerAddress },
        startTimeout,
        stopTimeout,
        // An engine that is stopped by the daemon may not get to unregister itself (on Windows the process is killed).
        onStopped = { id, _ -> router?.registry?.unregister(id) },
        // combined mode: the router of this process; a separate router is announced to by mTLS from the daemon (#114)
        announce = ::announce,
        // writes the engine's trust file before the process starts so it can talk to the router over mTLS
        writeTrustFile = ::writeEngineTrustFile,
        engineCredentials = { TlsHelper.channelCredentials(daemonIdentity, daemonTrustStore) },
    )

    /** The LoggingCollector of this machine (#195); it collects for the engines it is switched on for. */
    internal val collector: LoggingCollector = LoggingCollector(
        daemonDir,
        { supervisor.list() },
        LoggingCollector.overGrpc { TlsHelper.channelCredentials(daemonIdentity, daemonTrustStore) },
    )

    private val server: Server = NettyServerBuilder
        .forAddress(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        .sslContext(TlsHelper.serverCredentials(daemonIdentity, daemonTrustStore))
        .addService(DaemonGrpcService(this))
        .build()

    init {
        register.load().forEach {
            engines[it.id] = it
            supervisor.add(it.id, it.name)
            trustEngine(it.id)
        }
    }

    /** The port of the gRPC server; valid after [start]. */
    public val port: Int get() = server.port

    /** Starts the router (combined mode) and the gRPC server. */
    public fun start(): Daemon {
        certificateWatchers.forEach { it.start() }
        router?.start()
        if (router != null) {
            val routerAddr = "127.0.0.1:${router.port}"
            daemonTrustStore.add(TrustEntry(router.identity!!.publicKeyFingerprint, "router", TrustKind.ROUTER, address = routerAddr))
            router.tls!!.trustStore.add(TrustEntry(daemonIdentity.publicKeyFingerprint, "daemon", TrustKind.COMPONENT))
            localManagement?.let { LocalTrust.trust(router.tls!!.trustStore, it, "management", TrustKind.COMPONENT) }
        }
        server.start()
        collector.start()
        return this
    }

    /** Registers a new engine; allocates an id if [id] is `null`. Does not start it. */
    public fun createEngine(id: String?, name: String?): EngineSnapshot = synchronized(engines) {
        val engineId = id?.takeIf { it.isNotEmpty() } ?: allocateId()
        if (!EngineArgs.idPattern.matches(engineId)) {
            throw DaemonException(DaemonError.INVALID, "engine id '$engineId' must match ${EngineArgs.idPattern.pattern}")
        }
        if (name != null && name.isNotEmpty() && name.isBlank()) throw DaemonException(DaemonError.INVALID, "name must not be blank")
        if (engineId in engines) throw DaemonException(DaemonError.ALREADY_EXISTS, "engine '$engineId' already exists")
        val entry = RegisteredEngine(engineId, name?.takeIf { it.isNotEmpty() } ?: engineId)
        engines[engineId] = entry
        register.save(engines.values.toList())
        supervisor.add(entry.id, entry.name)
        trustEngine(entry.id)
        supervisor.get(entry.id)
    }

    /**
     * Creates the identity of the engine [id] (in its directory, where the engine process loads it) and trusts its key as
     * `ENGINE`, so the daemon can call the engine API as soon as the process is up.
     */
    private fun trustEngine(id: String) {
        val identity = EngineIdentity.loadOrCreate(CringleHome.engineDir(home, id), id)
        daemonTrustStore.add(TrustEntry(identity.publicKeyFingerprint, id, TrustKind.ENGINE))
    }

    private fun allocateId(): String {
        while (true) {
            val candidate = "e-" + ByteArray(4).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            if (candidate !in engines) return candidate
        }
    }

    /**
     * Announces at the router that [engineId] will register with the secret whose SHA-256 is [secretHash]
     * (`PrepareEngine`). In combined mode the in-process router is used directly; in separate router mode a TLS
     * channel to [routerAddress] is opened with the daemon's identity and trust store.
     */
    private fun announce(engineId: String, secretHash: ByteArray) {
        if (router != null) {
            router.enrollment!!.prepare(engineId, secretHash)
            return
        }
        val address = routerAddress ?: throw DaemonException(DaemonError.FAILED_PRECONDITION, "no router configured")
        val (host, port) = address.substringBeforeLast(':') to address.substringAfterLast(':').toInt()
        val channel = NettyChannelBuilder.forAddress(host, port)
            .sslContext(TlsHelper.channelCredentials(daemonIdentity, daemonTrustStore))
            .build()
        try {
            runBlocking {
                RegistryServiceGrpcKt.RegistryServiceCoroutineStub(channel)
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .prepareEngine(
                        PrepareEngineRequest.newBuilder()
                            .setEngineId(EngineId.newBuilder().setValue(engineId))
                            .setEnrollmentSecretHash(ByteString.copyFrom(secretHash))
                            .build(),
                    )
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    /**
     * Writes `<engineDir>/trust.json` with the peers the engine accepts: the daemon, every `COMPONENT` of the daemon's
     * trust store (the management server, the CLI) for the management API, and, if the engine talks to a router, that
     * router. The router's fingerprint is looked up in the daemon's trust store by address; if the router is not
     * trusted, the start fails.
     */
    private fun writeEngineTrustFile(engineId: String) {
        val routerEntry = (router?.let { "127.0.0.1:${it.port}" } ?: routerAddress)?.let { routerAddr ->
            daemonTrustStore.list().firstOrNull { it.kind == TrustKind.ROUTER && it.address == routerAddr }
                ?: throw DaemonException(
                    DaemonError.FAILED_PRECONDITION,
                    "router $routerAddr is not trusted; add it to the daemon trust store first",
                )
        }
        val store = TrustStore(CringleHome.engineDir(home, engineId).resolve("trust.json"))
        store.add(TrustEntry(daemonIdentity.publicKeyFingerprint, "daemon", TrustKind.COMPONENT))
        daemonTrustStore.list().filter { it.kind == TrustKind.COMPONENT }.forEach { store.add(it.copy(origin = null)) }
        routerEntry?.let { store.add(TrustEntry(it.fingerprint, it.name, TrustKind.ROUTER, address = it.address)) }
    }

    /** Runs one round of the LoggingCollector now instead of waiting for the next one; returns the number of entries it added. */
    public fun collectLogsNow(): Int = collector.collectOnce()

    /** Stops an engine and removes it; also deletes its data directory if [deleteData]. */
    public fun deleteEngine(id: String, deleteData: Boolean) {
        synchronized(engines) {
            if (id !in engines) throw DaemonException(DaemonError.NOT_FOUND, "engine '$id' is not registered")
            supervisor.remove(id)
            engines.remove(id)
            register.save(engines.values.toList())
        }
        router?.registry?.unregister(id)
        if (deleteData) {
            val dir = CringleHome.engineDir(home, id)
            if (Files.exists(dir)) Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    /** Stops the gRPC server, the router and all engine processes. */
    override fun close() {
        certificateWatchers.forEach { it.close() }
        collector.close()
        server.shutdown()
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) server.shutdownNow()
        supervisor.close()
        router?.stop()
    }
}
