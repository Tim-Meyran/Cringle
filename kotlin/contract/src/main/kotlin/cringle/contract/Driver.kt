// SPDX-License-Identifier: Apache-2.0

package cringle.contract

/**
 * The single gateway of a block to Cringle and to the outside world: operating system, file system,
 * network, user management, logging and the data warehouse.
 *
 * **Decided:** every driver exists exactly once per engine. Blocks use the engine-wide instance, which is
 * injected by their [BlockProvider]. The driver separates blocks from each other by the [BlockId] of the
 * caller. Blueprint authors never see drivers, only block authors do.
 *
 * All operations that may wait are `suspend` functions. Implementations must not block the thread of the
 * calling dispatcher.
 */
public interface Driver {
    /** The type of this driver. */
    public val type: DriverType

    /**
     * Starts the driver. **[Zu bestätigen]** Minimal lifecycle; retry, reconnect and error behavior of drivers
     * are open points of the architecture.
     */
    public suspend fun start() {}

    /** Stops the driver. **[Zu bestätigen]** See [start]. */
    public suspend fun stop() {}
}

/**
 * Describes a kind of driver.
 *
 * @property id the identifier blocks use to require the driver, see [BlockDefinition.requiredDrivers].
 * @property isolation the isolation level this kind of driver needs.
 */
public data class DriverType(public val id: String, public val isolation: IsolationLevel) {
    init {
        require(id.isNotBlank()) { "DriverType id must not be blank" }
    }
}

/**
 * How strongly a driver or block is isolated. The levels are ordered by ascending strictness: a level with a
 * higher ordinal is stricter, so the stricter of two levels is the maximum. More levels may be inserted
 * between the existing ones later; compare levels, never rely on their ordinals.
 */
public enum class IsolationLevel {
    /** Runs in the normal thread of its fabric. */
    SHARED,

    /** Runs in its own child process. */
    PROCESS,
}

/**
 * Lets blocks write entries into the data warehouse of the engine and read their own back (Architecture 16.5). **[Zu bestätigen]**
 * The structure of the data warehouse is an open point of the architecture; this is the interface of its first version: the
 * entries of a block form one partition of the engine's store, ordered by time and removed by the retention of that partition.
 * A block reads only its own partition.
 */
public interface DwhDriver : Driver {
    /** Writes [entry] into the data warehouse. */
    public suspend fun write(entry: DwhEntry)

    /**
     * The entries of this block in the range [since] to [until] (both inclusive and optional): the newest [limit], oldest first.
     * A driver that cannot read (a fake in a test) throws [UnsupportedOperationException].
     */
    public suspend fun read(since: java.time.Instant? = null, until: java.time.Instant? = null, limit: Int = 1000): List<DwhEntry> =
        throw UnsupportedOperationException("this data warehouse driver cannot read")
}

/**
 * One entry for the data warehouse. **[Zu bestätigen]** See [DwhDriver].
 *
 * @property key identifies what the entry is about.
 * @property value the payload.
 * @property timestamp when the entry was created.
 * @property tags free labels, for example to find the entry again.
 */
public data class DwhEntry(
    public val key: String,
    public val value: Any,
    public val timestamp: java.time.Instant = java.time.Instant.now(),
    public val tags: Map<String, String> = emptyMap(),
) {
    init {
        require(key.isNotBlank()) { "DwhEntry key must not be blank" }
    }
}
