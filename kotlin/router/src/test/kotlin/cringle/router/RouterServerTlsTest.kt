// SPDX-License-Identifier: Apache-2.0

package cringle.router

import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.common.TlsHelper
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.TrustStore
import cringle.router.v1.ListEnginesRequest
import cringle.router.v1.RegistryServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Router with TLS. mTLS needs trust in both directions: the caller trusts the key of the server (client side check of
 * the handshake) and the server trusts the key of the caller (`TrustInterceptor`, every method that is not open).
 */
class RouterServerTlsTest {
    @TempDir
    lateinit var dir: Path

    private val closeables = ArrayList<() -> Unit>()
    private var counter = 0

    @AfterEach
    fun tearDown() {
        closeables.reversed().forEach { runCatching { it() } }
    }

    private class Node(val name: String, val identity: Identity, val trust: TrustStore, val server: RouterServer) {
        val fp: String get() = identity.publicKeyFingerprint
        val address: String get() = "127.0.0.1:${server.port}"
    }

    private fun node(name: String): Node {
        val identity = Identity.loadOrCreate(dir.resolve(name), ComponentKind.ROUTER.commonName(name))
        val trust = TrustStore(dir.resolve("$name-trust.json"))
        val server = RouterServer(
            dir.resolve("$name-registry.json"),
            refreshInterval = Duration.ofHours(1),
            tls = RouterTls(identity, trust),
        ).start()
        closeables += { server.stop() }
        return Node(name, identity, trust, server)
    }

    /** A channel to [to]; [serverTrusted] says whether the caller trusts the key of [to]; [me] is the client certificate or null. */
    private fun channel(to: Node, me: Identity?, serverTrusted: Boolean = true): ManagedChannel {
        val store = TrustStore(dir.resolve("client-${counter++}-trust.json"))
        if (serverTrusted) store.add(TrustEntry(to.fp, to.name, TrustKind.ROUTER))
        val channel = NettyChannelBuilder.forAddress("127.0.0.1", to.server.port)
            .sslContext(TlsHelper.channelCredentials(me, store))
            .build()
        closeables += { channel.shutdownNow() }
        return channel
    }

    private fun listEngines(channel: ManagedChannel) = runBlocking {
        RegistryServiceGrpcKt.RegistryServiceCoroutineStub(channel)
            .withDeadlineAfter(30, TimeUnit.SECONDS)
            .listEngines(ListEnginesRequest.getDefaultInstance())
    }

    private fun trust(who: Node, other: Node) = who.trust.add(TrustEntry(other.fp, other.name, TrustKind.ROUTER, address = other.address))

    @Test
    fun routerWithTlsRejectsUntrustedRouterDuringRefresh() {
        val a = node("a")
        val b = node("b")
        a.server.registry.addRemote(b.address)

        runBlocking { a.server.remoteRouters.refresh(b.address) }

        assertEquals("remote router is not trusted", a.server.registry.remotes().single { it.address == b.address }.lastError)
    }

    @Test
    fun routerWithTlsSucceedsWhenRouterIsTrustedInBothDirections() {
        val a = node("a")
        val b = node("b")
        trust(a, b)
        trust(b, a)
        a.server.registry.addRemote(b.address)

        runBlocking { a.server.remoteRouters.refresh(b.address) }

        assertEquals(null, a.server.registry.remotes().single { it.address == b.address }.lastError)
    }

    @Test
    fun refreshFailsWhenTheRemoteRouterDoesNotTrustTheCaller() {
        val a = node("a")
        val b = node("b")
        trust(a, b) // b does not trust a: the handshake works, the interceptor of b refuses the call
        a.server.registry.addRemote(b.address)

        runBlocking { a.server.remoteRouters.refresh(b.address) }

        val error = a.server.registry.remotes().single { it.address == b.address }.lastError
        assertEquals(true, error?.startsWith("UNAUTHENTICATED"), error)
    }

    @Test
    fun routerWithTlsRejectsCallWithoutClientCertificate() {
        val b = node("b")

        val e = assertThrows(StatusException::class.java) { listEngines(channel(b, null)) }

        assertEquals(Status.Code.UNAUTHENTICATED, e.status.code)
    }

    @Test
    fun routerWithTlsRejectsCallFromUntrustedRouter() {
        val a = node("a")
        val b = node("b")

        val e = assertThrows(StatusException::class.java) { listEngines(channel(b, a.identity)) }

        assertEquals(Status.Code.UNAUTHENTICATED, e.status.code)
    }

    @Test
    fun callerThatDoesNotTrustTheServerFailsInTheHandshake() {
        val a = node("a")
        val b = node("b")
        trust(b, a)

        val e = assertThrows(StatusException::class.java) { listEngines(channel(b, a.identity, serverTrusted = false)) }

        assertEquals(Status.Code.UNAVAILABLE, e.status.code)
    }

    @Test
    fun routerWithTlsAllowsConnectionFromTrustedRouter() {
        val a = node("a")
        val b = node("b")
        trust(a, b) // the server a trusts the caller b; b trusts a through the channel helper

        val response = listEngines(channel(a, b.identity))

        assertEquals(0, response.enginesList.size)
    }
}
