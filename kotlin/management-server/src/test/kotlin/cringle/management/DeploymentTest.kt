// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.management.test.ManagementTls
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Deploys a project from a real Repository onto real Engine processes. */
@Tag("integration")
class DeploymentTest {
    private lateinit var dir: Path
    private lateinit var home: Path
    private lateinit var repository: PackageRepository
    private lateinit var core: ManagementCore
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

    private fun buildPlugin(version: String, work: Path) = TestPluginBuilder("acme-demo", version)
        .provider("com.acme.MarkerProvider")
        .block(BlockDefinition("marker", emptyList(), emptyList(), emptyList(), SchemaRef("acme.demo", "MarkerConfig")))
        .schema("marker.json", markerSchema)
        .lib("marker.jar", TestJar.fromJavaSources(providerSource))
        .build(work)

    private val lockFile get() = dir.resolve("locks/demo-0.1.0.lock.json")

    private suspend fun fabricIds(): Map<String, String> =
        s.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList.associate { it.info.fabricId.value to it.engineId.value }

    private val markerOne get() = dir.resolve("one.txt")
    private val markerTwo get() = dir.resolve("two.txt")

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("cringle-deploy-test")
        home = Files.createDirectories(dir.resolve("home"))
        repository = PackageRepository(dir.resolve("repo"))
        val work = Files.createDirectories(dir.resolve("build"))
        val plugin = buildPlugin("1.0.0", work)
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

        val tls = ManagementTls(dir.resolve("tls"))
        val daemon = Daemon(home).start()
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

    private val previous = mapOf("demo-one-1" to "e3", "demo-two-1" to "e1")

    @Test
    fun theLockFileOfAVersionIsUsedAgainAndRelockResolvesAgain(): Unit = runBlocking {
        val first = s.deploy(DeployProjectRequest.newBuilder().setProject("demo").build())
        assertTrue(first.lock.contains("1.0.0") && !first.lock.contains("1.1.0"), first.lock)

        // a newer compatible release appears; the lock keeps the old one
        buildPlugin("1.1.0", Files.createDirectories(dir.resolve("build-1-1"))).let { repository.publish(it.file) }
        val second = s.deploy(DeployProjectRequest.newBuilder().setProject("demo").build())
        assertEquals(first.lock, second.lock)
        assertEquals(listOf("acme-demo/1.0.0"), versionDirs("plugins"))
        assertEquals(first.lock, Files.readString(lockFile))

        // --relock resolves again and writes the lock again
        val relocked = s.deploy(DeployProjectRequest.newBuilder().setProject("demo").setRelock(true).build())
        assertTrue(relocked.lock.contains("1.1.0"), relocked.lock)
        assertEquals(relocked.lock, Files.readString(lockFile))
        assertEquals(listOf("acme-demo/1.0.0", "acme-demo/1.1.0"), versionDirs("plugins"))
        assertEquals(relocked.lock, s.deploy(DeployProjectRequest.newBuilder().setProject("demo").build()).lock)
        assertEquals(previous, fabricIds())
    }

    @Test
    fun aLockWhoseHashDiffersFromTheRepositoryIsAnErrorAndChangesNothing(): Unit = runBlocking {
        s.deploy(DeployProjectRequest.newBuilder().setProject("demo").build())
        val lock = cringle.packaging.LockFile.parse(Files.readString(lockFile))
        val bad = lock.copy(packages = lock.packages + ("acme-demo" to lock.packages.getValue("acme-demo").copy(hash = "a".repeat(64))))
        Files.writeString(lockFile, bad.encode())
        val e = assertThrows<StatusException> { runBlocking { s.deploy(DeployProjectRequest.newBuilder().setProject("demo").build()) } }
        assertEquals(Status.Code.FAILED_PRECONDITION, e.status.code)
        assertTrue(e.status.description!!.contains("--relock"), e.status.description)
        assertEquals(previous, fabricIds())
        // relocking repairs it
        s.deploy(DeployProjectRequest.newBuilder().setProject("demo").setRelock(true).build())
        assertEquals(lock.encode(), Files.readString(lockFile))
    }

    @Test
    fun whenNoEngineMatchesThePreviousFabricsKeepRunning(): Unit = runBlocking {
        s.deploy(DeployProjectRequest.newBuilder().setProject("demo").build())
        // the only engine with the role 'db' loses it
        s.setEngineTags(
            cringle.management.v1.SetEngineTagsRequest.newBuilder()
                .setEngine(EngineRef.newBuilder().setMachineId("m1").setEngineId(EngineId.newBuilder().setValue("e3"))).build(),
        )
        val e = assertThrows<StatusException> { runBlocking { s.deploy(DeployProjectRequest.newBuilder().setProject("demo").build()) } }
        assertEquals(Status.Code.FAILED_PRECONDITION, e.status.code)
        assertTrue(e.status.description!!.contains("needs 1 running engine(s) with roles [db]"), e.status.description)
        assertEquals(previous, fabricIds())
        // a fabric that had been stopped would have written 'stopped'
        assertEquals("started", Files.readString(markerOne))
        assertEquals("started", Files.readString(markerTwo))
    }

