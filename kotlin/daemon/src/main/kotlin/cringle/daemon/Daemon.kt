// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.TrustStore
import cringle.engine.CringleHome
import cringle.engine.EngineArgs
import cringle.router.RouterServer
import cringle.router.RouterTls
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * The daemon of one machine (Architecture 4.1): keeps the list of engines, starts and supervises their processes and
 * offers this through [cringle.daemon.v1.DaemonServiceGrpcKt]. In combined mode ([router] set) it runs the router of the
 * machine in the same process and points every engine to it (Architecture 4.3); otherwise [routerAddress] is used.
 *
 * The gRPC server is plaintext on the loopback interface until trust management exists (#13).
 */
public class Daemon(
    private val home: Path,
    port: Int = 0,
    private val routerAddress: String? = null,
    combined: Boolean = false,
    command: EngineCommand = EngineCommand(),
    startTimeout: java.time.Duration = java.time.Duration.ofSeconds(90),
    stopTimeout: java.time.Duration = java.time.Duration.ofSeconds(30),
) : AutoCloseable {
    private val daemonDir = home.resolve("daemon")
    private val daemonIdentity: Identity = Identity.loadOrCreate(daemonDir, ComponentKind.DAEMON.commonName("daemon"))
    private val daemonTrustStore: TrustStore = TrustStore(daemonDir.resolve("trust.json"))

    /** The router that runs inside this process in combined mode, `null` otherwise. */
    public val router: RouterServer? = if (combined) {
        val routerDir = home.resolve("router")
        val routerIdentity = Identity.loadOrCreate(routerDir, ComponentKind.ROUTER.commonName("router"))
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
        announce = { id, hash -> router?.enrollment?.prepare(id, hash) },
    )

    private val server: Server = NettyServerBuilder
        .forAddress(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        .addService(DaemonGrpcService(this))
        .build()

    init {
        register.load().forEach {
            engines[it.id] = it
            supervisor.add(it.id, it.name)
        }
    }

    /** The port of the gRPC server; valid after [start]. */
    public val port: Int get() = server.port

    /** Starts the router (combined mode) and the gRPC server. */
    public fun start(): Daemon {
        router?.start()
        if (router != null) {
            val routerAddr = "127.0.0.1:${router.port}"
            daemonTrustStore.add(TrustEntry(router.identity!!.publicKeyFingerprint, "router", TrustKind.ROUTER, address = routerAddr))
            router.tls!!.trustStore.add(TrustEntry(daemonIdentity.publicKeyFingerprint, "daemon", TrustKind.COMPONENT))
        }
        server.start()
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
        supervisor.get(entry.id)
    }

    private fun allocateId(): String {
        while (true) {
            val candidate = "e-" + ByteArray(4).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            if (candidate !in engines) return candidate
        }
    }

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
        server.shutdown()
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) server.shutdownNow()
        supervisor.close()
        router?.stop()
    }
}
