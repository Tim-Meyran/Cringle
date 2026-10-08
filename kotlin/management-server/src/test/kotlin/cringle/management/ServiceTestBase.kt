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
 * What the tests of tethers between projects share (#179, #173, #174): a repository with a plugin (blocks `caller` and
 * `sink`), a service project and a backup service project that provide `orders`, and two consumer projects, all started by
 * one ManagementServer with a daemon (and its router) on machine `m1`. The consumer blocks send their name every 200 ms,
 * the service blocks append what arrives to a file.
 */
abstract class ServiceTestBase {
    protected lateinit var dir: Path
    protected lateinit var s: ManagementServiceCoroutineStub
    protected lateinit var tls: ManagementTls
    protected lateinit var core: ManagementCore
    protected lateinit var daemon: Daemon
    protected val closeables = ArrayList<AutoCloseable>()
    protected val received get() = dir.resolve("orders.txt")
    protected val backupFile get() = dir.resolve("backup.txt")

    protected val schema = """{"namespace":"acme.svc","types":{"CallerConfig":{"record":{"message":"cringle.std/String"}},"SinkConfig":{"record":{"file":"cringle.std/String"}}}}"""

    protected val sources = mapOf(
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

    protected fun consumerProject(name: String, message: String, role: String, work: Path, pkg: cringle.packaging.PluginPackage) = TestProjectBuilder(name, "1.0.0")
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
        repository.publish(consumerProject("remote-shop", "from-remote-shop", "m2", work, plugin.pkg).file)
        // two local tethers inside one fabric; the first has a `record`, the second has not (#194)
        fun caller(id: String) = BlueprintBlock(id, "acme-svc/caller", config = JsonObject(mapOf("message" to JsonPrimitive("ping-$id"))))
        fun sink(id: String, file: String) = BlueprintBlock(id, "acme-svc/sink", config = JsonObject(mapOf("file" to JsonPrimitive(dir.resolve(file).toString()))))
        repository.publish(
            TestProjectBuilder("recorded-app", "1.0.0")
                .dependency("acme-svc", "^1.0.0")
                .blueprint(
                    Blueprint(
                        "app",
                        listOf(caller("c"), sink("s", "recorded1.txt"), caller("c2"), sink("s2", "recorded2.txt")),
                        listOf(
                            TetherDef(TetherType.MESSAGE, Endpoint("c", "out"), Endpoint("s", "in"), cringle.packaging.DeliveryPolicy.DROP, record = cringle.packaging.RecordConfig(java.time.Duration.ofDays(3), 1_000_000)),
                            TetherDef(TetherType.MESSAGE, Endpoint("c2", "out"), Endpoint("s2", "in")),
                        ),
                    ),
                )
                .fabric(FabricConfig("app", 1, listOf("a"), emptyMap()))
                .build(work, listOf(plugin.pkg)).file,
        )
        // a plugin whose block holds an exclusive resource, and a project that uses it (#226)
        val excl = TestPluginBuilder("acme-excl", "1.0.0")
            .provider("com.acme.SvcProvider")
            .block(
                BlockDefinition(
                    "caller", emptyList(), listOf(PortDefinition("out", PortDirection.OUT, setOf(TetherType.MESSAGE), SchemaRef("cringle.std", "String"))), emptyList(),
                    SchemaRef("acme.svc", "CallerConfig"), listOf(cringle.contract.ExclusiveResource(cringle.contract.ExclusiveKind.SERIAL, "COM3")),
                ),
            )
            .schema("svc.json", schema)
            .lib("svc.jar", TestJar.fromJavaSources(sources))
            .build(work)
        repository.publish(excl.file)
        repository.setTrust("acme-excl", cringle.repository.PluginTrust.TRUSTED)
        repository.publish(
            TestProjectBuilder("excl-app", "1.0.0")
                .dependency("acme-excl", "^1.0.0")
                .blueprint(Blueprint("app", listOf(BlueprintBlock("c1", "acme-excl/caller", config = JsonObject(mapOf("message" to JsonPrimitive("from-excl"))))), emptyList()))
                .fabric(FabricConfig("app", 1, listOf("a"), emptyMap()))
                .build(work, listOf(excl.pkg)).file,
        )
        repository.setTrust("acme-svc", cringle.repository.PluginTrust.TRUSTED)

        tls = ManagementTls(dir.resolve("tls"))
        daemon = Daemon(home, combined = true).start()
        closeables += daemon
        tls.trust(daemon)
        val repositoryServer = tls.startRepository(repository)
        closeables += AutoCloseable { repositoryServer.stop() }
        core = tls.core(ManagementStore(dir.resolve("state.json")), "127.0.0.1:${repositoryServer.port}")
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

    protected suspend fun engine(id: String, role: String, machine: String = "m1") {
        s.createEngine(CreateEngineRequest.newBuilder().setMachineId(machine).setEngineId(id).addRoles(role).build())
        s.startEngine(ref(id, machine))
    }

    protected fun ref(id: String, machine: String = "m1") = EngineRef.newBuilder().setMachineId(machine).setEngineId(EngineId.newBuilder().setValue(id)).build()

    protected fun bind(project: String, vararg targets: String): Binding = runBlocking {
        s.bind(Binding.newBuilder().setConsumerProject(project).setService("orders").addAllTargets(targets.toList().ifEmpty { listOf("orders-service-service-1") }).build())
    }

    protected fun deploy(project: String) = runBlocking { s.deploy(DeployProjectRequest.newBuilder().setProject(project).build()) }

    protected fun lines(file: Path = received): Set<String> = if (Files.exists(file)) Files.readAllLines(file).toSet() else emptySet()

    /** Waits (at most 60 s) until the service block that writes to [file] has received [expected] since the last [clear]. */
    protected fun awaitReceived(expected: Set<String>, file: Path = received) {
        val deadline = System.nanoTime() + 60_000_000_000L
        while (!lines(file).containsAll(expected)) {
            check(System.nanoTime() < deadline) { "the service did not receive $expected, only ${lines(file)}\n" + diagnostics() }
            Thread.sleep(100)
        }
    }

    protected fun diagnostics(): String = runBlocking {
        val state = s.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList.joinToString("\n") { "${it.info.fabricId.value} on ${it.engineId.value}: ${it.info.state} ${it.info.failure}" }
        val logs = Files.walk(dir.resolve("home")).use { st -> st.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".log") }.toList() }
            .joinToString("\n") { "== $it\n" + Files.readAllLines(it).takeLast(15).joinToString("\n") }
        state + "\n" + logs
    }

    protected fun clear() {
        Files.deleteIfExists(received)
    }

    protected fun fabrics(): Map<String, String> = runBlocking {
        s.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList.associate { it.info.fabricId.value to it.engineId.value }
    }
}