    @Test
    fun whenAFabricCannotBeStartedThePreviousFabricsAreBack(): Unit = runBlocking {
        s.deploy(DeployProjectRequest.newBuilder().setProject("demo").build())
        core.beforeFabricDeploy = { if (it == "demo-two-1") throw ManagementException(Status.Code.UNAVAILABLE, "injected failure") }
        try {
            val e = assertThrows<StatusException> { runBlocking { s.deploy(DeployProjectRequest.newBuilder().setProject("demo").build()) } }
            assertEquals(Status.Code.UNAVAILABLE, e.status.code)
            assertTrue(e.status.description!!.contains("injected failure"), e.status.description)
        } finally {
            core.beforeFabricDeploy = {}
        }
        // exactly the fabrics that ran before are there and run again
        assertEquals(previous, fabricIds())
        val fabrics = s.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList
        assertTrue(fabrics.all { it.desiredRunning })
        assertEquals("started", Files.readString(markerOne))
        assertEquals("started", Files.readString(markerTwo))
        // and a later deploy works
        assertEquals(2, s.deploy(DeployProjectRequest.newBuilder().setProject("demo").build()).fabricsCount)
    }

    @Test
    fun twoParallelDeploysOfOneProjectEndWithOneConsistentResult(): Unit = runBlocking {
        val results = (1..2).map { async { s.deploy(DeployProjectRequest.newBuilder().setProject("demo").build()) } }.awaitAll()
        assertTrue(results.all { it.fabricsCount == 2 })
        assertEquals(previous, fabricIds())
        assertEquals(2, s.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsCount)
        val lock = cringle.packaging.LockFile.parse(Files.readString(lockFile))
        assertEquals(emptyList<Any>(), lock.problems())
        assertEquals(listOf("demo-0.1.0.lock.json"), Files.list(dir.resolve("locks")).use { st -> st.map { it.fileName.toString() }.toList() })
    }

    /** Publishes the project [name] 1.0.0 with one blueprint and one fabric config that needs [instances] engines with the given roles. */
    private fun publishPool(name: String, instances: Int, roles: List<String> = listOf("worker")) {
        val work = Files.createDirectories(dir.resolve("build-$name"))
        val block = BlueprintBlock("p1", "acme-demo/marker", config = JsonObject(mapOf("marker" to JsonPrimitive(dir.resolve("$name.txt").toString()))))
        val project = TestProjectBuilder(name, "1.0.0")
            .dependency("acme-demo", "^1.0.0")
            .blueprint(Blueprint("main", listOf(block), emptyList()))
            .fabric(FabricConfig("main", instances, roles, emptyMap()))
            .build(work, listOf(buildPlugin("1.0.0", work).pkg))
        repository.publish(project.file)
    }

    /**
     * Placement (#17, Architecture 8.4): a fabric goes to the running engine with the fewest fabrics among those that
     * have all roles and labels, and a tie goes to the smallest engine id. The engines `e1` and `e2` both have the role
     * `worker`.
     */
    @Test
    fun theLeastLoadedEngineGetsTheFabricAndATieGoesToTheSmallestId(): Unit = runBlocking {
        publishPool("tie", 1)
        publishPool("pool", 2)
        publishPool("single", 1)
        fun placed(r: cringle.management.v1.DeployProjectResponse) = r.fabricsList.associate { it.info.fabricId.value to it.engineId.value }

        // both workers are empty: the tie goes to e1
        assertEquals(mapOf("tie-main-1" to "e1"), placed(s.deploy(DeployProjectRequest.newBuilder().setProject("tie").build())))
        // e1 has one fabric, e2 none: the first copy goes to e2, then both have one and the tie goes to e1
        assertEquals(mapOf("pool-main-1" to "e2", "pool-main-2" to "e1"), placed(s.deploy(DeployProjectRequest.newBuilder().setProject("pool").build())))
        // e1 has two fabrics, e2 one: the next one goes to e2
        assertEquals(mapOf("single-main-1" to "e2"), placed(s.deploy(DeployProjectRequest.newBuilder().setProject("single").build())))
        // the engine with the role `db` was never a candidate
        assertTrue(fabricIds().values.none { it == "e3" })

        // deploying a project again replaces its fabrics, it does not add to them
        s.deploy(DeployProjectRequest.newBuilder().setProject("pool").build())
        assertEquals(2, fabricIds().keys.count { it.startsWith("pool-") })
    }

    /**
     * #17 "zwei Engines derselben Maschine teilen eine Version": the fabrics of one project run on two engines of one
     * machine, and the plugin and the project exist once in the cache of the machine, with the hash of the package,
     * while both engines have recorded that their fabric uses them.
     */
    @Test
    fun twoEnginesOfOneMachineShareOneInstalledVersionOfAPackage(): Unit = runBlocking {
        val result = s.deploy(DeployProjectRequest.newBuilder().setProject("demo").build())
        assertEquals(setOf("e1", "e3"), result.fabricsList.map { it.engineId.value }.toSet())

        assertEquals(listOf("acme-demo/1.0.0"), versionDirs("plugins"))
        assertEquals(listOf("demo/0.1.0"), versionDirs("projects"))
        assertEquals(repository.get("acme-demo", "1.0.0").sha256, Files.readString(home.resolve("plugins/acme-demo/1.0.0/.cringle-installed")).trim())
        assertEquals(repository.get("demo", "0.1.0").sha256, Files.readString(home.resolve("projects/demo/0.1.0/.cringle-installed")).trim())
        for ((engine, fabric) in listOf("e1" to "demo-two-1", "e3" to "demo-one-1")) {
            val usage = Files.readString(home.resolve("cache/usage/$engine/$fabric"))
            assertTrue("PLUGIN acme-demo 1.0.0" in usage && "PROJECT demo 0.1.0" in usage, "engine $engine does not record its use of the shared versions:\n$usage")
        }
        val leftovers = Files.walk(home).use { st -> st.filter { it.fileName.toString().let { n -> n.endsWith(".part") || ".installing-" in n || ".removing-" in n } }.toList() }
        assertEquals(emptyList<Path>(), leftovers)
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
