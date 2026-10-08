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

/** Tethers between projects (#179) and failover between instances of a service (#173), on one machine. */
@Tag("integration")
class ServiceBindingDeployTest : ServiceTestBase() {
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
