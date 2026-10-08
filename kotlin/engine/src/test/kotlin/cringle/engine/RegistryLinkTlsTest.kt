// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import cringle.common.ComponentKind
import cringle.common.EngineTls
import cringle.common.Identity
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.TrustStore
import cringle.router.RouterServer
import cringle.router.RouterTls
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Tag("integration")
class RegistryLinkTlsTest {
    @TempDir
    lateinit var dir: Path

    private val closeables = ArrayList<() -> Unit>()

    @AfterEach
    fun tearDown() {
        closeables.reversed().forEach { runCatching { it() } }
    }

    private fun args(id: String) = EngineArgs(id = id, name = "Engine $id", home = dir.resolve("home"), managementPort = 0)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun awaitTrue(what: String, seconds: Long = 10, cond: () -> Boolean) {
        val end = System.nanoTime() + Duration.ofSeconds(seconds).toNanos()
        while (!cond()) {
            check(System.nanoTime() < end) { "timed out waiting for $what" }
            Thread.sleep(25)
        }
    }

    private fun routerWithTls(name: String): RouterServer {
        val identity = Identity.loadOrCreate(dir.resolve(name), ComponentKind.ROUTER.commonName(name))
        val trust = TrustStore(dir.resolve("$name-trust.json"))
        val server = RouterServer(
            dir.resolve("$name-registry.json"),
            refreshInterval = Duration.ofHours(1),
            tls = RouterTls(identity, trust),
        ).start()
        closeables += { server.stop() }
        return server
    }

    private fun engineTls(name: String, router: RouterServer): EngineTls {
        val engineDir = dir.resolve("home").resolve("engines").resolve(name)
        val identity = Identity.loadOrCreate(engineDir, ComponentKind.ENGINE.commonName(name))
        val trust = TrustStore(engineDir.resolve("trust.json"))
        trust.add(TrustEntry(router.identity!!.publicKeyFingerprint, "router", TrustKind.ROUTER, address = "127.0.0.1:${router.port}"))
        return EngineTls(identity, trust)
    }

