// SPDX-License-Identifier: Apache-2.0

package cringle.router

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir

class MutableClock(private var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advance(d: Duration) {
        now = now.plus(d)
    }
}

class RegistryTest {
    @TempDir
    lateinit var dir: Path

    private val clock = MutableClock()
    private fun registry(file: Path = dir.resolve("registry.json")) =
        Registry(FileRegistryStore(file), clock, Duration.ofSeconds(15))

    private val fabric = FabricSummary("f1", "bp", "FABRIC_LIFECYCLE_STATE_RUNNING")

    @Test
    fun `registered engine is listed and reachable`() {
        val r = registry()
        r.register("e1", "Engine One", "127.0.0.1:1")
        val views = r.engines()
        assertEquals(1, views.size)
        assertEquals("Engine One", views[0].record.name)
        assertEquals(Reachability.REACHABLE, views[0].reachability)
    }

    @Test
    fun `the tether address of an engine is kept, found by the fabric it runs and persisted`() {
        val file = dir.resolve("registry-tether.json")
        val r = registry(file)
        r.register("e1", "Engine One", "127.0.0.1:1", tetherAddress = "127.0.0.1:2")
        r.heartbeat("e1", listOf(fabric))
        assertEquals("127.0.0.1:2", r.lookupFabric("f1")!!.record.tetherAddress)
        // an engine that moves registers its new address
        r.register("e1", "Engine One", "127.0.0.1:1", tetherAddress = "127.0.0.1:3")
        assertEquals("127.0.0.1:3", r.lookupFabric("f1")!!.record.tetherAddress)
        assertEquals("127.0.0.1:3", registry(file).engines().single().record.tetherAddress)
        // an engine without a tether service has an empty address
        r.register("e2", "Engine Two", "127.0.0.1:4")
        assertEquals("", r.engines().single { it.record.id == "e2" }.record.tetherAddress)
    }

    @Test
    fun `engine becomes unreachable after the timeout and recovers with a heartbeat`() {
        val r = registry()
        r.register("e1", "e1", "127.0.0.1:1")
        clock.advance(Duration.ofSeconds(16))
        assertEquals(Reachability.UNREACHABLE, r.engines().single().reachability)
        assertTrue(r.engines(onlyReachable = true).isEmpty())
        r.heartbeat("e1", listOf(fabric))
        assertEquals(Reachability.REACHABLE, r.engines().single().reachability)
        assertEquals(listOf(fabric), r.engines().single().record.fabrics)
    }

    @Test
    fun `heartbeat of unknown engine fails`() {
        assertThrows<EngineNotRegisteredException> { registry().heartbeat("nope", emptyList()) }
    }

    @Test
    fun `unregister removes the engine`() {
        val r = registry()
        r.register("e1", "e1", "127.0.0.1:1")
        assertTrue(r.unregister("e1"))
        assertFalse(r.unregister("e1"))
        assertTrue(r.engines().isEmpty())
    }

    @Test
    fun `lookup finds the engine that runs a fabric`() {
        val r = registry()
        r.register("e1", "e1", "127.0.0.1:1")
        r.register("e2", "e2", "127.0.0.1:2")
        r.heartbeat("e2", listOf(fabric))
        assertEquals("e2", r.lookupFabric("f1")?.record?.id)
        assertNull(r.lookupFabric("unknown"))
    }

    @Test
    fun `registrations survive a restart but are unreachable until the next heartbeat`() {
        val r = registry()
        r.register("e1", "e1", "127.0.0.1:1")
        r.heartbeat("e1", listOf(fabric))
        val restarted = registry()
        val view = restarted.engines().single()
        assertEquals("e1", view.record.id)
        assertEquals(listOf(fabric), view.record.fabrics)
        assertEquals(Reachability.UNREACHABLE, view.reachability)
        assertNotNull(restarted.lookupFabric("f1"))
        restarted.heartbeat("e1", listOf(fabric))
        assertEquals(Reachability.REACHABLE, restarted.engines().single().reachability)
    }

    @Test
    fun `remote routers and cached engines are persisted and listed on request`() {
        val r = registry()
        r.addRemote("10.0.0.1:7000")
        r.updateRemote(
            "10.0.0.1:7000",
            listOf(EngineRecord("far", "far", "10.0.0.2:1", listOf(fabric)) to Reachability.REACHABLE),
            null,
        )
        assertTrue(r.engines().isEmpty())
        val remote = r.engines(includeRemote = true).single()
        assertEquals("10.0.0.1:7000", remote.record.origin)
        assertEquals("far", r.lookupFabric("f1")?.record?.id)
        val restarted = registry()
        assertEquals(1, restarted.remotes().single().engines.size)
        assertTrue(restarted.removeRemote("10.0.0.1:7000"))
        assertTrue(restarted.engines(includeRemote = true).isEmpty())
    }

    @Test
    fun `corrupt registry file is reported, not silently replaced`() {
        val file = dir.resolve("registry.json")
        Files.writeString(file, "{ not json")
        assertThrows<RegistryStoreException> { registry(file) }
        assertEquals("{ not json", Files.readString(file))
    }

    @Test
    fun `changes counter increases with every change`() {
        val r = registry()
        val before = r.changes.value
        r.register("e1", "e1", "127.0.0.1:1")
        r.heartbeat("e1", emptyList())
        assertTrue(r.changes.value >= before + 2)
    }

    @Test
    fun `the numbers of a heartbeat are kept in memory, the last ones survive a heartbeat without them`() {
        val file = dir.resolve("registry-vitals.json")
        val r = registry(file)
        r.register("e1", "Engine One", "127.0.0.1:1")
        assertNull(r.engines().single().vitals)
        val vitals = cringle.common.v1.EngineMetrics.newBuilder().setFabricCount(3).setRunningFabricCount(2).setErrorCount(5).setMemoryUsedBytes(10).build()
        r.heartbeat("e1", listOf(fabric), vitals)
        assertEquals(vitals, r.engines().single().vitals)
        r.heartbeat("e1", listOf(fabric))
        assertEquals(vitals, r.engines().single().vitals)
        // not persisted: a registry that reads the file has none
        assertNull(registry(file).engines().single().vitals)
        // gone with the engine
        r.unregister("e1")
        r.register("e1", "Engine One", "127.0.0.1:1")
        assertNull(r.engines().single().vitals)
    }
}
