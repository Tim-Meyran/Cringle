// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.daemon.Daemon
import cringle.management.v1.AddMachineRequest
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The M6 criterion (#174, `docs/shared-services.md`): a shared service is used by two projects on different machines, and
 * when it fails the consumers switch to an alternative. Two machines are two daemons with homes of their own, each with its
 * router; the routers trust each other, so that an engine on one machine finds the fabric of an engine on the other. One
 * ManagementServer manages both. Everything runs over loopback and mutual TLS.
 *
 * Machine m1 runs the service (`orders-service`, preferred) and the consumer `shop`; machine m2 runs the backup service
 * (`orders-backup`) and the consumer `remote-shop`.
 */
@Tag("integration")
class SharedServiceEndToEndTest : ServiceTestBase() {
    private fun secondMachine(): Daemon {
        val home2 = Files.createDirectories(dir.resolve("home2"))
        val second = Daemon(home2, combined = true).start()
        closeables += second
        secondDaemon = second
        tls.trust(second)
        // the engines of the second machine are told to trust the repository, as those of the first one were
        second.trustStore.add(TrustEntry(tls.repositoryFingerprint(0), "repository-0", TrustKind.COMPONENT))
        // the routers trust each other, as an operator does with `cringle trust add` (Architecture 5.1)
        val router1 = daemon.router!!
        val router2 = second.router!!
        router1.tls!!.trustStore.add(TrustEntry(router2.identity!!.publicKeyFingerprint, "router-2", TrustKind.ROUTER, address = "127.0.0.1:${router2.port}"))
        router2.tls!!.trustStore.add(TrustEntry(router1.identity!!.publicKeyFingerprint, "router-1", TrustKind.ROUTER, address = "127.0.0.1:${router1.port}"))
        router1.registry.addRemote("127.0.0.1:${router2.port}")
        router2.registry.addRemote("127.0.0.1:${router1.port}")
        runBlocking {
            s.addMachine(AddMachineRequest.newBuilder().setMachineId("m2").setDaemonAddress("127.0.0.1:${second.port}").build())
            engine("e-m2", "m2", "m2")
            engine("e-bak2", "backup", "m2")
        }
        // the periodic exchange of the routers is slow (30 s): once now, and again whenever the test waits
        refreshRouters(router1.port, router2.port)
        return second
    }

    private fun refreshRouters(port1: Int, port2: Int) = runBlocking {
        val router1 = daemon.router!!
        val router2 = secondDaemon!!.router!!
        runCatching { router1.remoteRouters.refresh("127.0.0.1:$port2") }
        runCatching { router2.remoteRouters.refresh("127.0.0.1:$port1") }
    }

    private var secondDaemon: Daemon? = null

    @Test
    fun aServiceOnOneMachineServesConsumersOnBothAndASecondInstanceOnTheOtherMachineTakesOver() {
        secondDaemon = secondMachine()
        val port1 = daemon.router!!.port
        val port2 = secondDaemon!!.router!!.port
        // the backup is placed on m2: the engine of m1 that could take it is stopped
        runBlocking { s.stopEngine(ref("e-bak")) }
        // the service and the consumer `shop` on m1, the consumer `remote-shop` and the backup on m2
        deploy("orders-service")
        deploy("orders-backup")
        bind("shop", "orders-service-service-1", "orders-backup-service-1")
        bind("remote-shop", "orders-service-service-1", "orders-backup-service-1")
        deploy("shop")
        deploy("remote-shop")
        val deadline = System.nanoTime() + 90_000_000_000L
        while (!lines().containsAll(setOf("from-shop", "from-remote-shop"))) {
            check(System.nanoTime() < deadline) { "the service did not get both consumers, only ${lines()}\n" + diagnostics() }
            refreshRouters(port1, port2)
            Thread.sleep(500)
        }
        // Until the routers know each other the consumer on m2 cannot resolve the preferred instance and rightly falls back to the backup, so the backup may
        // have seen early messages. Once both consumers reach the preferred instance they have gone back to it: from here on only the preferred instance gets messages.
        Files.deleteIfExists(backupFile)
        clear()
        val settled = System.nanoTime() + 90_000_000_000L
        fun counts(): Map<String, Int> = if (Files.exists(received)) Files.readAllLines(received).groupingBy { it }.eachCount() else emptyMap()
        while ((counts()["from-shop"] ?: 0) < 3 || (counts()["from-remote-shop"] ?: 0) < 3) {
            check(System.nanoTime() < settled) { "the preferred instance did not keep getting both consumers, only ${counts()}\n" + diagnostics() }
            Thread.sleep(200)
        }
        assertTrue(lines(backupFile).isEmpty(), "the preferred instance gets the messages, but the backup got ${lines(backupFile)}")

        // the service fails (its fabric is removed): both consumers move to the backup, without being touched
        runBlocking {
            s.removeFabric(
                cringle.management.v1.FabricRef.newBuilder().setEngine(ref("e-svc")).setFabricId(cringle.common.v1.FabricId.newBuilder().setValue("orders-service-service-1")).build(),
            )
        }
        val deadline2 = System.nanoTime() + 90_000_000_000L
        while (!lines(backupFile).containsAll(setOf("from-shop", "from-remote-shop"))) {
            check(System.nanoTime() < deadline2) { "the backup did not get both consumers, only ${lines(backupFile)}\n" + diagnostics() }
            refreshRouters(port1, port2)
            Thread.sleep(500)
        }
    }
}
