// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.common.v1.EngineId
import cringle.contract.BlockDefinition
import cringle.contract.SchemaRef
import cringle.daemon.Daemon
import cringle.management.v1.AddMachineRequest
import cringle.management.v1.CleanupCacheRequest
import cringle.management.v1.CreateEngineRequest
import cringle.management.v1.DeployProjectRequest
import cringle.management.v1.EngineRef
import cringle.management.v1.ListFabricsRequest
import cringle.management.v1.ManagementServiceGrpcKt.ManagementServiceCoroutineStub
import cringle.management.v1.UndeployRequest
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.FabricConfig
import cringle.repository.PackageRepository
import cringle.repository.RepositoryServer
import cringle.testkit.TestJar
import cringle.testkit.TestPluginBuilder
import cringle.testkit.TestProjectBuilder
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Deploys a project from a real Repository onto real Engine processes. */
class DeploymentTest {
    private lateinit var dir: Path
    private lateinit var home: Path
    private lateinit var repository: PackageRepository
    private lateinit var s: ManagementServiceCoroutineStub
    private val closeables = ArrayList<AutoCloseable>()

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

    private val markerOne get() = dir.resolve("one.txt")
    private val markerTwo get() = dir.resolve("two.txt")

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("cringle-deploy-test")
        home = Files.createDirectories(dir.resolve("home"))
        repository = PackageRepository(dir.resolve("repo"))
        val work = Files.createDirectories(dir.resolve("build"))
        val plugin = TestPluginBuilder("acme-demo", "1.0.0")
            .provider("com.acme.MarkerProvider")
            .block(BlockDefinition("marker", emptyList(), emptyList(), emptyList(), SchemaRef("acme.demo", "MarkerConfig")))
            .schema("marker.json", markerSchema)
            .lib("marker.jar", TestJar.fromJavaSources(providerSource))
            .build(work)
        fun block(id: String, marker: Path) = BlueprintBlock(id, "acme-demo/marker", config = JsonObject(mapOf("marker" to JsonPrimitive(marker.toString()))))
        val project = TestProjectBuilder("demo", "0.1.0")
            .dependency("acme-demo", "^1.0.0")
            .blueprint(Blueprint("one", listOf(block("b1", markerOne)), emptyList()))
            .blueprint(Blueprint("two", listOf(block("b2", markerTwo)), emptyList()))
            .fabric(FabricConfig("one", 1, listOf("db"), emptyMap()))
            .fabric(FabricConfig("two", 1, listOf("worker"), mapOf("zone" to "a")))
            .build(work, listOf(plugin.pkg))
        val gpu = TestProjectBuilder("gpu-app", "1.0.0")
            .dependency("acme-demo", "^1.0.0")
            .blueprint(Blueprint("main", listOf(block("g1", dir.resolve("gpu.txt"))), emptyList()))
            .fabric(FabricConfig("main", 1, listOf("gpu"), emptyMap()))
            .build(work, listOf(plugin.pkg))
        repository.publish(plugin.file)
        repository.publish(project.file)
        repository.publish(gpu.file)
        // the trust status comes from the repository and is passed to the engines with the deploy command
        repository.setTrust("acme-demo", cringle.repository.PluginTrust.TRUSTED)

