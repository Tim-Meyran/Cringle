// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
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
        val registry = daemon.router!!.registry
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (registry.engines().none { it.record.id == "comb" }) {
            check(System.nanoTime() < end) { "engine did not register at the router; logs:\n" + logs() }
            Thread.sleep(100)
        }
        assertEquals("Combined", registry.engines().single().record.name)
        api.stopEngine(req("comb"))
        val end2 = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (registry.engines().isNotEmpty()) {
            check(System.nanoTime() < end2) { "engine did not unregister" }
            Thread.sleep(100)
        }
    }
}
