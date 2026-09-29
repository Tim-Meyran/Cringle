// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import cringle.common.v1.FabricId
import cringle.common.v1.FabricLifecycleState
import cringle.common.v1.FabricStateSummary
import cringle.router.Reachability
import cringle.router.RouterServer
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir

class RegistryLinkTest {
    @TempDir
    lateinit var dir: Path

    private fun args() = EngineArgs(
        id = "e1", name = "Engine One", home = dir.resolve("home"), managementPort = 0, insecureDevMode = true,
    )

    private fun awaitTrue(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (!cond()) {
            check(System.nanoTime() < end) { "timed out waiting for $what" }
            Thread.sleep(25)
        }
    }

    @Test
    fun `engine registers, reports fabrics and unregisters on stop`() {
        val router = RouterServer(dir.resolve("r.json")).start()
        val engine = Engine.create(args(), emptyMap(), Duration.ofMillis(50))
        engine.fabricStates = {
            listOf(
                FabricStateSummary.newBuilder().setFabricId(FabricId.newBuilder().setValue("f1"))
                    .setState(FabricLifecycleState.FABRIC_LIFECYCLE_STATE_RUNNING).setBlueprintName("bp").build(),
            )
        }
        try {
            engine.start()
            engine.setRouterAddress("127.0.0.1:${router.port}")
            awaitTrue("registration") { router.registry.lookupFabric("f1") != null }
            val view = router.registry.engines().single()
            assertEquals("Engine One", view.record.name)
            assertEquals(Reachability.REACHABLE, view.reachability)
            engine.stop()
            awaitTrue("unregistration") { router.registry.engines().isEmpty() }
        } finally {
            engine.stop()
            router.stop()
        }
    }

    @Test
    fun `engine registers again after the router lost its registration`() {
        val router = RouterServer(dir.resolve("r.json")).start()
        val engine = Engine.create(args(), emptyMap(), Duration.ofMillis(50))
        try {
            engine.start()
            engine.setRouterAddress("127.0.0.1:${router.port}")
            awaitTrue("registration") { router.registry.engines().isNotEmpty() }
            router.registry.unregister("e1")
            assertTrue(router.registry.engines().isEmpty())
            awaitTrue("re-registration") { router.registry.engines().isNotEmpty() }
        } finally {
            engine.stop()
            router.stop()
        }
    }
}