        val repositoryServer = RepositoryServer(repository).start()
        closeables += AutoCloseable { repositoryServer.stop() }
        val daemon = Daemon(home).start()
        closeables += daemon
        val core = ManagementCore(ManagementStore(dir.resolve("state.json")), "127.0.0.1:${repositoryServer.port}")
        val server = ManagementServer(core, recoverOnStart = false).start()
        closeables += server
        val channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
        closeables += AutoCloseable { channel.shutdownNow() }
        s = ManagementServiceCoroutineStub(channel)
        runBlocking {
            s.addMachine(AddMachineRequest.newBuilder().setMachineId("m1").setDaemonAddress("127.0.0.1:${daemon.port}").build())
            engine("e1", listOf("worker"), mapOf("zone" to "a"))
            engine("e2", listOf("worker"), mapOf("zone" to "b"))
            engine("e3", listOf("db"), emptyMap())
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

    private suspend fun engine(id: String, roles: List<String>, labels: Map<String, String>) {
        s.createEngine(CreateEngineRequest.newBuilder().setMachineId("m1").setEngineId(id).addAllRoles(roles).putAllLabels(labels).build())
        s.startEngine(EngineRef.newBuilder().setMachineId("m1").setEngineId(EngineId.newBuilder().setValue(id)).build())
    }

    private fun code(body: suspend () -> Unit): Status.Code = assertThrows<StatusException> { runBlocking { body() } }.status.code

    private fun versionDirs(kind: String): List<String> {
        val base = home.resolve(kind)
        if (!Files.isDirectory(base)) return emptyList()
        return Files.walk(base, 2).use { st -> st.filter { it.nameCount == base.nameCount + 2 }.map { base.relativize(it).toString().replace('\\', '/') }.toList() }.sorted()
    }

    @Test
    fun deployingAProjectPlacesFabricsOnTheRightEnginesAndTheyRun(): Unit = runBlocking {
        val result = s.deploy(DeployProjectRequest.newBuilder().setProject("demo").build())
        assertEquals("0.1.0", result.version)
        assertEquals(mapOf("demo-one-1" to "e3", "demo-two-1" to "e1"), result.fabricsList.associate { it.info.fabricId.value to it.engineId.value })
        assertTrue(result.lock.contains("acme-demo"))
        assertTrue(Files.exists(dir.resolve("locks/demo-0.1.0.lock.json")))
        assertEquals("started", Files.readString(markerOne))
        assertEquals("started", Files.readString(markerTwo))
        assertTrue(result.fabricsList.all { it.desiredRunning })

        // the two engines that got fabrics share one unpacked version of the plugin and the project
        assertEquals(listOf("acme-demo/1.0.0"), versionDirs("plugins"))
        assertEquals(listOf("demo/0.1.0"), versionDirs("projects"))
        assertTrue(Files.exists(home.resolve("plugins/acme-demo/1.0.0/.cringle-installed")))

        // deploying again replaces the fabrics instead of duplicating them
        val again = s.deploy(DeployProjectRequest.newBuilder().setProject("demo").setVersionRange("^0.1.0").build())
        assertEquals(2, again.fabricsCount)
        assertEquals(2, s.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsCount)

        // versions in use are kept by the cleanup
        assertEquals(0, s.cleanupCache(CleanupCacheRequest.getDefaultInstance()).removedCount)
        assertEquals(listOf("acme-demo/1.0.0"), versionDirs("plugins"))

        // after undeploying they are unused and go away
        assertEquals(2, s.undeploy(UndeployRequest.newBuilder().setProject("demo").build()).removedCount)
        assertEquals(0, s.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsCount)
        val cleaned = s.cleanupCache(CleanupCacheRequest.getDefaultInstance())
        assertEquals(emptyList<String>(), cleaned.problemsList)
        assertEquals(listOf("m1: plugins/acme-demo@1.0.0", "m1: projects/demo@0.1.0"), cleaned.removedList.sorted())
        assertEquals(emptyList<String>(), versionDirs("plugins"))
    }

    @Test
    fun deploymentFailsClearlyWhenNoEngineMatchesOrThePackageIsUnknown(): Unit = runBlocking {
        assertEquals(Status.Code.FAILED_PRECONDITION, code { s.deploy(DeployProjectRequest.newBuilder().setProject("gpu-app").build()) })
        assertEquals(Status.Code.NOT_FOUND, code { s.deploy(DeployProjectRequest.newBuilder().setProject("nope").build()) })
        assertEquals(Status.Code.INVALID_ARGUMENT, code { s.deploy(DeployProjectRequest.newBuilder().setProject("acme-demo").build()) })
        assertEquals(0, s.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsCount)
        // an engine gets the missing role and the deployment works
        s.setEngineTags(
            cringle.management.v1.SetEngineTagsRequest.newBuilder()
                .setEngine(EngineRef.newBuilder().setMachineId("m1").setEngineId(EngineId.newBuilder().setValue("e2"))).addRoles("gpu").build(),
        )
        val ok = s.deploy(DeployProjectRequest.newBuilder().setProject("gpu-app").setStart(false).build())
        assertEquals("e2", ok.getFabrics(0).engineId.value)
        assertFalse(ok.getFabrics(0).desiredRunning)
    }

    @Test
    fun aCorruptDownloadIsRejectedAndNothingIsLeftInTheCache(): Unit = runBlocking {
        Files.write(repository.file("acme-demo", "1.0.0"), byteArrayOf(1, 2, 3))
        assertEquals(Status.Code.DATA_LOSS, code { s.deploy(DeployProjectRequest.newBuilder().setProject("demo").build()) })
        assertEquals(emptyList<String>(), versionDirs("plugins"))
        assertEquals(0, s.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsCount)
        val leftovers = if (Files.isDirectory(home.resolve("cache/downloads"))) Files.list(home.resolve("cache/downloads")).use { it.toList() } else emptyList()
        assertEquals(emptyList<Path>(), leftovers)
    }
}
