// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.common.ComponentKind
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.test.TestTls
import cringle.daemon.Daemon
import cringle.daemon.v1.DaemonServiceGrpcKt
import cringle.daemon.v1.ListEnginesRequest
import cringle.engine.v1.EngineManagementServiceGrpcKt
import cringle.engine.v1.GetStatusRequest
import cringle.management.test.ManagementTls
import cringle.management.v1.ManagementServiceGrpcKt.ManagementServiceCoroutineStub
import cringle.repository.PackageRepository
import cringle.repository.RepositoryClient
import cringle.repository.RepositoryClientException
import cringle.repository.RepositoryServer
import cringle.repository.RepositoryTls
import cringle.repository.v1.ListPackagesRequest
import cringle.repository.v1.RepositoryServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The proof of #7 (mTLS Teil 3): the links of the management server to a daemon, an engine and a repository, and of a
 * daemon to an engine, are mutual TLS. A peer without an entry in the trust store of the other side is refused, and a
 * peer that was removed from it is refused on its next connection.
 *
 * An open connection may live on until [cringle.common.TlsHelper.SESSION_TIMEOUT_SECONDS] (60 s) after the removal of
 * a peer, because a resumed TLS session skips the check; no test waits for that, every check opens a new connection with
 * a new context.
 */
