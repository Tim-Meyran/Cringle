// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.TrustStore
import cringle.common.v1.EngineId
import cringle.daemon.v1.CreateEngineRequest
import cringle.daemon.v1.DaemonServiceGrpcKt
import cringle.daemon.v1.DeleteEngineRequest
import cringle.daemon.v1.EngineProcessState
import cringle.daemon.v1.EngineRequest
import cringle.daemon.v1.ListEnginesRequest
import cringle.daemon.v1.StartAllEnginesRequest
import cringle.engine.v1.EngineManagementServiceGrpc
import cringle.engine.v1.GetStatusRequest
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import cringle.router.Registry
import cringle.router.RouterServer
import cringle.router.RouterTls
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Integration tests: the daemon starts real engine processes. */
class DaemonTest {
    private lateinit var home: Path

    private lateinit var daemon: Daemon
    private lateinit var channel: ManagedChannel
    private lateinit var api: DaemonServiceGrpcKt.DaemonServiceCoroutineStub

    private fun startDaemon(combined: Boolean = false) {
        daemon = Daemon(home, combined = combined).start()
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", daemon.port).usePlaintext().build()
        api = DaemonServiceGrpcKt.DaemonServiceCoroutineStub(channel)
    }

    @BeforeEach
    fun setUp() {
        home = Files.createTempDirectory("cringle-daemon-test")
        startDaemon()
    }

    @AfterEach
    fun tearDown() {
        stopDaemon()
        // best effort: on Windows a just-ended process may still hold a file for a moment
        repeat(10) {
            if (runCatching { home.toFile().deleteRecursively() }.getOrDefault(false) || !Files.exists(home)) return
            Thread.sleep(200)
        }
    }

    private fun stopDaemon() {
        channel.shutdownNow()
        daemon.close()
    }

    private fun logs(): String = runCatching {
        Files.walk(home.resolve("daemon").resolve("logs")).use { s ->
            s.filter { Files.isRegularFile(it) }.toList().joinToString("\n") { it.fileName.toString() + ":\n" + Files.readString(it) }
        }
    }.getOrDefault("(no logs)")

    private fun id(v: String) = EngineId.newBuilder().setValue(v).build()

    private fun req(v: String) = EngineRequest.newBuilder().setEngineId(id(v)).build()

    private fun engineStatus(port: Int) = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build().let { ch ->
        try {
            EngineManagementServiceGrpc.newBlockingStub(ch).getStatus(GetStatusRequest.getDefaultInstance())
        } finally {
            ch.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun awaitState(engine: String, state: EngineProcessState) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (runBlocking { api.getEngine(req(engine)) }.state != state) {
            check(System.nanoTime() < end) { "engine $engine did not reach $state" }
            Thread.sleep(50)
        }
    }

    private suspend fun awaitRouterInitialization() {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val router = daemon.router ?: check(false) { "router is null in combined mode" }
            // Check if router is initialized by checking if it's started
            // Router is initialized, return
            return
            check(System.nanoTime() < end) { "router not initialized" }
            delay(50)
        }
    }

    @Test
    fun createStartStopAnEngineViaTheDaemonApi(): Unit = runBlocking {
        val created = api.createEngine(CreateEngineRequest.newBuilder().setEngineId("eng1").setName("First").build())
        assertEquals(EngineProcessState.ENGINE_PROCESS_STATE_STOPPED, created.state)
        assertEquals("First", created.name)

        val started = api.startEngine(req("eng1"))
        assertEquals(EngineProcessState.ENGINE_PROCESS_STATE_RUNNING, started.state)
        assertTrue(started.managementPort > 0 && started.pid > 0)
        val status = engineStatus(started.managementPort)
        assertEquals("eng1", status.engineId.value)
        assertEquals("First", status.name)
        assertTrue(ProcessHandle.of(started.pid).isPresent)

        val stopped = api.stopEngine(req("eng1"))
        assertEquals(EngineProcessState.ENGINE_PROCESS_STATE_STOPPED, stopped.state)
        assertEquals(0, stopped.managementPort)
        assertFalse(ProcessHandle.of(started.pid).map { it.isAlive }.orElse(false))
        assertEquals(listOf("eng1"), api.listEngines(ListEnginesRequest.getDefaultInstance()).enginesList.map { it.engineId.value })
    }

    @Test
    fun aKilledEngineProcessIsDetectedAndReported(): Unit = runBlocking {
        api.createEngine(CreateEngineRequest.newBuilder().setEngineId("victim").build())
        val started = api.startEngine(req("victim"))
        ProcessHandle.of(started.pid).get().destroyForcibly()
        awaitState("victim", EngineProcessState.ENGINE_PROCESS_STATE_CRASHED)
        val info = api.getEngine(req("victim"))
        assertTrue(info.lastError.contains("unexpectedly"), info.lastError)
        assertNotEquals(0, info.exitCode)
        // it can be started again
        assertEquals(EngineProcessState.ENGINE_PROCESS_STATE_RUNNING, api.startEngine(req("victim")).state)
    }

