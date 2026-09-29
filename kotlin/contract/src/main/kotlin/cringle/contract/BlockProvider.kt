// SPDX-License-Identifier: Apache-2.0

package cringle.contract

import kotlin.reflect.KClass

/**
 * Creates block instances and injects their drivers.
 *
 * A plugin contributes one or more providers. The provider describes the blocks it can create with
 * [BlockDefinition]s; the engine reads these definitions to know which drivers it has to hand over.
 *
 * There are no provider lifecycle hooks (for example on loading and unloading). Whether they are needed is an
 * open point of the architecture.
 */
public interface BlockProvider {
    /** The blocks this provider can create. */
    public val definitions: List<BlockDefinition>

    /**
     * Creates a new instance of the block described by the definition named [definitionName].
     *
     * The provider passes the drivers it takes from [drivers] to the block, usually through its constructor.
     * This is a plain function that only instantiates: it must not perform I/O or wait for anything. Anything
     * that takes time belongs into [Block.init] or [Block.start].
     *
     * @throws IllegalArgumentException if this provider has no definition with that name.
     */
    public fun createBlock(definitionName: String, drivers: DriverSet): Block
}

/**
 * The drivers a block was granted. It contains exactly the drivers named in
 * [BlockDefinition.requiredDrivers]; the instances are the engine-wide driver instances.
 */
public interface DriverSet {
    /**
     * Returns the driver of the given interface type.
     *
     * @throws IllegalArgumentException if no such driver was declared in the block definition.
     */
    public operator fun <T : Driver> get(type: KClass<T>): T
}
