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
 * Lets blocks write entries into the data warehouse. **[Zu bestätigen]** This is deliberately minimal: the
 * structure and the query interface of the data warehouse are open points of the architecture.
 */
public interface DwhDriver : Driver {
    /** Writes [entry] into the data warehouse. */
    public suspend fun write(entry: DwhEntry)
}

/**
 * One entry for the data warehouse. **[Zu bestätigen]** See [DwhDriver].
 *
 * @property key identifies what the entry is about.
 * @property value the payload.
 * @property timestamp when the entry was created.
 */
public data class DwhEntry(
    public val key: String,
    public val value: Any,
    public val timestamp: java.time.Instant = java.time.Instant.now(),
) {
    init {
        require(key.isNotBlank()) { "DwhEntry key must not be blank" }
    }
}
