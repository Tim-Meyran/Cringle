// SPDX-License-Identifier: Apache-2.0

package cringle.contract

/**
 * A unit of an application. Blocks are assembled into blueprints, communicate over tethers and are
 * created by a [BlockProvider].
 *
 * Every block has its own lifecycle and can be started and stopped independently of the other blocks of
 * its fabric. The engine calls the hooks in the order `init`, `start`, `stop`, `destroy`; a block that is
 * restarted after a crash goes through the sequence again with a new instance. All hooks default to no-ops,
 * so an empty block is valid.
 *
 * A block reaches the outside world exclusively through the drivers it received from its provider.
 *
 * All hooks are `suspend` functions. Implementations must not block the thread of the calling dispatcher.
 */
public interface Block {
    /** Called once after creation, before the block is started. Ports and configuration are available through [context]. */
    public suspend fun init(context: BlockContext) {}

    /** Called to start the block. After it returns, the block may receive tether events. */
    public suspend fun start() {}

    /** Called to stop the block. It can be followed by [destroy] or, depending on the engine, by another [start]. */
    public suspend fun stop() {}

    /** Called once when the block instance is discarded. Release everything acquired in [init] here. */
    public suspend fun destroy() {}

    /**
     * Called for every tether event that arrives at one of the block's ports, see [TetherEvent].
     * Events for one block are delivered by the engine; the block never has to block to receive them.
     */
    public suspend fun onTetherEvent(event: TetherEvent) {}
}

/**
 * The identity of a block instance.
 *
 * Drivers exist once per engine (see [Driver]) and use this identity to keep blocks apart, for example to
 * assign runtime paths, log tags or quotas.
 */
@JvmInline
public value class BlockId(public val value: String) {
    init {
        require(value.isNotBlank()) { "BlockId must not be blank" }
    }

    override fun toString(): String = value
}

/** What the engine hands to a block in [Block.init]. */
public interface BlockContext {
    /** The identity of this block instance. */
    public val blockId: BlockId

    /**
     * The block configuration.
     *
     * **[Zu bestätigen]** The engine validates it against [BlockDefinition.configSchema] before handing it
     * over; it is represented as a map of field names to values in the canonical value form of the schema
     * system.
     */
    public val config: Map<String, Any?>

    /** Access to the tethers of this block's ports. */
    public val ports: BlockPorts

    /**
     * The log folder of this block (`<fabric>/logs/<block>`), or `null` if the engine gives none. A block that starts a
     * foreign process with its own log files lets that process write `*.log` files here; the engine reads them into its
     * log query (Architecture 16.1).
     */
    public val logDirectory: java.nio.file.Path? get() = null

    /**
     * The data folder of this block, or `null` if the engine gives none: `<home>/data/<project>/<blueprint>/<n>/<block id>`, the same for every
     * deployment of the same instance of the blueprint (`n` is the number of the instance), whatever the id and the version of the fabric. It
     * survives redeploys and updates, so a block keeps its persistent state here; migrating it on a new version is the job of a processor. Only one
     * running fabric should use it at a time: a block that does says so with an exclusive resource (`OTHER`, label `data`) in its definition, which makes
     * the project be updated by stopping the old fabric first. Data lives on the machine of the engine, not in the repository.
     */
    public val dataDirectory: java.nio.file.Path? get() = null
}

/**
 * Access to the tethers behind a block's ports. The set of ports is fixed by the [BlockDefinition];
 * connections are fixed when the blueprint starts and do not change afterwards.
 */
public interface BlockPorts {
    /**
     * Returns the tether of the plain (non-VarArg) port [name].
     *
     * @throws IllegalArgumentException if the block has no such port or if it is a VarArg port.
     */
    public fun port(name: String): Tether

    /**
     * Returns the tethers of the VarArg port [name] as a list. The size is determined when the blueprint
     * starts, the list is immutable and never changes afterwards.
     *
     * @throws IllegalArgumentException if the block has no such port or if it is not a VarArg port.
     */
    public fun varArgPort(name: String): List<Tether>
}
