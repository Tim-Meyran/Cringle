// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import com.google.protobuf.Timestamp
import cringle.common.v1.CertificateInfo
import cringle.common.v1.EngineId
import cringle.engine.v1.ConfigureRequest
import cringle.engine.v1.ConfigureResponse
import cringle.engine.v1.EngineManagementServiceGrpcKt
import cringle.engine.v1.EngineState
import cringle.engine.v1.GetStatusRequest
import cringle.engine.v1.GetStatusResponse
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

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
) {
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

    /** The engine's current configuration. */
    public val config: EngineConfig get() = synchronized(lock) { currentConfig }

    /** Port of the management server; valid after [start]. */
    public val managementPort: Int get() = server.port

    /** Starts the management server and publishes its port in `<engine dir>/management.port`. */
    public fun start(): Engine {
        server.start()
        Files.writeString(dir.resolve(PORT_FILE), managementPort.toString() + "\n")
        return this
    }

    /** Stops the management server gracefully and removes the port file. Safe to call more than once. */
    public fun stop() {
        state = EngineState.ENGINE_STATE_STOPPING
        server.shutdown()
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        Files.deleteIfExists(dir.resolve(PORT_FILE))
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
            synchronized(lock) {
                if (request.hasRouterAddress()) {
                    val address = request.routerAddress.takeIf { it.isNotEmpty() }
                    currentConfig = currentConfig.copy(routerAddress = address).also { it.save(dir) }
                }
                return ConfigureResponse.newBuilder().setRouterAddress(currentConfig.routerAddress.orEmpty()).build()
            }
        }
    }

    public companion object {
        /** Name of the file that holds the management port of a running engine. */
        public const val PORT_FILE: String = "management.port"

        /**
         * Prepares an engine for [args]: resolves the home, loads or creates config and identity. The server is not
         * started yet. Throws [EngineArgsException] if insecure dev mode is missing.
         */
        public fun create(args: EngineArgs, env: Map<String, String> = System.getenv()): Engine {
            if (!args.insecureDevMode) {
                throw EngineArgsException(
                    "secure (mTLS) management is not available yet (issue #13); start with --insecure-dev-mode",
                )
            }
            val dir = CringleHome.engineDir(CringleHome.resolve(args.home, env), args.id)
            Files.createDirectories(dir)
            val config = EngineConfig.loadOrCreate(dir, args.id, args.name)
            val identity = EngineIdentity.loadOrCreate(dir, args.id)
            return Engine(dir, identity, config, args.managementPort)
        }

        private fun timestamp(i: Instant): Timestamp = Timestamp.newBuilder().setSeconds(i.epochSecond).setNanos(i.nano).build()
    }
}