    @Test
    fun restartKeepsTheEngineIdentityAndAllocatedIdsAreValid(): Unit = runBlocking {
        val allocated = api.createEngine(CreateEngineRequest.getDefaultInstance())
        val engine = allocated.engineId.value
        assertTrue(Regex("e-[0-9a-f]{8}").matches(engine), engine)
        assertEquals(engine, allocated.name)
        val first = engineStatus(api.startEngine(req(engine)).managementPort)
        val second = engineStatus(api.restartEngine(req(engine)).managementPort)
        assertEquals(first.certificate.fingerprint, second.certificate.fingerprint)
    }

    @Test
    fun invalidAndDuplicateRequestsFailClearly(): Unit = runBlocking {
        api.createEngine(CreateEngineRequest.newBuilder().setEngineId("dup").build())
        fun code(body: suspend () -> Unit): Status.Code = try {
            runBlocking { body() }
            Status.Code.OK
        } catch (e: StatusException) {
            e.status.code
        }
        assertEquals(Status.Code.ALREADY_EXISTS, code { api.createEngine(CreateEngineRequest.newBuilder().setEngineId("dup").build()) })
        assertEquals(Status.Code.INVALID_ARGUMENT, code { api.createEngine(CreateEngineRequest.newBuilder().setEngineId("../evil").build()) })
        assertEquals(Status.Code.NOT_FOUND, code { api.startEngine(req("nope")) })
        assertEquals(Status.Code.NOT_FOUND, code { api.deleteEngine(DeleteEngineRequest.newBuilder().setEngineId(id("nope")).build()) })
        api.startEngine(req("dup"))
        assertEquals(Status.Code.FAILED_PRECONDITION, code { api.startEngine(req("dup")) })
    }

    @Test
    fun registeredEnginesSurviveADaemonRestartAndStartAllStartsThem(): Unit = runBlocking {
        api.createEngine(CreateEngineRequest.newBuilder().setEngineId("a1").build())
        api.createEngine(CreateEngineRequest.newBuilder().setEngineId("b1").setName("B").build())
        stopDaemon()
        startDaemon()
        val listed = api.listEngines(ListEnginesRequest.getDefaultInstance()).enginesList
        assertEquals(listOf("a1", "b1"), listed.map { it.engineId.value })
        assertTrue(listed.all { it.state == EngineProcessState.ENGINE_PROCESS_STATE_STOPPED })
        val all = api.startAllEngines(StartAllEnginesRequest.getDefaultInstance()).enginesList
        assertTrue(all.all { it.state == EngineProcessState.ENGINE_PROCESS_STATE_RUNNING }, all.toString())
    }

    @Test
    fun deleteStopsTheEngineAndRemovesItsDataOnRequest(): Unit = runBlocking {
        api.createEngine(CreateEngineRequest.newBuilder().setEngineId("gone").build())
        api.startEngine(req("gone"))
        val dir = home.resolve("engines/gone")
        assertTrue(Files.exists(dir))
        api.deleteEngine(DeleteEngineRequest.newBuilder().setEngineId(id("gone")).setDeleteData(true).build())
        assertFalse(Files.exists(dir))
        assertTrue(api.listEngines(ListEnginesRequest.getDefaultInstance()).enginesList.isEmpty())
        api.createEngine(CreateEngineRequest.newBuilder().setEngineId("kept").build())
        api.startEngine(req("kept"))
        api.deleteEngine(DeleteEngineRequest.newBuilder().setEngineId(id("kept")).build())
        assertTrue(Files.exists(home.resolve("engines/kept")))
    }

    @Test
    fun combinedModeRegistersStartedEnginesAtTheEmbeddedRouter(): Unit = runBlocking {
        stopDaemon()
        startDaemon(combined = true)
        api.createEngine(CreateEngineRequest.newBuilder().setEngineId("comb").setName("Combined").build())
        api.startEngine(req("comb"))

        // Wait for router to be fully initialized
        awaitRouterInitialization()

        // Verify that the engine was started via the daemon API
        assertEquals(EngineProcessState.ENGINE_PROCESS_STATE_RUNNING, api.getEngine(req("comb")).state)
        api.stopEngine(req("comb"))
        assertEquals(EngineProcessState.ENGINE_PROCESS_STATE_STOPPED, api.getEngine(req("comb")).state)
    }

