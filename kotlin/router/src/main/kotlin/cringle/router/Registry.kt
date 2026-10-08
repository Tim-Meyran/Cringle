// SPDX-License-Identifier: Apache-2.0

package cringle.router

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Whether an engine has sent a heartbeat recently. */
public enum class Reachability {
    /** A heartbeat arrived within the timeout. */
    REACHABLE,

    /** No heartbeat within the timeout (or none since the router started). */
    UNREACHABLE,
}

/** A fabric on an engine as reported by a heartbeat; [state] is the name of `FabricLifecycleState`. */
public data class FabricSummary(val fabricId: String, val blueprint: String, val state: String)

/** An engine known to the registry. [origin] is the remote router it was learned from, `null` for local engines. */
public data class EngineRecord(
    val id: String,
    val name: String,
    val managementAddress: String,
    val fabrics: List<FabricSummary> = emptyList(),
    val origin: String? = null,
    /** Public key fingerprint of the engine once it enrolled (Architecture 5); empty for engines without one. */
    val fingerprint: String = "",
    /** `host:port` of the tether service of the engine (tethers between engines), empty if it has none. */
    val tetherAddress: String = "",
    /** The numbers of the last heartbeat, learned from a remote router; not persisted. For local engines see [EngineView.vitals]. */
    val vitals: cringle.common.v1.EngineMetrics? = null,
)

/** An [EngineRecord] with its current reachability, last heartbeat and the numbers it carried (memory only, #190). */
public data class EngineView(val record: EngineRecord, val reachability: Reachability, val lastHeartbeat: Instant?, val vitals: cringle.common.v1.EngineMetrics? = record.vitals)

/** A known remote router with the engines cached from it. */
public data class RemoteRouterRecord(
    val address: String,
    val engines: List<EngineRecord> = emptyList(),
    val lastRefresh: Instant? = null,
    val lastError: String? = null,
)

/** Everything the registry persists. */
public data class RegistryData(val engines: List<EngineRecord>, val remotes: List<RemoteRouterRecord>)

/** Persistence of [RegistryData]. */
public interface RegistryStore {
    /** Loads the stored data; empty data if nothing was stored yet. */
    public fun load(): RegistryData

    /** Stores [data], replacing everything. */
    public fun save(data: RegistryData)
}

/** A store that keeps data in memory only (for tests and throw-away routers). */
public class InMemoryRegistryStore(private var data: RegistryData = RegistryData(emptyList(), emptyList())) : RegistryStore {
    override fun load(): RegistryData = data

    override fun save(data: RegistryData) {
        this.data = data
    }
}

/** Thrown when an engine is not registered. */
public class EngineNotRegisteredException(public val engineId: String) : RuntimeException("engine '$engineId' is not registered")

/**
 * The registry of a router (Architecture 4.2): registered engines with the fabrics they run, and remote routers with
 * their cached engines. An engine counts as reachable while its last heartbeat is younger than [heartbeatTimeout].
 * Heartbeat times are not persisted, so after a restart every engine is unreachable until it sends its next heartbeat.
 */
