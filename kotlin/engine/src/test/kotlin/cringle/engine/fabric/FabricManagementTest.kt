// SPDX-License-Identifier: Apache-2.0

package cringle.engine.fabric

import cringle.common.v1.FabricId
import cringle.common.v1.PluginRef
import cringle.common.v1.ProjectRef
import cringle.contract.BlockDefinition
import cringle.contract.SchemaRef
import cringle.engine.Engine
import cringle.engine.EngineArgs
import cringle.engine.v1.DeployFabricRequest
import cringle.engine.v1.DeployedPlugin
import cringle.engine.v1.EngineManagementServiceGrpcKt.EngineManagementServiceCoroutineStub
import cringle.engine.v1.FabricRequest
import cringle.engine.v1.FabricRuntimeState
import cringle.engine.v1.ListFabricsRequest
import cringle.engine.v1.PluginTrust as ProtoTrust
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.SafeUnzip
import cringle.testkit.TestJar
import cringle.testkit.TestPluginBuilder
import cringle.testkit.TestProjectBuilder
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Deploys a real project and plugin (compiled test JAR) through the management API of an in-process engine. */
class FabricManagementTest {
    @TempDir
    lateinit var dir: Path

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

    private fun installPackages(home: Path, m1: Path, m2: Path) {
        val work = Files.createDirectories(dir.resolve("build"))
        val plugin = TestPluginBuilder("acme-demo", "1.0.0")
            .provider("com.acme.MarkerProvider")
            .block(BlockDefinition("marker", emptyList(), emptyList(), emptyList(), SchemaRef("acme.demo", "MarkerConfig")))
            .schema("marker.json", markerSchema)
            .lib("marker.jar", TestJar.fromJavaSources(providerSource))
            .build(work)
        fun block(id: String, marker: Path) =
            BlueprintBlock(id, "acme-demo/marker", config = JsonObject(mapOf("marker" to JsonPrimitive(marker.toString()))))
        val project = TestProjectBuilder("demo", "0.1.0")
            .dependency("acme-demo", "^1.0.0")
            .blueprint(Blueprint("main", listOf(block("m1", m1), block("m2", m2)), emptyList()))
            .build(work, listOf(plugin.pkg))
        SafeUnzip.extract(plugin.file, home.resolve("plugins/acme-demo/1.0.0"))
        SafeUnzip.extract(project.file, home.resolve("projects/demo/0.1.0"))
    }

    private fun fabricRequest(id: String) = FabricRequest.newBuilder().setFabricId(FabricId.newBuilder().setValue(id)).build()

    private fun deploy(id: String, trust: ProtoTrust, project: String = "demo", blueprint: String = "main") = DeployFabricRequest.newBuilder()
        .setFabricId(FabricId.newBuilder().setValue(id))
        .setProject(ProjectRef.newBuilder().setName(project).setVersion("0.1.0"))
        .setBlueprint(blueprint)
        .addPlugins(DeployedPlugin.newBuilder().setPlugin(PluginRef.newBuilder().setName("acme-demo").setVersion("1.0.0")).setTrust(trust))
        .build()

    private fun withEngine(body: suspend (EngineManagementServiceCoroutineStub, Path, Path) -> Unit) {
        val home = Files.createDirectories(dir.resolve("home"))
        val m1 = dir.resolve("m1.txt")
        val m2 = dir.resolve("m2.txt")
        installPackages(home, m1, m2)
        val engine = Engine.create(EngineArgs("e1", null, home, 0, true)).start()
        val channel = ManagedChannelBuilder.forAddress("127.0.0.1", engine.managementPort).usePlaintext().build()
        try {
            runBlocking { body(EngineManagementServiceCoroutineStub(channel), m1, m2) }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            engine.stop()
        }
    }

    private fun code(block: suspend () -> Unit): Status.Code =
        assertThrows<StatusException> { runBlocking { block() } }.status.code

    @Test
    fun deployStartStopRestartAndRemoveThroughTheManagementApi() = withEngine { stub, m1, m2 ->
        val deployed = stub.deployFabric(deploy("shop-1", ProtoTrust.PLUGIN_TRUST_TRUSTED))
        assertEquals(FabricRuntimeState.FABRIC_RUNTIME_STATE_CREATED, deployed.state)
        assertEquals(listOf("m1", "m2"), deployed.blocksList.map { it.blockId.value })
        assertFalse(Files.exists(m1))

        val started = stub.startFabric(fabricRequest("shop-1"))
        assertEquals(FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING, started.state)
        assertEquals("started", Files.readString(m1))
        assertEquals("started", Files.readString(m2))
        assertEquals(FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING, stub.getFabricStatus(fabricRequest("shop-1")).state)
        assertEquals(listOf("shop-1"), stub.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList.map { it.fabricId.value })

        assertEquals(FabricRuntimeState.FABRIC_RUNTIME_STATE_STOPPED, stub.stopFabric(fabricRequest("shop-1")).state)
        assertEquals("stopped", Files.readString(m1))
        Files.delete(m1)
        assertEquals(FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING, stub.startFabric(fabricRequest("shop-1")).state)
        assertEquals("started", Files.readString(m1))

        stub.removeFabric(fabricRequest("shop-1"))
        assertEquals("stopped", Files.readString(m2))
        assertTrue(stub.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList.isEmpty())
        assertEquals(Status.Code.NOT_FOUND, code { stub.getFabricStatus(fabricRequest("shop-1")) })
    }