class ManagementTlsTest {
    private lateinit var dir: Path
    private lateinit var tls: ManagementTls
    private lateinit var daemon: Daemon
    private lateinit var repository: RepositoryServer
    private lateinit var core: ManagementCore
    private lateinit var stranger: TestTls
    private val closeables = ArrayList<AutoCloseable>()

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("cringle-mgmt-tls-test")
        val home = Files.createDirectories(dir.resolve("home"))
        tls = ManagementTls(dir.resolve("tls"))
        stranger = TestTls(dir.resolve("stranger"))
        stranger.identity("stranger", ComponentKind.MANAGEMENT)
        daemon = Daemon(home).start()
        closeables += daemon
        tls.trust(daemon)
        repository = tls.startRepository(PackageRepository(dir.resolve("repo")))
        closeables += AutoCloseable { repository.stop() }
        core = tls.core(ManagementStore(dir.resolve("state.json")), "127.0.0.1:${repository.port}")
        closeables += core
    }

    @AfterEach
    fun tearDown() {
        closeables.reversed().forEach { runCatching { it.close() } }
        repeat(10) {
            if (runCatching { dir.toFile().deleteRecursively() }.getOrDefault(false) || !Files.exists(dir)) return
            Thread.sleep(200)
        }
    }

    /**
     * A channel of a peer that nobody entered into a trust store. It trusts the server with [serverFingerprint], so that
     * only the server decides whether the connection is allowed.
     */
    private fun strangerChannel(port: Int, serverFingerprint: String): ManagedChannel {
        stranger.trustStore("stranger").add(TrustEntry(serverFingerprint, "server-$port", TrustKind.COMPONENT))
        return NettyChannelBuilder.forAddress("127.0.0.1", port).sslContext(stranger.clientSsl("stranger")).build()
            .also { closeables += AutoCloseable { it.shutdownNow() } }
    }

    private fun code(body: suspend () -> Unit): Status.Code = try {
        runBlocking { body() }
        Status.Code.OK
    } catch (e: StatusException) {
        e.status.code
    }

    /** MS to daemon, MS to engine and MS to repository work over mTLS; a stranger is refused at every one of the three. */
    @Test
    fun managementServerToDaemonEngineAndRepositoryAreMtlsAndStrangersAreRefused(): Unit = runBlocking {
        val machine = core.addMachine("m1", "127.0.0.1:${daemon.port}", null, null)
        assertTrue(machine.reachable, "the management server has to reach the daemon over mTLS: ${machine.lastError}")
        core.createEngine("m1", "e1", null, false)
        val engine = core.startEngine("m1", "e1")
        assertNotNull(engine.status, "the management server has to reach the engine over mTLS")
        val enginePort = engine.process.managementPort
        val server = ManagementServer(core, recoverOnStart = false).start().also { closeables += it }
        val plain = ManagedChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
            .also { closeables += AutoCloseable { it.shutdownNow() } }
        assertEquals(
            0,
            ManagementServiceCoroutineStub(plain).listPackages(ListPackagesRequest.getDefaultInstance()).packagesCount,
            "the management server has to reach the repository over mTLS",
        )

        val engineFingerprint = daemon.trustStore.list().single { it.kind == TrustKind.ENGINE && it.name == "e1" }.fingerprint
        assertEquals(
            Status.Code.UNAVAILABLE,
            code { DaemonServiceGrpcKt.DaemonServiceCoroutineStub(strangerChannel(daemon.port, daemon.identityFingerprint)).listEngines(ListEnginesRequest.getDefaultInstance()) },
            "the daemon has to refuse a peer that is not in its trust store",
        )
        assertEquals(
            Status.Code.UNAVAILABLE,
            code { EngineManagementServiceGrpcKt.EngineManagementServiceCoroutineStub(strangerChannel(enginePort, engineFingerprint)).getStatus(GetStatusRequest.getDefaultInstance()) },
            "the engine has to refuse a peer that is not in its trust store",
        )
        assertEquals(
            Status.Code.UNAVAILABLE,
            code { RepositoryServiceGrpcKt.RepositoryServiceCoroutineStub(strangerChannel(repository.port, tls.repositoryFingerprint(0))).listPackages(ListPackagesRequest.getDefaultInstance()) },
            "the repository has to refuse a peer that is not in its trust store",
        )
    }

    /** Daemon to engine: the daemon configures the engine over mTLS, which is how the engine learns the router and registers. */
    @Test
    fun daemonToEngineIsMtlsInCombinedMode() {
        val combined = Daemon(dir.resolve("home-combined"), combined = true).start()
        closeables += combined
        combined.createEngine("c1", "Combined")
        combined.supervisor.start("c1")
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (combined.router!!.registry.engines().none { it.record.id == "c1" }) {
            check(System.nanoTime() < end) { "engine c1 did not register: the daemon could not configure it over mTLS" }
            Thread.sleep(50)
        }
    }

    /** A peer that is removed from a trust store is refused on its next connection. */
    @Test
    fun aRemovedPeerIsRefusedOnTheNextConnection(): Unit = runBlocking {
        core.addMachine("m1", "127.0.0.1:${daemon.port}", null, null)
        assertTrue(core.listMachines().single().reachable)

        assertTrue(daemon.trustStore.remove(tls.identity.publicKeyFingerprint), "the management server was a trusted peer of the daemon")
        // the first core keeps its channel (and its TLS session) open, so a second core opens a connection that is checked again
        val fresh = tls.core(ManagementStore(dir.resolve("state2.json")))
        closeables += fresh
        val view = fresh.addMachine("m2", "127.0.0.1:${daemon.port}", null, null)
        assertEquals(false, view.reachable, "the daemon has to refuse the management server after its entry was removed")
    }

    /** A client with a token but without an entry in the trust store fails at TLS, not at authentication (see also RepositoryTlsTest). */
    @Test
    fun aRepositoryClientWithATokenButWithoutATrustEntryFailsAtTls() {
        val peer = TestTls(dir.resolve("token-peer"))
        peer.identity("p", ComponentKind.MANAGEMENT)
        peer.trustStore("p").add(TrustEntry(tls.repositoryFingerprint(0), "repository", TrustKind.SERVER))
        val client = RepositoryClient("127.0.0.1:${repository.port}", "any-token", RepositoryTls(peer.identity("p"), peer.trustStore("p")))
        closeables += client
        val e = assertThrows<RepositoryClientException> { runBlocking { client.list() } }
        assertEquals(Status.Code.UNAVAILABLE, e.status)
    }
}