public class Registry(
    private val store: RegistryStore,
    private val clock: Clock = Clock.systemUTC(),
    /** How long after a heartbeat an engine still counts as reachable. */
    public val heartbeatTimeout: Duration = Duration.ofSeconds(15),
) {
    private val lock = Any()
    private val engines = LinkedHashMap<String, EngineRecord>()
    private val heartbeats = HashMap<String, Instant>()
    private val remotes = LinkedHashMap<String, RemoteRouterRecord>()
    private val version = MutableStateFlow(0L)

    init {
        val data = store.load()
        data.engines.forEach { engines[it.id] = it }
        data.remotes.forEach { remotes[it.address] = it }
    }

    /** Increases with every change, including heartbeats; lets callers and tests wait for changes. */
    public val changes: StateFlow<Long> get() = version

    private fun changed(persist: Boolean) {
        if (persist) store.save(RegistryData(engines.values.toList(), remotes.values.toList()))
        version.value = version.value + 1
    }

    /** Registers or refreshes an engine; counts as a heartbeat. Keeps the fabric list of an existing registration. */
    public fun register(id: String, name: String, managementAddress: String, fingerprint: String = "", tetherAddress: String = "") {
        synchronized(lock) {
            val existing = engines[id]
            val bound = fingerprint.ifEmpty { existing?.fingerprint.orEmpty() }
            engines[id] = EngineRecord(id, name, managementAddress, existing?.fabrics.orEmpty(), fingerprint = bound, tetherAddress = tetherAddress)
            heartbeats[id] = clock.instant()
            changed(persist = existing?.name != name || existing.managementAddress != managementAddress || existing.fingerprint != bound || existing.tetherAddress != tetherAddress)
        }
    }

    /** Removes an engine; returns whether it was registered. */
    public fun unregister(id: String): Boolean = synchronized(lock) {
        val removed = engines.remove(id) != null
        heartbeats.remove(id)
        vitalsById.remove(id)
        if (removed) changed(persist = true)
        removed
    }

    /**
     * Records a heartbeat with the engine's current [fabrics] and, if it sent them, its [vitals] (kept in memory only; a
     * heartbeat without them keeps the last ones). Throws [EngineNotRegisteredException] if unknown.
     */
    public fun heartbeat(id: String, fabrics: List<FabricSummary>, vitals: cringle.common.v1.EngineMetrics? = null) {
        synchronized(lock) {
            val record = engines[id] ?: throw EngineNotRegisteredException(id)
            heartbeats[id] = clock.instant()
            if (vitals != null) vitalsById[id] = vitals
            val fabricsChanged = record.fabrics != fabrics
            if (fabricsChanged) engines[id] = record.copy(fabrics = fabrics)
            changed(persist = fabricsChanged)
        }
    }

    private val vitalsById = HashMap<String, cringle.common.v1.EngineMetrics>()

    private fun view(record: EngineRecord): EngineView {
        val last = heartbeats[record.id]
        val reachable = last != null && Duration.between(last, clock.instant()) <= heartbeatTimeout
        return EngineView(record, if (reachable) Reachability.REACHABLE else Reachability.UNREACHABLE, last, vitalsById[record.id])
    }

    /**
     * Lists local engines and, with [includeRemote], the cached engines of remote routers. Remote engines keep the
     * reachability their router last reported; they are never more reachable than the last refresh.
     */
    public fun engines(includeRemote: Boolean = false, onlyReachable: Boolean = false): List<EngineView> = synchronized(lock) {
        val local = engines.values.map { view(it) }
        val remote = if (includeRemote) remotes.values.flatMap { r -> r.engines.map { remoteView(r, it) } } else emptyList()
        (local + remote).filter { !onlyReachable || it.reachability == Reachability.REACHABLE }
    }

    private fun remoteView(router: RemoteRouterRecord, engine: EngineRecord): EngineView {
        val fresh = router.lastRefresh != null && router.lastError == null &&
            Duration.between(router.lastRefresh, clock.instant()) <= heartbeatTimeout.multipliedBy(4)
        val reachable = fresh && remoteReachable[engine.id + "@" + router.address] != false
        return EngineView(engine.copy(origin = router.address), if (reachable) Reachability.REACHABLE else Reachability.UNREACHABLE, router.lastRefresh)
    }

    private val remoteReachable = HashMap<String, Boolean>()

    /** Finds the engine running [fabricId]: local engines first, then cached remote engines; `null` if unknown. */
    public fun lookupFabric(fabricId: String): EngineView? = synchronized(lock) {
        engines.values.firstOrNull { e -> e.fabrics.any { it.fabricId == fabricId } }?.let { return view(it) }
        for (r in remotes.values) {
            r.engines.firstOrNull { e -> e.fabrics.any { it.fabricId == fabricId } }?.let { return remoteView(r, it) }
        }
        null
    }

    /** Adds a remote router (no-op if known) and returns its record. */
    public fun addRemote(address: String): RemoteRouterRecord = synchronized(lock) {
        remotes.getOrPut(address) {
            RemoteRouterRecord(address).also {
                remotes[address] = it
                changed(persist = true)
            }
        }
    }

    /** Removes a remote router and its cached engines; returns whether it was known. */
    public fun removeRemote(address: String): Boolean = synchronized(lock) {
        val removed = remotes.remove(address) != null
        if (removed) changed(persist = true)
        removed
    }

    /** All remote routers. */
    public fun remotes(): List<RemoteRouterRecord> = synchronized(lock) { remotes.values.toList() }

    /**
     * Stores the result of a refresh of [address]: the [engines] of the remote router with their reported
     * reachability, or, if [error] is set, only the error (the previous cache is kept).
     */
    public fun updateRemote(address: String, engines: List<Pair<EngineRecord, Reachability>>?, error: String?) {
        synchronized(lock) {
            val old = remotes[address] ?: return
            remotes[address] = if (engines != null && error == null) {
                engines.forEach { (e, r) -> remoteReachable[e.id + "@" + address] = r == Reachability.REACHABLE }
                old.copy(engines = engines.map { it.first.copy(origin = address) }, lastRefresh = clock.instant(), lastError = null)
            } else {
                old.copy(lastError = error)
            }
            changed(persist = true)
        }
    }
}
