// SPDX-License-Identifier: Apache-2.0

package cringle.router

import cringle.common.v1.EngineHeartbeat
import cringle.common.v1.EngineId
import cringle.common.v1.FabricId
import cringle.common.v1.FabricLifecycleState
import cringle.common.v1.FabricStateSummary
import cringle.router.v1.AddRemoteRouterRequest
import cringle.router.v1.ListEnginesRequest
import cringle.router.v1.LookupFabricRequest
import cringle.router.v1.RegisterEngineRequest
import cringle.router.v1.RegistryServiceGrpcKt
import cringle.router.v1.SendHeartbeatRequest
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class RouterServerTest {
    @TempDir
    lateinit var dir: Path

    private fun stub(server: RouterServer): Pair<ManagedChannel, RegistryServiceGrpcKt.RegistryServiceCoroutineStub> {
        val ch = NettyChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
        return ch to RegistryServiceGrpcKt.RegistryServiceCoroutineStub(ch)
    }

    private fun eid(v: String) = EngineId.newBuilder().setValue(v).build()

    private fun register(s: RegistryServiceGrpcKt.RegistryServiceCoroutineStub, id: String) = runBlocking {
        s.registerEngine(
            RegisterEngineRequest.newBuilder().setEngineId(eid(id)).setName(id).setManagementAddress("127.0.0.1:9").build(),
        )
    }

    private fun heartbeat(s: RegistryServiceGrpcKt.RegistryServiceCoroutineStub, id: String, fabric: String) = runBlocking {
        s.sendHeartbeat(
            SendHeartbeatRequest.newBuilder().setHeartbeat(
                EngineHeartbeat.newBuilder().setEngineId(eid(id)).addFabricStates(
                    FabricStateSummary.newBuilder().setFabricId(FabricId.newBuilder().setValue(fabric))
                        .setState(FabricLifecycleState.FABRIC_LIFECYCLE_STATE_RUNNING).setBlueprintName("bp"),
                ),
            ).build(),
        )
    }

    @Test
    fun `register, heartbeat, list and lookup over gRPC`() {
        val server = RouterServer(dir.resolve("r.json")).start()
        val (ch, s) = stub(server)
        try {
            register(s, "e1")
            heartbeat(s, "e1", "f1")
            val engines = runBlocking { s.listEngines(ListEnginesRequest.getDefaultInstance()) }.enginesList
            assertEquals(listOf("e1"), engines.map { it.engineId.value })
            val found = runBlocking {
                s.lookupFabric(LookupFabricRequest.newBuilder().setFabricId(FabricId.newBuilder().setValue("f1")).build())
            }
            assertEquals("e1", found.engine.engineId.value)
        } finally {
            ch.shutdownNow()
            server.stop()
        }
    }

    @Test
    fun `heartbeat of unknown engine is NOT_FOUND`() {
        val server = RouterServer(dir.resolve("r.json")).start()
        val (ch, s) = stub(server)
        try {
            val e = assertThrows<StatusException> { heartbeat(s, "ghost", "f") }
            assertEquals(Status.Code.NOT_FOUND, e.status.code)
        } finally {
            ch.shutdownNow()
            server.stop()
        }
    }

    @Test
    fun `lookup works again after the router restarted`() {
        val file = dir.resolve("r.json")
        var server = RouterServer(file).start()
        var (ch, s) = stub(server)
        register(s, "e1")
        heartbeat(s, "e1", "f1")
        ch.shutdownNow()
        server.stop()
        server = RouterServer(file).start()
        val pair = stub(server)
        ch = pair.first
        s = pair.second
        try {
            val found = runBlocking {
                s.lookupFabric(LookupFabricRequest.newBuilder().setFabricId(FabricId.newBuilder().setValue("f1")).build())
            }
            assertEquals("e1", found.engine.engineId.value)
        } finally {
            ch.shutdownNow()
            server.stop()
        }
    }

    @Test
    fun `two routers exchange engines through a remote router entry`() {
        val a = RouterServer(dir.resolve("a.json")).start()
        val b = RouterServer(dir.resolve("b.json")).start()
        val (cha, sa) = stub(a)
        val (chb, sb) = stub(b)
        try {
            register(sb, "far")
            heartbeat(sb, "far", "remote-fabric")
            val added = runBlocking {
                sa.addRemoteRouter(AddRemoteRouterRequest.newBuilder().setAddress("127.0.0.1:${b.port}").build())
            }
            assertEquals(1, added.router.cachedEngines)
            assertTrue(runBlocking { sa.listEngines(ListEnginesRequest.getDefaultInstance()) }.enginesList.isEmpty())
            val all = runBlocking { sa.listEngines(ListEnginesRequest.newBuilder().setIncludeRemote(true).build()) }
            assertEquals("127.0.0.1:${b.port}", all.enginesList.single().originRouter)
            val found = runBlocking {
                sa.lookupFabric(LookupFabricRequest.newBuilder().setFabricId(FabricId.newBuilder().setValue("remote-fabric")).build())
            }
            assertEquals("far", found.engine.engineId.value)
        } finally {
            cha.shutdownNow()
            chb.shutdownNow()
            a.stop()
            b.stop()
        }
    }

    @Test
    fun `unreachable remote router is recorded with an error`() {
        val a = RouterServer(dir.resolve("a.json")).start()
        val (ch, s) = stub(a)
        try {
            val added = runBlocking { s.addRemoteRouter(AddRemoteRouterRequest.newBuilder().setAddress("127.0.0.1:1").build()) }
            assertTrue(added.router.lastError.isNotEmpty())
        } finally {
            ch.shutdownNow()
            a.stop()
        }
    }
}