    @Test
    fun directoriesOfTheFabricAndItsBlocksExistAfterStart() = withEngine { stub, _, _ ->
        stub.deployFabric(deploy("f-dirs", ProtoTrust.PLUGIN_TRUST_TRUSTED))
        stub.startFabric(fabricRequest("f-dirs"))
        val root = dir.resolve("home/engines/e1/fabrics/f-dirs")
        for (b in listOf("m1", "m2")) {
            assertTrue(Files.isDirectory(root.resolve("working/$b")) && Files.isDirectory(root.resolve("logs/$b")))
        }
        assertTrue(Files.readString(root.resolve("logs/fabric.log")).contains("starting fabric 'f-dirs'"))
    }

    @Test
    fun twoInstancesOfTheSameBlueprintRunTogether() = withEngine { stub, m1, _ ->
        stub.deployFabric(deploy("one", ProtoTrust.PLUGIN_TRUST_TRUSTED))
        stub.deployFabric(deploy("two", ProtoTrust.PLUGIN_TRUST_TRUSTED))
        stub.startFabric(fabricRequest("one"))
        stub.startFabric(fabricRequest("two"))
        val all = stub.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList
        assertEquals(listOf("one", "two"), all.map { it.fabricId.value })
        assertTrue(all.all { it.state == FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING })
        stub.stopFabric(fabricRequest("one"))
        assertEquals(FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING, stub.getFabricStatus(fabricRequest("two")).state)
        assertTrue(Files.exists(m1))
    }

    @Test
    fun unspecifiedOrUntrustedPluginFailsClosedOnStart() = withEngine { stub, m1, _ ->
        stub.deployFabric(deploy("unspecified", ProtoTrust.PLUGIN_TRUST_UNSPECIFIED))
        assertEquals(Status.Code.FAILED_PRECONDITION, code { stub.startFabric(fabricRequest("unspecified")) })
        val failed = stub.getFabricStatus(fabricRequest("unspecified"))
        assertEquals(FabricRuntimeState.FABRIC_RUNTIME_STATE_FAILED, failed.state)
        assertTrue(failed.failure.contains("needs isolation level PROCESS"), failed.failure)
        assertFalse(Files.exists(m1))
        stub.deployFabric(deploy("untrusted", ProtoTrust.PLUGIN_TRUST_UNTRUSTED))
        assertEquals(Status.Code.FAILED_PRECONDITION, code { stub.startFabric(fabricRequest("untrusted")) })
    }

    @Test
    fun invalidDeploymentsAreRejectedWithReadableErrors() = withEngine { stub, _, _ ->
        fun failure(request: DeployFabricRequest): StatusException = assertThrows { runBlocking { stub.deployFabric(request) } }
        val noProject = failure(deploy("f", ProtoTrust.PLUGIN_TRUST_TRUSTED, project = "ghost"))
        assertEquals(Status.Code.INVALID_ARGUMENT, noProject.status.code)
        assertTrue(noProject.status.description!!.contains("not unpacked"), noProject.status.description)
        val noBlueprint = failure(deploy("f", ProtoTrust.PLUGIN_TRUST_TRUSTED, blueprint = "other"))
        assertTrue(noBlueprint.status.description!!.contains("no blueprint 'other'"), noBlueprint.status.description)
        val badId = failure(deploy("Bad Id", ProtoTrust.PLUGIN_TRUST_TRUSTED))
        assertTrue(badId.status.description!!.contains("must match"), badId.status.description)
        val noPlugins = failure(deploy("f", ProtoTrust.PLUGIN_TRUST_TRUSTED).toBuilder().clearPlugins().build())
        assertTrue(noPlugins.status.description!!.contains("unknown block 'acme-demo/marker'"), noPlugins.status.description)
        stub.deployFabric(deploy("dup", ProtoTrust.PLUGIN_TRUST_TRUSTED))
        assertTrue(failure(deploy("dup", ProtoTrust.PLUGIN_TRUST_TRUSTED)).status.description!!.contains("already exists"))
        assertEquals(Status.Code.NOT_FOUND, code { stub.startFabric(fabricRequest("nope")) })
        assertEquals(Status.Code.NOT_FOUND, code { stub.removeFabric(fabricRequest("nope")) })
    }
}
