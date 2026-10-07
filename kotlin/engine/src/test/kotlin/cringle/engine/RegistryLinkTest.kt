// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.TrustStore
import cringle.common.v1.FabricId
import cringle.common.v1.FabricLifecycleState
import cringle.common.v1.FabricStateSummary
import cringle.router.Reachability
import cringle.router.RouterServer
import cringle.router.RouterTls
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The link of an engine to its router: always mutual TLS, with the enrollment secret of the daemon (docs/trust.md). */
class RegistryLinkTest {
    @TempDir
    lateinit var dir: Path

    private fun args() = EngineArgs(id = "e1", name = "Engine One", home = dir.resolve("home"), managementPort = 0)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun awaitTrue(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (!cond()) {
            check(System.nanoTime() < end) { "timed out waiting for $what" }
            Thread.sleep(25)
        }
    }

    /** A router with TLS, and the trust file of engine `e1` that names it; the daemon announces the secret that is returned as hex. */
    private fun routerAndSecret(): Pair<RouterServer, String> {
        val routerIdentity = Identity.loadOrCreate(dir.resolve("router"), ComponentKind.ROUTER.commonName("router"))
        val router = RouterServer(
            dir.resolve("r.json"),
            refreshInterval = Duration.ofHours(1),
            tls = RouterTls(routerIdentity, TrustStore(dir.resolve("router-trust.json"))),
        ).start()
        val engineDir = dir.resolve("home").resolve("engines").resolve("e1")
        Identity.loadOrCreate(engineDir, ComponentKind.ENGINE.commonName("e1"))
        TrustStore(engineDir.resolve("trust.json"))
            .add(TrustEntry(routerIdentity.publicKeyFingerprint, "router", TrustKind.ROUTER, address = "127.0.0.1:${router.port}"))
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        router.enrollment!!.prepare("e1", MessageDigest.getInstance("SHA-256").digest(secret))
        return router to hex(secret)
    }

    @Test
    fun `engine registers, reports fabrics and unregisters on stop`() {
        val (router, secret) = routerAndSecret()
        val engine = Engine.create(args(), mapOf("CRINGLE_ENROLLMENT_SECRET" to secret), Duration.ofMillis(50))
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
        val (router, secret) = routerAndSecret()
        val engine = Engine.create(args(), mapOf("CRINGLE_ENROLLMENT_SECRET" to secret), Duration.ofMillis(50))
        try {
            engine.start()
            engine.setRouterAddress("127.0.0.1:${router.port}")
            awaitTrue("registration") { router.registry.engines().isNotEmpty() }
            router.registry.unregister("e1")
            assertTrue(router.registry.engines().isEmpty())
            // the key is bound to the id now, so the engine needs no secret for the second registration
            awaitTrue("re-registration") { router.registry.engines().isNotEmpty() }
        } finally {
            engine.stop()
            router.stop()
        }
    }
}