    @Test
    fun engineWithValidSecretRegistersAtTlsRouter() {
        val router = routerWithTls("r1")
        val engineTls = engineTls("e1", router)
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hash = MessageDigest.getInstance("SHA-256").digest(secret)
        router.enrollment!!.prepare("e1", hash)

        val engine = Engine.create(args("e1"), mapOf("CRINGLE_ENROLLMENT_SECRET" to hex(secret)), Duration.ofMillis(50))
        closeables += { engine.stop() }
        try {
            engine.start()
            engine.setRouterAddress("127.0.0.1:${router.port}")
            awaitTrue("registration") { router.registry.engines().any { it.record.id == "e1" } }
            val view = router.registry.engines().single { it.record.id == "e1" }
            assertEquals(engineTls.identity.publicKeyFingerprint, view.record.fingerprint)
            // the heartbeat carries a few numbers (#190)
            awaitTrue("vitals") { router.registry.engines().single { it.record.id == "e1" }.vitals != null }
            val vitals = router.registry.engines().single { it.record.id == "e1" }.vitals!!
            assertTrue(vitals.memoryUsedBytes > 0 && vitals.memoryUsedBytes <= vitals.memoryMaxBytes, vitals.toString())
            assertEquals(0, vitals.fabricCount)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun engineWithoutSecretIsRefused() {
        val router = routerWithTls("r1")
        engineTls("e1", router) // trust store written but no enrollment.prepare call

        val engine = Engine.create(args("e1"), emptyMap(), Duration.ofMillis(50))
        closeables += { engine.stop() }
        try {
            engine.start()
            engine.setRouterAddress("127.0.0.1:${router.port}")
            Thread.sleep(3000)
            assertTrue(router.registry.engines().isEmpty(), "engine should have been refused without a secret")
        } finally {
            engine.stop()
        }
    }

    @Test
    fun engineWithForeignKeyIsRefused() {
        val router = routerWithTls("r1")
        val foreignTls = engineTls("foreign", router)
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hash = MessageDigest.getInstance("SHA-256").digest(secret)
        router.enrollment!!.prepare("e1", hash)

        // Create engine with id "e1" but using the foreign identity
        val engine = Engine.create(args("e1"), mapOf("CRINGLE_ENROLLMENT_SECRET" to hex(secret)), Duration.ofMillis(50))
        closeables += { engine.stop() }
        try {
            engine.start()
            engine.setRouterAddress("127.0.0.1:${router.port}")
            Thread.sleep(3000)
            assertTrue(router.registry.engines().isEmpty(), "engine with foreign key should have been refused")
        } finally {
            engine.stop()
        }
    }

    @Test
    fun engineWithTrustStoreFailsAgainstPlaintextRouter() {
        val router = RouterServer(dir.resolve("r-plain-registry.json")).start()
        closeables += { router.stop() }
        // Create a trust store with a dummy entry so the engine tries mTLS
        val engineDir = dir.resolve("home").resolve("engines").resolve("e1")
        Identity.loadOrCreate(engineDir, ComponentKind.ENGINE.commonName("e1"))
        TrustStore(engineDir.resolve("trust.json")).add(
            TrustEntry("0".repeat(64), "dummy", TrustKind.ROUTER, address = "127.0.0.1:${router.port}"),
        )

        val engine = Engine.create(args("e1"), emptyMap(), Duration.ofMillis(50))
        closeables += { engine.stop() }
        try {
            engine.start()
            engine.setRouterAddress("127.0.0.1:${router.port}")
            Thread.sleep(3000)
            assertTrue(router.registry.engines().isEmpty(), "engine should have failed against plaintext router")
        } finally {
            engine.stop()
        }
    }

    @Test
    fun engineKeepsIdentityOverRestartAndSecondRegistrationWithoutSecretWorks() {
        val router = routerWithTls("r1")
        val engineDir = dir.resolve("home").resolve("engines").resolve("e1")
        val identity = Identity.loadOrCreate(engineDir, ComponentKind.ENGINE.commonName("e1"))
        val trust = TrustStore(engineDir.resolve("trust.json"))
        trust.add(TrustEntry(router.identity!!.publicKeyFingerprint, "router", TrustKind.ROUTER, address = "127.0.0.1:${router.port}"))

        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hash = MessageDigest.getInstance("SHA-256").digest(secret)
        router.enrollment!!.prepare("e1", hash)

        // First registration with secret
        val engine1 = Engine.create(args("e1"), mapOf("CRINGLE_ENROLLMENT_SECRET" to hex(secret)), Duration.ofMillis(50))
        try {
            engine1.start()
            engine1.setRouterAddress("127.0.0.1:${router.port}")
            awaitTrue("first registration") { router.registry.engines().any { it.record.id == "e1" } }
            assertEquals(identity.publicKeyFingerprint, router.registry.engines().single { it.record.id == "e1" }.record.fingerprint)
        } finally {
            engine1.stop()
        }

        Thread.sleep(500)

        // Second registration without secret (same identity, same path)
        val engine2 = Engine.create(args("e1"), emptyMap(), Duration.ofMillis(50))
        try {
            engine2.start()
            engine2.setRouterAddress("127.0.0.1:${router.port}")
            awaitTrue("second registration") { router.registry.engines().any { it.record.id == "e1" } }
            assertEquals(identity.publicKeyFingerprint, router.registry.engines().single { it.record.id == "e1" }.record.fingerprint)
        } finally {
            engine2.stop()
        }

        Thread.sleep(500)

        // Third: a registration with a different key for the same id is refused
        val foreignDir = dir.resolve("foreign-identity")
        val foreignIdentity = Identity.loadOrCreate(foreignDir, ComponentKind.ENGINE.commonName("foreign"))
        assertNotEquals(identity.publicKeyFingerprint, foreignIdentity.publicKeyFingerprint)

        val ex = assertThrows(io.grpc.StatusException::class.java) {
            router.enrollment!!.enroll(
                "e1",
                foreignIdentity.certificate.encoded,
                ByteArray(0), // no secret
                foreignIdentity.publicKeyFingerprint,
                "127.0.0.1:0",
            )
        }
        assertEquals(io.grpc.Status.Code.PERMISSION_DENIED, ex.status.code)
    }
}
