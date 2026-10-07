// SPDX-License-Identifier: Apache-2.0

package cringle.management

import com.google.protobuf.ByteString
import cringle.management.test.ManagementTls
import cringle.common.v1.EngineId
import cringle.common.v1.FabricId
import cringle.common.v1.PluginRef
import cringle.common.v1.ProjectRef
import cringle.contract.BlockDefinition
import cringle.contract.LogEntry
import cringle.contract.LogLevel
import cringle.contract.SchemaRef
import cringle.contract.UserRole
import cringle.daemon.Daemon
import cringle.engine.CringleHome
import cringle.engine.drivers.LoggingService
import cringle.engine.v1.DeployFabricRequest as EngineDeploy
import cringle.engine.v1.DeployedPlugin
import cringle.engine.v1.FabricRuntimeState
import cringle.engine.v1.PluginTrust as EngineTrust
import cringle.management.v1.AddMachineRequest
import cringle.management.v1.CreateEngineRequest
import cringle.management.v1.DeleteEngineRequest
import cringle.management.v1.DeployFabricRequest
import cringle.management.v1.EngineRef
import cringle.management.v1.FabricRef
import cringle.management.v1.ListEnginesRequest
import cringle.management.v1.ListFabricsRequest
import cringle.management.v1.ListMachinesRequest
import cringle.management.v1.ManagementServiceGrpcKt.ManagementServiceCoroutineStub
import cringle.management.v1.MachineRequest
import cringle.management.v1.QueryLogsRequest
import cringle.management.v1.RecoverRequest
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.PackageWriter
import cringle.packaging.PluginManifest
import cringle.packaging.SafeUnzip
import cringle.repository.PackageRepository
import cringle.repository.RepositoryServer
import cringle.repository.v1.DownloadRequest
import cringle.repository.v1.ListPackagesRequest
import cringle.repository.v1.PublishHeader
import cringle.repository.v1.PublishRequest
import cringle.router.RouterServer
import cringle.router.users.AuthInterceptor
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import cringle.router.v1.AddRemoteRouterRequest
import cringle.router.v1.ListRemoteRoutersRequest
import cringle.testkit.TestJar
import cringle.testkit.TestPluginBuilder
import cringle.testkit.TestProjectBuilder
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusException
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** End-to-end tests with real Daemons and Engine processes. */
class ManagementServerTest {
    private lateinit var dir: Path
    private lateinit var home: Path
    private val closeables = ArrayList<AutoCloseable>()

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("cringle-mgmt-test")
        home = Files.createDirectories(dir.resolve("home"))
    }

    @AfterEach
    fun tearDown() {
        closeables.reversed().forEach { runCatching { it.close() } }
        repeat(10) {
            if (runCatching { dir.toFile().deleteRecursively() }.getOrDefault(false) || !Files.exists(dir)) return
            Thread.sleep(200)
        }
    }

    private fun <T : AutoCloseable> track(c: T): T = c.also { closeables += it }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private val tls by lazy { ManagementTls(dir.resolve("tls")) }

    private fun startDaemon(port: Int = 0, combined: Boolean = false): Daemon = track(Daemon(home, port, combined = combined).start()).also(tls::trust)

    private fun startManagement(users: UserManager? = null, repository: String? = null, router: String? = null, token: String? = null, recover: Boolean = false): ManagementServer =
        track(ManagementServer(tls.core(ManagementStore(dir.resolve("state.json")), repository, token, router), users = users, recoverOnStart = recover).start())

    private fun stub(server: ManagementServer, token: String? = null): ManagementServiceCoroutineStub {
        val channel: ManagedChannel = ManagedChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
        closeables += AutoCloseable { channel.shutdownNow() }
        val s = ManagementServiceCoroutineStub(channel)
        if (token == null) return s
        return s.withInterceptors(object : ClientInterceptor {
            override fun <Q, R> interceptCall(method: MethodDescriptor<Q, R>, options: CallOptions, next: Channel): ClientCall<Q, R> =
                object : ForwardingClientCall.SimpleForwardingClientCall<Q, R>(next.newCall(method, options)) {
                    override fun start(listener: Listener<R>, headers: Metadata) {
                        headers.put(AuthInterceptor.AUTHORIZATION, "Bearer $token")
                        super.start(listener, headers)
                    }
                }
        })
    }

    private fun engineRef(machine: String, id: String) = EngineRef.newBuilder().setMachineId(machine).setEngineId(EngineId.newBuilder().setValue(id)).build()

    private fun fabricRef(machine: String, engine: String, fabric: String) =
        FabricRef.newBuilder().setEngine(engineRef(machine, engine)).setFabricId(FabricId.newBuilder().setValue(fabric)).build()

    private fun code(body: suspend () -> Unit): Status.Code = assertThrows<StatusException> { runBlocking { body() } }.status.code

    private suspend fun addMachine(s: ManagementServiceCoroutineStub, daemon: Daemon, id: String = "m1") =
        s.addMachine(AddMachineRequest.newBuilder().setMachineId(id).setDaemonAddress("127.0.0.1:${daemon.port}").build())

    private suspend fun createAndStart(s: ManagementServiceCoroutineStub, id: String = "e1", machine: String = "m1") {
        s.createEngine(CreateEngineRequest.newBuilder().setMachineId(machine).setEngineId(id).setName("Engine $id").build())
        s.startEngine(engineRef(machine, id))
    }

    private fun awaitTrue(seconds: Long = 30, what: String, condition: suspend () -> Boolean) = runBlocking {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            check(System.nanoTime() < end) { "timeout: $what" }
            Thread.sleep(100)
        }
    }

    // --- fixtures: a plugin whose block writes a marker file when it starts ---

    private val markerSchema = """{"namespace":"acme.demo","types":{"MarkerConfig":{"record":{"marker":"cringle.std/String"}}}}"""

    private val providerSource = mapOf(
        "com.acme.MarkerProvider" to """
            package com.acme;
            import cringle.contract.*;
            import java.util.List;
            public class MarkerProvider implements BlockProvider {
                public List<BlockDefinition> getDefinitions() {
                    return List.of(new BlockDefinition("marker", List.of(), List.of(), List.of(), new SchemaRef("acme.demo", "MarkerConfig")));
                }
                public Block createBlock(String name, DriverSet drivers) { return new MarkerBlock(); }
            }
        """.trimIndent(),
        "com.acme.MarkerBlock" to """
            package com.acme;
            import cringle.contract.*;
            import kotlin.Unit;
            import kotlin.coroutines.Continuation;
            import java.nio.file.*;
            public class MarkerBlock implements Block {
                private String marker;
                public Object init(BlockContext c, Continuation<? super Unit> k) { marker = (String) c.getConfig().get("marker"); return Unit.INSTANCE; }
                public Object start(Continuation<? super Unit> k) { write("started"); return Unit.INSTANCE; }
                public Object stop(Continuation<? super Unit> k) { write("stopped"); return Unit.INSTANCE; }
                public Object destroy(Continuation<? super Unit> k) { return Unit.INSTANCE; }
                public Object onTetherEvent(TetherEvent e, Continuation<? super Unit> k) { return Unit.INSTANCE; }
                private void write(String s) {
                    try { Files.writeString(Path.of(marker), s); } catch (java.io.IOException e) { throw new RuntimeException(e); }
                }
            }
        """.trimIndent(),
    )

    private fun installPackages(marker: Path) {
        val work = Files.createDirectories(dir.resolve("build"))
        val plugin = TestPluginBuilder("acme-demo", "1.0.0")
            .provider("com.acme.MarkerProvider")
            .block(BlockDefinition("marker", emptyList(), emptyList(), emptyList(), SchemaRef("acme.demo", "MarkerConfig")))
            .schema("marker.json", markerSchema)
            .lib("marker.jar", TestJar.fromJavaSources(providerSource))
            .build(work)
        val block = BlueprintBlock("m1", "acme-demo/marker", config = JsonObject(mapOf("marker" to JsonPrimitive(marker.toString()))))
        val project = TestProjectBuilder("demo", "0.1.0")
            .dependency("acme-demo", "^1.0.0")
            .blueprint(Blueprint("main", listOf(block), emptyList()))
            .build(work, listOf(plugin.pkg))
        SafeUnzip.extract(plugin.file, home.resolve("plugins/acme-demo/1.0.0"))
        SafeUnzip.extract(project.file, home.resolve("projects/demo/0.1.0"))
    }

    private fun deployRequest(machine: String, engine: String, fabric: String, start: Boolean) = DeployFabricRequest.newBuilder()
        .setEngine(engineRef(machine, engine))
        .setStart(start)
        .setDeploy(
            EngineDeploy.newBuilder()
                .setFabricId(FabricId.newBuilder().setValue(fabric))
                .setProject(ProjectRef.newBuilder().setName("demo").setVersion("0.1.0"))
                .setBlueprint("main")
                .addPlugins(DeployedPlugin.newBuilder().setPlugin(PluginRef.newBuilder().setName("acme-demo").setVersion("1.0.0")).setTrust(EngineTrust.PLUGIN_TRUST_TRUSTED)),
        )
        .build()

    @Test
    fun managementServerCreatesAnEngineThroughADaemonAndQueriesItsStatus(): Unit = runBlocking {
        val daemon = startDaemon()
        val server = startManagement()
        val s = stub(server)
        val machine = addMachine(s, daemon)
        assertTrue(machine.reachable)
        assertEquals(1, s.listMachines(ListMachinesRequest.getDefaultInstance()).machinesCount)
        assertEquals(Status.Code.ALREADY_EXISTS, code { addMachine(s, daemon) })
        assertEquals(Status.Code.NOT_FOUND, code { s.startEngine(engineRef("nope", "e1")) })

        s.createEngine(CreateEngineRequest.newBuilder().setMachineId("m1").setEngineId("e1").setName("First").build())
        val listed = s.listEngines(ListEnginesRequest.newBuilder().setMachineId("m1").build())
        assertEquals(listOf("e1"), listed.enginesList.map { it.process.engineId.value })
        assertTrue(listed.getEngines(0).autostart)

        val started = s.startEngine(engineRef("m1", "e1"))
        assertEquals(cringle.daemon.v1.EngineProcessState.ENGINE_PROCESS_STATE_RUNNING, started.process.state)
        assertEquals("First", started.status.name)
        assertEquals("e1", s.getEngine(engineRef("m1", "e1")).status.engineId.value)

        s.stopEngine(engineRef("m1", "e1"))
        assertFalse(s.getEngine(engineRef("m1", "e1")).hasStatus())
        s.deleteEngine(DeleteEngineRequest.newBuilder().setEngine(engineRef("m1", "e1")).build())
        assertEquals(0, s.listEngines(ListEnginesRequest.getDefaultInstance()).enginesCount)
        s.removeMachine(MachineRequest.newBuilder().setMachineId("m1").build())
        assertEquals(0, s.listMachines(ListMachinesRequest.getDefaultInstance()).machinesCount)
    }

    @Test
    fun enginesAndFabricsComeBackAfterRestartingManagementServerAndDaemon(): Unit = runBlocking {
        val marker = dir.resolve("marker.txt")
        installPackages(marker)
        val daemonPort = freePort()
        val daemon = startDaemon(daemonPort)
        val server = startManagement()
        val s = stub(server)
        addMachine(s, daemon)
        createAndStart(s)
        val deployed = s.deployFabric(deployRequest("m1", "e1", "shop", start = true))
        assertEquals(FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING, deployed.info.state)
        assertTrue(deployed.desiredRunning)
        assertEquals("started", Files.readString(marker))
        // a second fabric that should stay stopped
        s.deployFabric(deployRequest("m1", "e1", "idle", start = false))
        assertEquals(setOf("shop", "idle"), s.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList.map { it.info.fabricId.value }.toSet())

        // everything goes down: management server, daemon and (with it) the engine process
        server.close()
        daemon.close()
        Files.delete(marker)

        val daemon2 = startDaemon(daemonPort)
        val server2 = startManagement(recover = true)
        val report = server2.recovery!!.await()
        assertEquals(emptyList<String>(), report.problems)
        val s2 = stub(server2)
        assertEquals(cringle.daemon.v1.EngineProcessState.ENGINE_PROCESS_STATE_RUNNING, s2.getEngine(engineRef("m1", "e1")).process.state)
        assertEquals("started", Files.readString(marker))
        val fabrics = s2.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList.associateBy { it.info.fabricId.value }
        assertEquals(FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING, fabrics.getValue("shop").info.state)
        assertEquals(FabricRuntimeState.FABRIC_RUNTIME_STATE_CREATED, fabrics.getValue("idle").info.state)
        assertFalse(fabrics.getValue("idle").desiredRunning)

        // recovering again changes nothing
        val again = s2.recover(RecoverRequest.getDefaultInstance())
        assertEquals(0, again.enginesStarted)
        assertEquals(0, again.fabricsRestored)

        // stopping a fabric is remembered
        s2.stopFabric(fabricRef("m1", "e1", "shop"))
        assertFalse(s2.getFabric(fabricRef("m1", "e1", "shop")).desiredRunning)
        s2.removeFabric(fabricRef("m1", "e1", "idle"))
        assertEquals(listOf("shop"), s2.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList.map { it.info.fabricId.value })
        assertNotNull(daemon2)
    }

    /**
     * M4 "Fertig, wenn": after the machine was restarted (Daemon with its router and ManagementServer new, engine
     * process gone) the project runs again and the registry of the router knows the placement again. The registry keeps
     * the engine and its fabrics across a restart, but without a heartbeat it counts the engine as unreachable, so a
     * reachable engine with the fabric in state RUNNING proves that the recovered engine reported it.
     */
    @Test
    fun afterARestartOfTheMachineTheProjectRunsAgainAndTheRegistryKnowsThePlacement(): Unit = runBlocking {
        val runningState = cringle.common.v1.FabricLifecycleState.FABRIC_LIFECYCLE_STATE_RUNNING.name
        val marker = dir.resolve("marker.txt")
        installPackages(marker)
        val daemonPort = freePort()
        val daemon = startDaemon(daemonPort, combined = true)
        val server = startManagement()
        val s = stub(server)
        addMachine(s, daemon)
        createAndStart(s)
        s.deployFabric(deployRequest("m1", "e1", "shop", start = true))
        assertEquals("started", Files.readString(marker))
        val registry = daemon.router!!.registry
        awaitTrue(what = "the registry knows the fabric of the engine before the restart") {
            registry.lookupFabric("shop")?.record?.fabrics?.any { it.fabricId == "shop" && it.state == runningState } == true
        }

        // the machine goes down and comes up again: new Daemon (new router, same home), new ManagementServer
        server.close()
        daemon.close()
        Files.delete(marker)
        val daemon2 = startDaemon(daemonPort, combined = true)
        val recovered = startManagement(recover = true)
        assertEquals(emptyList<String>(), recovered.recovery!!.await().problems)

        assertEquals("started", Files.readString(marker), "the project runs again")
        val registry2 = daemon2.router!!.registry
        awaitTrue(what = "the registry knows the placement of the recovered fabric") {
            val view = registry2.lookupFabric("shop")
            view != null && view.record.id == "e1" && view.reachability == cringle.router.Reachability.REACHABLE &&
                view.record.fabrics.any { it.fabricId == "shop" && it.state == runningState }
        }
    }

    @Test
    fun logsAreQueriedFromEnginesAndFilteredByFabricAndBlock(): Unit = runBlocking {
        val daemon = startDaemon()
        val s = stub(startManagement())
        addMachine(s, daemon)
        createAndStart(s, "e1")
        createAndStart(s, "e2")
        val t0 = Instant.parse("2026-01-01T10:00:00Z")
        fun log(engine: String, at: Long, fabric: String, block: String, level: LogLevel, text: String) =
            LoggingService(CringleHome.engineDir(home, engine)).append(LogEntry(t0.plusSeconds(at), fabric, block, level, text))
        log("e1", 1, "shop", "a", LogLevel.INFO, "one")
        log("e2", 2, "shop", "b", LogLevel.ERROR, "two")
        log("e1", 3, "other", "a", LogLevel.DEBUG, "three")

        val all = s.queryLogs(QueryLogsRequest.getDefaultInstance())
        assertEquals(listOf("one", "two", "three"), all.entriesList.map { it.entry.message })
        assertEquals(listOf("e1", "e2", "e1"), all.entriesList.map { it.engineId.value })
        assertEquals(emptyList<String>(), all.problemsList)

        val shop = s.queryLogs(QueryLogsRequest.newBuilder().setFabric("shop").build())
        assertEquals(listOf("one", "two"), shop.entriesList.map { it.entry.message })
        val errors = s.queryLogs(QueryLogsRequest.newBuilder().setMinLevel(cringle.engine.v1.LogLevel.LOG_LEVEL_ERROR).build())
        assertEquals(listOf("two"), errors.entriesList.map { it.entry.message })
        val one = s.queryLogs(QueryLogsRequest.newBuilder().setEngine(engineRef("m1", "e1")).setBlock("a").setLimit(1).build())
        assertEquals(listOf("three"), one.entriesList.map { it.entry.message })

        s.stopEngine(engineRef("m1", "e2"))
        assertEquals(listOf("one", "three"), s.queryLogs(QueryLogsRequest.getDefaultInstance()).entriesList.map { it.entry.message })
        assertEquals(Status.Code.FAILED_PRECONDITION, code { s.queryLogs(QueryLogsRequest.newBuilder().setEngine(engineRef("m1", "e2")).build()) })
    }

    @Test
    fun repositoryAndRouterCallsAreProxied(): Unit = runBlocking {
        val repository = tls.startRepository(PackageRepository(dir.resolve("repo")))
        closeables += AutoCloseable { repository.stop() }
        val router = tls.startRouter(dir.resolve("registry.json"))
        closeables += AutoCloseable { router.stop() }
        val s = stub(startManagement(repository = "127.0.0.1:${repository.port}", router = "127.0.0.1:${router.port}"))

        val file = dir.resolve("p.cringle")
        Files.newOutputStream(file).use { PackageWriter.writePlugin(PluginManifest("acme-core", "1.0.0"), emptyMap(), emptyMap(), emptyMap(), it) }
        val bytes = Files.readAllBytes(file)
        val published = s.publishPackage(
            flowOf(
                PublishRequest.newBuilder().setHeader(PublishHeader.getDefaultInstance()).build(),
                PublishRequest.newBuilder().setChunk(ByteString.copyFrom(bytes)).build(),
            ),
        )
        assertEquals("acme-core", published.metadata.name)
        assertEquals(1, s.listPackages(ListPackagesRequest.getDefaultInstance()).packagesCount)
        val downloaded = s.downloadPackage(DownloadRequest.newBuilder().setName("acme-core").setVersion("1.0.0").build()).toList()
        assertEquals("acme-core", downloaded.first().metadata.name)
        assertEquals(bytes.size, downloaded.drop(1).sumOf { it.chunk.size() })

        // a router with mTLS wants the fingerprint of the remote router confirmed (Architecture 5.1)
        val remote = tls.startRouter(dir.resolve("remote-registry.json"))
        closeables += AutoCloseable { remote.stop() }
        val remoteAddress = "127.0.0.1:${remote.port}"
        s.addRemoteRouter(AddRemoteRouterRequest.newBuilder().setAddress(remoteAddress).setExpectedFingerprint(remote.identity!!.publicKeyFingerprint).build())
        assertEquals(listOf(remoteAddress), s.listRemoteRouters(ListRemoteRoutersRequest.getDefaultInstance()).routersList.map { it.address })
    }

    @Test
    fun withoutRepositoryOrRouterTheCallsFailClearly(): Unit = runBlocking {
        val s = stub(startManagement())
        assertEquals(Status.Code.FAILED_PRECONDITION, code { s.listPackages(ListPackagesRequest.getDefaultInstance()) })
        assertEquals(Status.Code.FAILED_PRECONDITION, code { s.listRemoteRouters(ListRemoteRoutersRequest.getDefaultInstance()) })
        assertEquals(Status.Code.INVALID_ARGUMENT, code { s.addMachine(AddMachineRequest.newBuilder().setMachineId("Bad Id").setDaemonAddress("x:1").build()) })
        assertEquals(Status.Code.INVALID_ARGUMENT, code { s.addMachine(AddMachineRequest.newBuilder().setMachineId("ok").setDaemonAddress("nocolon").build()) })
    }

    @Test
    fun unreachableMachinesAreReportedAndDoNotBreakRecovery(): Unit = runBlocking {
        val server = startManagement()
        val s = stub(server)
        val machine = s.addMachine(AddMachineRequest.newBuilder().setMachineId("gone").setDaemonAddress("127.0.0.1:${freePort()}").build())
        assertFalse(machine.reachable)
        val report = s.recover(RecoverRequest.getDefaultInstance())
        assertEquals(1, report.problemsCount)
        assertTrue(report.getProblems(0).contains("gone"))
    }

    @Test
    fun rolesAreEnforced(): Unit = runBlocking {
        val users = UserManager(FileUserStore(dir.resolve("users.json")))
        val admin = users.bootstrap()!!
        val viewer = users.createToken(users.createUser("v", setOf(UserRole.VIEWER)).user.id, "t", null).secret
        val operator = users.createToken(users.createUser("o", setOf(UserRole.OPERATOR)).user.id, "t", null).secret
        val daemon = startDaemon()
        val server = startManagement(users = users)
        val add = AddMachineRequest.newBuilder().setMachineId("m1").setDaemonAddress("127.0.0.1:${daemon.port}").build()

        assertEquals(Status.Code.UNAUTHENTICATED, code { stub(server).listMachines(ListMachinesRequest.getDefaultInstance()) })
        assertEquals(Status.Code.PERMISSION_DENIED, code { stub(server, operator).addMachine(add) })
        stub(server, admin).addMachine(add)
        assertEquals(1, stub(server, viewer).listMachines(ListMachinesRequest.getDefaultInstance()).machinesCount)
        assertEquals(Status.Code.PERMISSION_DENIED, code { stub(server, viewer).createEngine(CreateEngineRequest.newBuilder().setMachineId("m1").build()) })
        assertTrue(stub(server, operator).createEngine(CreateEngineRequest.newBuilder().setMachineId("m1").build()).process.engineId.value.startsWith("e-"))
    }
}
