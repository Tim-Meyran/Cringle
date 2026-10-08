// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.common.v1.EngineId
import cringle.contract.BlockDefinition
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import cringle.daemon.Daemon
import cringle.management.test.ManagementTls
import cringle.management.v1.AddMachineRequest
import cringle.management.v1.Binding
import cringle.management.v1.CreateEngineRequest
import cringle.management.v1.DeployProjectRequest
import cringle.management.v1.EngineRef
import cringle.management.v1.ListFabricsRequest
import cringle.management.v1.ManagementServiceGrpcKt.ManagementServiceCoroutineStub
import cringle.management.v1.RecoverRequest
import cringle.management.v1.UndeployRequest
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.Endpoint
import cringle.packaging.FabricConfig
import cringle.packaging.ProvidedService
import cringle.packaging.TetherDef
import cringle.repository.PackageRepository
import cringle.testkit.TestJar
import cringle.testkit.TestPluginBuilder
import cringle.testkit.TestProjectBuilder
import io.grpc.Status
import io.grpc.StatusException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tethers between projects (#179): a service project, two consumer projects that are bound to it, and an engine of each,
 * all started by one ManagementServer. The consumer blocks send their name every 200 ms, the service block appends what
 * arrives to a file.
 */
@Tag("integration")
class ServiceBindingDeployTest {
    private lateinit var dir: Path
    private lateinit var s: ManagementServiceCoroutineStub
    private val closeables = ArrayList<AutoCloseable>()
    private val received get() = dir.resolve("orders.txt")
    private val backupFile get() = dir.resolve("backup.txt")

    private val schema = """{"namespace":"acme.svc","types":{"CallerConfig":{"record":{"message":"cringle.std/String"}},"SinkConfig":{"record":{"file":"cringle.std/String"}}}}"""

    private val sources = mapOf(
        "com.acme.SvcProvider" to """
            package com.acme;
            import cringle.contract.*;
            import java.util.List;
            import java.util.Set;
            public class SvcProvider implements BlockProvider {
                private static final SchemaRef STRING = new SchemaRef("cringle.std", "String");
                public List<BlockDefinition> getDefinitions() {
                    return List.of(
                        new BlockDefinition("caller", List.of(), List.of(new PortDefinition("out", PortDirection.OUT, Set.of(TetherType.MESSAGE), STRING, false)), List.of(), new SchemaRef("acme.svc", "CallerConfig")),
                        new BlockDefinition("sink", List.of(), List.of(new PortDefinition("in", PortDirection.IN, Set.of(TetherType.MESSAGE), STRING, false)), List.of(), new SchemaRef("acme.svc", "SinkConfig")));
                }
                public Block createBlock(String name, DriverSet drivers) { return name.equals("caller") ? new CallerBlock() : new SinkBlock(); }
            }
        """.trimIndent(),
        "com.acme.CallerBlock" to """
            package com.acme;
            import cringle.contract.*;
            import kotlin.Unit;
            import kotlin.coroutines.Continuation;
            import kotlin.coroutines.EmptyCoroutineContext;
            import kotlinx.coroutines.BuildersKt;
            public class CallerBlock implements Block {
                private String message;
                private Tether out;
                private volatile boolean running;
                public Object init(BlockContext c, Continuation<? super Unit> k) {
                    message = (String) c.getConfig().get("message");
                    out = c.getPorts().port("out");
                    return Unit.INSTANCE;
                }
                public Object start(Continuation<? super Unit> k) {
                    running = true;
                    Thread t = new Thread(() -> {
                        while (running) {
                            try {
                                BuildersKt.<Unit>runBlocking(EmptyCoroutineContext.INSTANCE, (scope, cont) -> out.send(message, (Continuation<? super Unit>) cont));
                            } catch (Throwable e) {
                                // not connected yet, or not allowed yet: try again
                            }
                            try { Thread.sleep(200); } catch (InterruptedException e) { return; }
                        }
                    });
                    t.setDaemon(true);
                    t.start();
                    return Unit.INSTANCE;
                }
                public Object stop(Continuation<? super Unit> k) { running = false; return Unit.INSTANCE; }
                public Object destroy(Continuation<? super Unit> k) { return Unit.INSTANCE; }
                public Object onTetherEvent(TetherEvent e, Continuation<? super Unit> k) { return Unit.INSTANCE; }
            }
        """.trimIndent(),
        "com.acme.SinkBlock" to """
            package com.acme;
            import cringle.contract.*;
            import kotlin.Unit;
            import kotlin.coroutines.Continuation;
            import java.nio.file.*;
            public class SinkBlock implements Block {
                private String file;
                public Object init(BlockContext c, Continuation<? super Unit> k) { file = (String) c.getConfig().get("file"); return Unit.INSTANCE; }
                public Object start(Continuation<? super Unit> k) { return Unit.INSTANCE; }
                public Object stop(Continuation<? super Unit> k) { return Unit.INSTANCE; }
                public Object destroy(Continuation<? super Unit> k) { return Unit.INSTANCE; }
                public Object onTetherEvent(TetherEvent e, Continuation<? super Unit> k) {
                    if (e instanceof TetherEvent.Message) {
                        try {
                            Files.writeString(Path.of(file), ((TetherEvent.Message) e).getValue() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        } catch (java.io.IOException ex) { throw new RuntimeException(ex); }
                    }
                    return Unit.INSTANCE;
                }
            }
        """.trimIndent(),
    )

    private fun consumerProject(name: String, message: String, role: String, work: Path, pkg: cringle.packaging.PluginPackage) = TestProjectBuilder(name, "1.0.0")
        .dependency("acme-svc", "^1.0.0")
        .blueprint(
            Blueprint(
                "app",
                listOf(BlueprintBlock("c1", "acme-svc/caller", config = JsonObject(mapOf("message" to JsonPrimitive(message))))),
                listOf(TetherDef(TetherType.MESSAGE, Endpoint("c1", "out"), null, service = "orders")),
            ),
        )
        .fabric(FabricConfig("app", 1, listOf(role), emptyMap()))
        .build(work, listOf(pkg))

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("cringle-service-test")
        val home = Files.createDirectories(dir.resolve("home"))
        val repository = PackageRepository(dir.resolve("repo"))
        val work = Files.createDirectories(dir.resolve("build"))
        val plugin = TestPluginBuilder("acme-svc", "1.0.0")
            .provider("com.acme.SvcProvider")
            .block(BlockDefinition("caller", emptyList(), listOf(PortDefinition("out", PortDirection.OUT, setOf(TetherType.MESSAGE), SchemaRef("cringle.std", "String"))), emptyList(), SchemaRef("acme.svc", "CallerConfig")))
            .block(BlockDefinition("sink", emptyList(), listOf(PortDefinition("in", PortDirection.IN, setOf(TetherType.MESSAGE), SchemaRef("cringle.std", "String"))), emptyList(), SchemaRef("acme.svc", "SinkConfig")))
            .schema("svc.json", schema)
            .lib("svc.jar", TestJar.fromJavaSources(sources))
            .build(work)
        fun serviceProject(name: String, file: Path, role: String) = TestProjectBuilder(name, "1.0.0")
            .dependency("acme-svc", "^1.0.0")
            .blueprint(
                Blueprint(
                    "service",
                    listOf(BlueprintBlock("s1", "acme-svc/sink", config = JsonObject(mapOf("file" to JsonPrimitive(file.toString()))))),
                    emptyList(),
                    listOf(ProvidedService("orders", "s1", "in")),
                ),
            )
            .fabric(FabricConfig("service", 1, listOf(role), emptyMap()))
            .build(work, listOf(plugin.pkg))
        val service = serviceProject("orders-service", received, "svc")
        val backup = serviceProject("orders-backup", backupFile, "backup")
        repository.publish(plugin.file)
        repository.publish(service.file)
        repository.publish(backup.file)
        repository.publish(consumerProject("shop", "from-shop", "a", work, plugin.pkg).file)
        repository.publish(consumerProject("billing", "from-billing", "b", work, plugin.pkg).file)
        repository.setTrust("acme-svc", cringle.repository.PluginTrust.TRUSTED)

        val tls = ManagementTls(dir.resolve("tls"))
        val daemon = Daemon(home, combined = true).start()
        closeables += daemon
        tls.trust(daemon)
        val repositoryServer = tls.startRepository(repository)
        closeables += AutoCloseable { repositoryServer.stop() }
        val core = tls.core(ManagementStore(dir.resolve("state.json")), "127.0.0.1:${repositoryServer.port}")
        val server = ManagementServer(core, recoverOnStart = false).start()
        closeables += server
        val channel = ManagementTls.channelTo(server)
        closeables += AutoCloseable { channel.shutdownNow() }
        s = ManagementServiceCoroutineStub(channel)
        runBlocking {
            s.addMachine(AddMachineRequest.newBuilder().setMachineId("m1").setDaemonAddress("127.0.0.1:${daemon.port}").build())
            engine("e-svc", "svc")
            engine("e-svc2", "svc")
            engine("e-a", "a")
            engine("e-bak", "backup")
            engine("e-b", "b")
        }
    }

    @AfterEach
    fun tearDown() {
        closeables.reversed().forEach { runCatching { it.close() } }
        repeat(10) {
            if (runCatching { dir.toFile().deleteRecursively() }.getOrDefault(false) || !Files.exists(dir)) return
            Thread.sleep(200)
        }
    }

    private suspend fun engine(id: String, role: String) {
        s.createEngine(CreateEngineRequest.newBuilder().setMachineId("m1").setEngineId(id).addRoles(role).build())
        s.startEngine(ref(id))
    }

    private fun ref(id: String) = EngineRef.newBuilder().setMachineId("m1").setEngineId(EngineId.newBuilder().setValue(id)).build()

    private fun bind(project: String, vararg targets: String): Binding = runBlocking {
        s.bind(Binding.newBuilder().setConsumerProject(project).setService("orders").addAllTargets(targets.toList().ifEmpty { listOf("orders-service-service-1") }).build())
    }

    private fun deploy(project: String) = runBlocking { s.deploy(DeployProjectRequest.newBuilder().setProject(project).build()) }

    private fun lines(file: Path = received): Set<String> = if (Files.exists(file)) Files.readAllLines(file).toSet() else emptySet()

    /** Waits (at most 60 s) until the service block that writes to [file] has received [expected] since the last [clear]. */
    private fun awaitReceived(expected: Set<String>, file: Path = received) {
        val deadline = System.nanoTime() + 60_000_000_000L
        while (!lines(file).containsAll(expected)) {
            check(System.nanoTime() < deadline) { "the service did not receive $expected, only ${lines(file)}\n" + diagnostics() }
            Thread.sleep(100)
        }
    }

    private fun diagnostics(): String = runBlocking {
        val state = s.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList.joinToString("\n") { "${it.info.fabricId.value} on ${it.engineId.value}: ${it.info.state} ${it.info.failure}" }
        val logs = Files.walk(dir.resolve("home")).use { st -> st.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".log") }.toList() }
            .joinToString("\n") { "== $it\n" + Files.readAllLines(it).takeLast(15).joinToString("\n") }
        state + "\n" + logs
    }

    private fun clear() {
        Files.deleteIfExists(received)
    }

    private fun fabrics(): Map<String, String> = runBlocking {
        s.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList.associate { it.info.fabricId.value to it.engineId.value }
    }

    @Test
    fun consumersOfTwoProjectsReachTheServiceAndOthersDoNot() {
        assertEquals(mapOf("orders-service-service-1" to "e-svc"), deploy("orders-service").fabricsList.associate { it.info.fabricId.value to it.engineId.value })
        // a consumer without a binding is refused before anything is touched
        val refused = assertThrows<StatusException> { deploy("shop") }
        assertEquals(Status.Code.FAILED_PRECONDITION, refused.status.code)
        assertTrue(refused.status.description!!.contains("uses the service 'orders', but it is not bound"), refused.status.description)
        assertEquals(setOf("orders-service-service-1"), fabrics().keys)

        bind("shop")
        bind("billing")
        deploy("shop")
        deploy("billing")
        awaitReceived(setOf("from-shop", "from-billing"))
    }

    @Test
    fun undeployingAConsumerRemovesItFromTheAllowList() {
        deploy("orders-service")
        bind("shop")
        bind("billing")
        deploy("shop")
        deploy("billing")
        awaitReceived(setOf("from-shop", "from-billing"))
        runBlocking { s.undeploy(UndeployRequest.newBuilder().setProject("shop").build()) }
        clear()
        awaitReceived(setOf("from-billing"))
        Thread.sleep(1500)
        assertTrue("from-shop" !in lines(), lines().toString())
    }

    @Test
    fun theConsumersFollowTheServiceToAnotherEngine() {
        deploy("orders-service")
        bind("shop")
        bind("billing")
        deploy("shop")
        deploy("billing")
        awaitReceived(setOf("from-shop", "from-billing"))
        // the engine of the service stops; the next deploy puts it on the other one, and the consumers are deployed again
        runBlocking { s.stopEngine(ref("e-svc")) }
        assertEquals("e-svc2", deploy("orders-service").fabricsList.single().engineId.value)
        clear()
        awaitReceived(setOf("from-shop", "from-billing"))
    }

    @Test
    fun recoverRestoresTheBoundConsumersAndTheAllowList() {
        deploy("orders-service")
        bind("shop")
        deploy("shop")
        bind("billing")
        deploy("billing")
        awaitReceived(setOf("from-shop", "from-billing"))
        runBlocking {
            s.stopEngine(ref("e-svc"))
            s.stopEngine(ref("e-a"))
            s.startEngine(ref("e-svc"))
            s.startEngine(ref("e-a"))
            s.recover(RecoverRequest.getDefaultInstance())
        }
        clear()
        awaitReceived(setOf("from-shop", "from-billing"))
    }

    @Test
    fun theConsumerFailsOverToTheSecondInstanceWhenTheFirstIsRemoved() {
        deploy("orders-service")
        deploy("orders-backup")
        bind("shop", "orders-service-service-1", "orders-backup-service-1")
        deploy("shop")
        awaitReceived(setOf("from-shop"))
        assertTrue(lines(backupFile).isEmpty(), "the preferred instance gets the messages")
        // the first instance is removed; the consumer is not touched
        runBlocking { s.removeFabric(cringle.management.v1.FabricRef.newBuilder().setEngine(ref("e-svc")).setFabricId(cringle.common.v1.FabricId.newBuilder().setValue("orders-service-service-1")).build()) }
        awaitReceived(setOf("from-shop"), backupFile)
    }

    @Test
    fun aNewBindingReachesTheRunningConsumerWithoutARedeploy() {
        deploy("orders-service")
        deploy("orders-backup")
        bind("shop")
        deploy("shop")
        awaitReceived(setOf("from-shop"))
        assertTrue(lines(backupFile).isEmpty())
        val before = fabrics()
        // the binding now names the other instance; nothing is deployed again
        val bound = bind("shop", "orders-backup-service-1")
        assertEquals(listOf("orders-backup-service-1"), bound.targetsList)
        awaitReceived(setOf("from-shop"), backupFile)
        assertEquals(before, fabrics())
    }
}
