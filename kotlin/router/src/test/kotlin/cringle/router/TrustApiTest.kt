// SPDX-License-Identifier: Apache-2.0

package cringle.router

import com.google.protobuf.ByteString
import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.router.RouterServer
import cringle.router.RouterTls
import cringle.common.TlsHelper
import cringle.common.TrustStore
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.v1.EngineId
import cringle.contract.UserRole
import cringle.router.users.AuthInterceptor
import cringle.router.users.InMemoryUserStore
import cringle.router.users.UserManager
import cringle.router.v1.AddRemoteRouterRequest
import cringle.router.v1.ListEnginesRequest
import cringle.router.v1.ListEnginesResponse
import cringle.router.v1.ListTrustRequest
import cringle.router.v1.PrepareEngineRequest
import cringle.router.v1.RegisterEngineRequest
import cringle.router.v1.RevokeRemoteRouterRequest
import cringle.router.v1.RegistryServiceGrpcKt
import cringle.router.v1.TrustRemoteRouterRequest
import io.grpc.CallCredentials
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TrustApiTest {
    @TempDir
    lateinit var dir: Path

    private val closeables = ArrayList<() -> Unit>()
    private var counter = 0

    @AfterEach
    fun tearDown() {
        closeables.reversed().forEach { runCatching { it() } }
    }

    private class Node(val name: String, val identity: Identity, val trust: TrustStore, val users: UserManager, val server: RouterServer) {
        val fp: String get() = identity.publicKeyFingerprint
        val address: String get() = "127.0.0.1:${server.port}"
    }

    private fun node(name: String): Node {
        val identity = Identity.loadOrCreate(dir.resolve(name), ComponentKind.ROUTER.commonName(name))
        val trust = TrustStore(dir.resolve("$name-trust.json"))
        val users = UserManager(InMemoryUserStore())
        val server = RouterServer(
            dir.resolve("$name-registry.json"),
            refreshInterval = Duration.ofHours(1),
            tls = RouterTls(identity, trust),
            users = users,
        ).start()
        closeables += { server.stop() }
        return Node(name, identity, trust, users, server)
    }

    private fun token(node: Node, role: UserRole): String {
        val created = node.users.createUser("$role-${counter++}", setOf(role))
        return node.users.createToken(created.user.id, "t", null).secret
    }

    /** A TLS channel to [to] that trusts only [to] as a server; [me] is the client certificate, or null for none. */
    private fun channel(to: Node, me: Identity?): ManagedChannel {
        val store = TrustStore(dir.resolve("client-${counter++}-trust.json"))
        store.add(TrustEntry(to.fp, to.name, TrustKind.ROUTER))
        val channel = NettyChannelBuilder.forAddress("127.0.0.1", to.server.port)
            .sslContext(TlsHelper.channelCredentials(me, store))
            .build()
        closeables += { channel.shutdownNow() }
        return channel
    }

    private fun api(channel: ManagedChannel, token: String? = null): RegistryServiceGrpcKt.RegistryServiceCoroutineStub {
        val stub = RegistryServiceGrpcKt.RegistryServiceCoroutineStub(channel).withDeadlineAfter(30, TimeUnit.SECONDS)
        if (token == null) return stub
        return stub.withCallCredentials(object : CallCredentials() {
            override fun applyRequestMetadata(info: RequestInfo, executor: Executor, applier: MetadataApplier) {
                applier.apply(Metadata().also { it.put(AuthInterceptor.AUTHORIZATION, "Bearer $token") })
            }
        })
    }

    /** The response of a call, or the status it failed with. */
    private class Outcome<T>(val value: T?, val status: Status)

    private fun <T> outcome(body: suspend () -> T): Outcome<T> = try {
        Outcome(runBlocking { body() }, Status.OK)
    } catch (e: StatusException) {
        Outcome(null, e.status)
    }

    private fun peer(name: String, kind: ComponentKind): Identity = Identity.loadOrCreate(dir.resolve(name), kind.commonName(name))

    private fun listEngines(node: Node, me: Identity?): Outcome<ListEnginesResponse> =
        outcome { api(channel(node, me)).listEngines(ListEnginesRequest.newBuilder().setIncludeRemote(true).build()) }

    /** The engine registers with its certificate and the secret. */
    private fun register(router: Node, me: Identity, engine: Identity, id: String, secret: ByteArray?): Status.Code {
        val builder = RegisterEngineRequest.newBuilder().setEngineId(EngineId.newBuilder().setValue(id)).setName(id).setManagementAddress("127.0.0.1:1")
            .setCertificate(ByteString.copyFrom(engine.certificate.encoded))
        if (secret != null) builder.setEnrollmentSecret(ByteString.copyFrom(secret))
        return outcome { api(channel(router, me)).registerEngine(builder.build()) }.status.code
    }

    /** The daemon announces the engine at [router], the engine registers with its certificate and the secret. */
    private fun enroll(router: Node, daemon: Identity, engine: Identity, id: String) {
        val secret = "secret-$id".toByteArray()
        val engineId = EngineId.newBuilder().setValue(id).build()
        val prepare = outcome { api(channel(router, daemon)).prepareEngine(PrepareEngineRequest.newBuilder().setEngineId(engineId).setEnrollmentSecretHash(sha256(secret)).build()) }
        assertEquals(Status.Code.OK, prepare.status.code, prepare.status.toString())
        val register = outcome {
            api(channel(router, engine)).registerEngine(
                RegisterEngineRequest.newBuilder().setEngineId(engineId).setName(id).setManagementAddress("127.0.0.1:1")
                    .setCertificate(ByteString.copyFrom(engine.certificate.encoded)).setEnrollmentSecret(ByteString.copyFrom(secret)).build(),
            )
        }
        assertEquals(Status.Code.OK, register.status.code, register.status.toString())
    }

    private fun sha256(v: ByteArray): ByteString = ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(v))

    @Test
    fun enginesOfARouterThatIsNotTrustedAreNotListed() {
        val a = node("a")
        val ops = peer("ops", ComponentKind.DAEMON)
        a.trust.add(TrustEntry(ops.publicKeyFingerprint, "ops", TrustKind.COMPONENT))
        a.server.registry.addRemote("127.0.0.1:9")
        a.server.registry.updateRemote("127.0.0.1:9", listOf(EngineRecord("x", "x", "h:1") to Reachability.REACHABLE), null)
        assertEquals(1, a.server.registry.engines(true).size)
        val hidden = listEngines(a, ops)
        assertEquals(Status.Code.OK, hidden.status.code, hidden.status.toString())
        assertEquals(0, hidden.value!!.enginesList.size)
        a.trust.add(TrustEntry("1".repeat(64), "r", TrustKind.ROUTER, address = "127.0.0.1:9"))
        val shown = listEngines(a, ops)
        assertEquals(listOf("x"), shown.value!!.enginesList.map { it.engineId.value })
    }

    @Test
    fun withoutTlsTheRouterHasNoTrustAndKeepsTheOldAddBehavior() {
        val plain = RouterServer(dir.resolve("plain-registry.json")).start()
        closeables += { plain.stop() }
        val channel = NettyChannelBuilder.forAddress("127.0.0.1", plain.port).usePlaintext().build()
        closeables += { channel.shutdownNow() }
        val hash = ByteString.copyFrom(ByteArray(32))
        val trust = outcome { api(channel).trustRemoteRouter(TrustRemoteRouterRequest.newBuilder().setAddress("127.0.0.1:1").setExpectedFingerprint("0".repeat(64)).build()) }
        val list = outcome { api(channel).listTrust(ListTrustRequest.getDefaultInstance()) }
        val prepare = outcome { api(channel).prepareEngine(PrepareEngineRequest.newBuilder().setEngineId(EngineId.newBuilder().setValue("e")).setEnrollmentSecretHash(hash).build()) }
        for (status in listOf(trust.status, list.status, prepare.status)) assertEquals(Status.Code.FAILED_PRECONDITION, status.code, status.toString())
        val add = outcome { api(channel).addRemoteRouter(AddRemoteRouterRequest.newBuilder().setAddress("127.0.0.1:1").build()) }
        assertEquals(Status.Code.OK, add.status.code, add.status.toString())
    }

    @Test
    fun anExpectedFingerprintThatDoesNotMatchIsRefusedAndBothValuesAreNamed() {
        val a = node("a")
        val b = node("b")
        val admin = token(a, UserRole.ADMIN)
        val wrong = "0".repeat(64)

        val mismatch = outcome { api(channel(a, null), admin).addRemoteRouter(AddRemoteRouterRequest.newBuilder().setAddress(b.address).setExpectedFingerprint(wrong).build()) }
        assertEquals(Status.Code.FAILED_PRECONDITION, mismatch.status.code, mismatch.status.toString())
        val text = mismatch.status.description ?: ""
        assertTrue(wrong in text && b.fp in text, text)
        assertTrue(a.trust.list().isEmpty())

        val blank = outcome { api(channel(a, null), admin).addRemoteRouter(AddRemoteRouterRequest.newBuilder().setAddress(b.address).build()) }
        assertEquals(Status.Code.INVALID_ARGUMENT, blank.status.code, blank.status.toString())
        assertTrue(a.trust.list().isEmpty())

        val ok = outcome { api(channel(a, null), admin).addRemoteRouter(AddRemoteRouterRequest.newBuilder().setAddress(b.address).setExpectedFingerprint(b.fp).build()) }
        assertEquals(Status.Code.OK, ok.status.code, ok.status.toString())
        val entry = a.trust.list().single()
        assertEquals(TrustKind.ROUTER, entry.kind)
        assertEquals(b.fp, entry.fingerprint)
    }

    @Test
    fun trustInARouterMakesItsEnginesTransitivelyTrustedAndRevokeRemovesThemAndCutsTheRouter() {
        val a = node("a")
        val b = node("b")
        val daemonB = peer("daemon-b", ComponentKind.DAEMON)
        val engine = peer("engine-1", ComponentKind.ENGINE)
        val admin = token(a, UserRole.ADMIN)

        // b trusts a as a router and itself (as the daemon that enrolls engines)
        b.trust.add(TrustEntry(a.fp, "a", TrustKind.ROUTER, address = a.address))
        b.trust.add(TrustEntry(daemonB.publicKeyFingerprint, "daemon-b", TrustKind.COMPONENT))

        // enroll an engine through b (b is trusted by a)
        enroll(b, daemonB, engine, "eng-1")

        val bToA = channel(a, b.identity)          // one channel, reused: b as a client of a
        fun bAsksA() = outcome { api(bToA).listEngines(ListEnginesRequest.newBuilder().setIncludeRemote(true).build()) }
        assertEquals(Status.Code.UNAUTHENTICATED, bAsksA().status.code)      // not trusted yet

        // a trusts b as a remote router
        val added = outcome { api(channel(a, null), admin).addRemoteRouter(AddRemoteRouterRequest.newBuilder().setAddress(b.address).setExpectedFingerprint(b.fp).build()) }
        assertEquals(Status.Code.OK, added.status.code, added.status.toString())
        assertEquals(1, added.value!!.router.cachedEngines)

        val entries = a.trust.list()
        assertEquals(2, entries.size)                                          // router + engine (engine came via b)
        val routerEntry = entries.single { it.kind == TrustKind.ROUTER }
        assertEquals(b.fp, routerEntry.fingerprint); assertEquals(null, routerEntry.origin)
        val engineEntry = entries.single { it.kind == TrustKind.ENGINE }
        assertEquals(engine.publicKeyFingerprint, engineEntry.fingerprint); assertEquals(b.fp, engineEntry.origin)

        // b is trusted now (via the router entry), so listEngines works
        val shown = listEngines(a, b.identity)
        assertEquals(listOf("eng-1" to engine.publicKeyFingerprint), shown.value!!.enginesList.map { it.engineId.value to it.fingerprint })

        // b forgets the engine: the next refresh removes the transitive entry
        assertTrue(b.server.registry.unregister("eng-1"))
        runBlocking { a.server.remoteRouters.refresh(b.address) }
        assertEquals(listOf(TrustKind.ROUTER), a.trust.list().map { it.kind })

        // b reports it again
        b.server.registry.register("eng-1", "eng-1", "127.0.0.1:1", engine.publicKeyFingerprint)
        runBlocking { a.server.remoteRouters.refresh(b.address) }
        assertEquals(b.fp, a.trust.list().single { it.kind == TrustKind.ENGINE }.origin)

        // revoke
        val revoked = outcome { api(channel(a, null), admin).revokeRemoteRouter(RevokeRemoteRouterRequest.newBuilder().setAddress(b.address).build()) }
        assertEquals(Status.Code.OK, revoked.status.code, revoked.status.toString())
        assertEquals(2, revoked.value!!.removedEntries)
        assertTrue(a.trust.list().isEmpty())
        assertTrue(a.server.registry.remotes().isEmpty())
        assertEquals(0, a.server.registry.engines(true).size)
        assertEquals(Status.Code.UNAUTHENTICATED, bAsksA().status.code)      // the same connection, new call: refused

        val unknown = outcome { api(channel(a, null), admin).revokeRemoteRouter(RevokeRemoteRouterRequest.newBuilder().setAddress("127.0.0.1:9999").build()) }
        assertEquals(Status.Code.NOT_FOUND, unknown.status.code)
    }

    @Test
    fun enrollmentBindsTheKeyAndRefusesWrongReusedAndForeignSecrets() {
        val r = node("r")
        val daemon = peer("daemon", ComponentKind.DAEMON)
        val e1 = peer("e1", ComponentKind.ENGINE)
        val e2 = peer("e2", ComponentKind.ENGINE)
        r.trust.add(TrustEntry(daemon.publicKeyFingerprint, "daemon", TrustKind.COMPONENT))

        fun prepare(id: String, secret: String): Status.Code = outcome { api(channel(r, daemon)).prepareEngine(PrepareEngineRequest.newBuilder().setEngineId(EngineId.newBuilder().setValue(id)).setEnrollmentSecretHash(sha256(secret.toByteArray())).build()) }.status.code

        assertEquals(Status.Code.OK, prepare("eng", "s1"))
        assertEquals(Status.Code.PERMISSION_DENIED, register(r, e1, e1, "eng", "wrong".toByteArray()))
        assertEquals(Status.Code.PERMISSION_DENIED, register(r, e1, e1, "eng", null))
        // certificate of e1 on e2's connection
        assertEquals(Status.Code.PERMISSION_DENIED, register(r, e2, e1, "eng", "s1".toByteArray()))
        assertTrue(r.trust.list().none { it.kind == TrustKind.ENGINE })

        assertEquals(Status.Code.OK, register(r, e1, e1, "eng", "s1".toByteArray()))
        val bound = r.trust.list().single { it.kind == TrustKind.ENGINE }
        assertEquals(e1.publicKeyFingerprint, bound.fingerprint)
        assertEquals("eng", bound.name)
        assertEquals(null, bound.origin)
        assertEquals(e1.publicKeyFingerprint, r.server.registry.engines(false).single().record.fingerprint)

        assertEquals(Status.Code.OK, register(r, e1, e1, "eng", null))
        assertEquals(Status.Code.PERMISSION_DENIED, register(r, e2, e2, "eng", "s1".toByteArray()))
        assertEquals(Status.Code.PERMISSION_DENIED, register(r, e2, e2, "eng", null))

        assertEquals(Status.Code.OK, prepare("eng2", "s2"))
        assertEquals(Status.Code.PERMISSION_DENIED, register(r, e2, e2, "eng2", "s1".toByteArray()))
        assertEquals(Status.Code.OK, register(r, e2, e2, "eng2", "s2".toByteArray()))
        assertEquals(Status.Code.PERMISSION_DENIED, register(r, e1, e1, "eng2", null))
    }
}