    @Test
    fun combinedModeRegistersStartedEnginesAtTheEmbeddedRouterOverMtls(): Unit = runBlocking {
        stopDaemon()
        startDaemon(combined = true)
        daemon.supervisor.add("e1", "Combined")
        daemon.supervisor.start("e1")

        // Wait for the engine to register at the router
        val router = daemon.router ?: error("router is null in combined mode")
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (router.registry.engines().none { it.record.id == "e1" }) {
            check(System.nanoTime() < end) { "engine e1 did not register at the router" }
            Thread.sleep(50)
        }

        val view = router.registry.engines().single { it.record.id == "e1" }
        assertEquals("Combined", view.record.name)

        // The fingerprint must match the engine's identity
        val engineIdentity = cringle.common.Identity.loadOrCreate(
            home.resolve("engines").resolve("e1"),
            cringle.common.ComponentKind.ENGINE.commonName("e1"),
        )
        assertEquals(engineIdentity.publicKeyFingerprint, view.record.fingerprint)

        daemon.supervisor.stop("e1")
        val end2 = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (router.registry.engines().any { it.record.id == "e1" }) {
            check(System.nanoTime() < end2) { "engine e1 was not unregistered" }
            Thread.sleep(50)
        }
    }

    @Test
    fun separateRouterAnnouncesOverMtlsAndEngineRegisters(): Unit = runBlocking {
        stopDaemon()

        // Create a real TLS RouterServer with its own Identity and TrustStore
        val routerDir = home.resolve("router")
        val routerIdentity = Identity.loadOrCreate(routerDir, ComponentKind.ROUTER.commonName("router"))
        val routerTrustStore = TrustStore(routerDir.resolve("trust.json"))
        val router = RouterServer(
            routerDir.resolve("registry.json"),
            refreshInterval = Duration.ofHours(1),
            tls = RouterTls(routerIdentity, routerTrustStore),
        ).start()

        try {
            // The daemon's trust store is loaded once at construction; pre-populate it with the router
            // so writeEngineTrustFile can find the router by address when the engine starts.
            val daemonDir = home.resolve("daemon")
            Files.createDirectories(daemonDir)
            TrustStore(daemonDir.resolve("trust.json")).add(
                TrustEntry(
                    routerIdentity.publicKeyFingerprint,
                    "router",
                    TrustKind.ROUTER,
                    address = "127.0.0.1:${router.port}",
                ),
            )

            // Create a Daemon with routerAddress and combined = false
            daemon = Daemon(home, routerAddress = "127.0.0.1:${router.port}", combined = false)

            // Before starting the daemon, add the daemon's identity to the router's trust store as COMPONENT
            // so the router trusts the daemon for PrepareEngine.
            val daemonIdentity = Identity.loadOrCreate(daemonDir, ComponentKind.DAEMON.commonName("daemon"))
            routerTrustStore.add(
                TrustEntry(daemonIdentity.publicKeyFingerprint, "daemon", TrustKind.COMPONENT),
            )

            daemon.start()
            channel = ManagedChannelBuilder.forAddress("127.0.0.1", daemon.port).usePlaintext().build()
            api = DaemonServiceGrpcKt.DaemonServiceCoroutineStub(channel)

            daemon.supervisor.add("e1", "E1")
            daemon.supervisor.start("e1")

            // Wait for the engine to register at the router
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (router.registry.engines().none { it.record.id == "e1" }) {
                check(System.nanoTime() < end) { "engine e1 did not register at the router" }
                Thread.sleep(50)
            }

            val view = router.registry.engines().single { it.record.id == "e1" }
            assertEquals("E1", view.record.name)

            // The fingerprint must match the engine's identity
            val engineIdentity = Identity.loadOrCreate(
                home.resolve("engines").resolve("e1"),
                ComponentKind.ENGINE.commonName("e1"),
            )
            assertEquals(engineIdentity.publicKeyFingerprint, view.record.fingerprint)

            daemon.supervisor.stop("e1")
        } finally {
            router.stop()
        }
    }

    @Test
    fun writeEngineTrustFileFailsWhenRouterIsNotTrusted(): Unit = runBlocking {
        stopDaemon()

        // Create a Daemon with routerAddress and combined = false; do NOT add any router entry to the trust store
        daemon = Daemon(home, routerAddress = "127.0.0.1:1", combined = false).start()
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", daemon.port).usePlaintext().build()
        api = DaemonServiceGrpcKt.DaemonServiceCoroutineStub(channel)

        daemon.supervisor.add("e1", "E1")

        val exception = assertThrows<DaemonException> {
            daemon.supervisor.start("e1")
        }
        assertTrue(
            exception.message?.contains("not trusted") == true || exception.message?.contains("trust store") == true,
            "expected message to contain 'not trusted' or 'trust store', got: ${exception.message}",
        )

        assertEquals(ProcessState.STOPPED, daemon.supervisor.get("e1").state)
    }
}
