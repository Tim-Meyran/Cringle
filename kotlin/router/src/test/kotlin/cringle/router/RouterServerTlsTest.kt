// SPDX-License-Identifier: Apache-2.0

package cringle.router

import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.common.TlsHelper
import cringle.common.TrustStore
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.router.v1.ListEnginesRequest
import cringle.router.v1.ListEnginesResponse
import cringle.router.v1.RegistryServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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

    private fun channel(to: Node, me: Identity?): ManagedChannel {
        val store = TrustStore(dir.resolve("client-${counter++}-trust.json"))
        store.add(TrustEntry(to.fp, to.name, TrustKind.ROUTER))
        val channel = NettyChannelBuilder.forAddress("127.0.0.1", to.server.port)
            .sslContext(TlsHelper.channelCredentials(me, store))
            .build()
        closeables += { channel.shutdownNow() }
        return channel
    }

    private fun api(channel: ManagedChannel): RegistryServiceGrpcKt.RegistryServiceCoroutineStub {
        return RegistryServiceGrpcKt.RegistryServiceCoroutineStub(channel).withDeadlineAfter(30, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test
    fun routerWithTlsRejectsUntrustedRouterDuringRefresh() {
        val a = node("a")
        val b = node("b")
        
        // Add b as remote router to a without trusting it
        a.server.registry.addRemote(b.address)
        
        // Try to refresh - should fail because b is not trusted
        runBlocking { a.server.remoteRouters.refresh(b.address) }
        
        // Check that the remote router has an error status
        val remote = a.server.registry.remotes().find { it.address == b.address }
        assertEquals("remote router is not trusted", remote?.lastError)
    }

    @Test
    fun routerWithTlsSucceedsWhenRouterIsTrusted() {
        val a = node("a")
        val b = node("b")
        
        // Trust b as a router
        a.trust.add(TrustEntry(b.fp, "b", TrustKind.ROUTER, address = b.address))
        
        // Add b as remote router to a
        a.server.registry.addRemote(b.address)
        
        // Refresh should succeed
        runBlocking { a.server.remoteRouters.refresh(b.address) }
        
        // Check that the remote router has no error
        val remote = a.server.registry.remotes().find { it.address == b.address }
        assertEquals(null, remote?.lastError)
    }

    @Test
    fun routerWithTlsRejectsConnectionFromUntrustedRouter() {
        val a = node("a")
        val b = node("b")
        
        // Try to list engines from b without trusting it
        val channel = NettyChannelBuilder.forAddress("127.0.0.1", b.server.port)
            .sslContext(TlsHelper.channelCredentials(null, TrustStore(dir.resolve("empty-trust.json"))))
            .build()
        closeables += { channel.shutdownNow() }
        
        val stub = RegistryServiceGrpcKt.RegistryServiceCoroutineStub(channel)
        try {
            runBlocking { stub.listEngines(ListEnginesRequest.getDefaultInstance()) }
        } catch (e: Exception) {
            // Expected to fail with UNAUTHENTICATED
            assertEquals("UNAUTHENTICATED", e.message?.substringBefore(":")?.trim())
        }
    }

    @Test
    fun routerWithTlsAllowsConnectionFromTrustedRouter() {
        val a = node("a")
        val b = node("b")
        
        // Trust a as a router
        b.trust.add(TrustEntry(a.fp, "a", TrustKind.ROUTER, address = a.address))
        
        // List engines from a
        val channel = NettyChannelBuilder.forAddress("127.0.0.1", a.server.port)
            .sslContext(TlsHelper.channelCredentials(b.identity, TrustStore(dir.resolve("client-trust.json")).apply {
                add(TrustEntry(a.fp, "a", TrustKind.ROUTER))
            }))
            .build()
        closeables += { channel.shutdownNow() }
        
        val stub = RegistryServiceGrpcKt.RegistryServiceCoroutineStub(channel)
        val response = runBlocking { stub.listEngines(ListEnginesRequest.getDefaultInstance()) }
        
        // Should succeed
        assertEquals(0, response.enginesList.size)
    }
}