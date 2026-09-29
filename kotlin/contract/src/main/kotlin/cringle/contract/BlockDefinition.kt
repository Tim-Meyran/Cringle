// SPDX-License-Identifier: Apache-2.0

package cringle.contract

import java.util.Collections

/**
 * A reference to a schema by its namespace ID, written as `namespace/Name`.
 *
 * This is only a reference. What the schema contains, how it is parsed and how values are validated is the
 * business of the schema system, not of the contract.
 *
 * @property namespace the namespace ID of the schema.
 * @property name the name of the type within the namespace; it contains no `/`.
 */
public data class SchemaRef(public val namespace: String, public val name: String) {
    init {
        require(namespace.isNotBlank()) { "Schema namespace must not be blank" }
        require(name.isNotBlank()) { "Schema name must not be blank" }
        require('/' !in name) { "Schema name must not contain '/': '$name'" }
    }

    /** Returns the reference in the form `namespace/Name`. */
    override fun toString(): String = "$namespace/$name"

    public companion object {
        /**
         * Parses `namespace/Name`, splitting at the last `/`.
         *
         * @throws IllegalArgumentException if [text] has no `/` or if the namespace or the name is blank.
         */
        public fun parse(text: String): SchemaRef {
            val slash = text.lastIndexOf('/')
            require(slash >= 0) { "Schema reference must have the form 'namespace/Name', got '$text'" }
            val namespace = text.substring(0, slash)
            val name = text.substring(slash + 1)
            require(namespace.isNotBlank() && name.isNotBlank()) {
                "Schema reference must have the form 'namespace/Name' with non-blank parts, got '$text'"
            }
            return SchemaRef(namespace, name)
        }
    }
}

/**
 * Which way a port faces. **[Zu bestätigen]** The architecture does not say; the blueprint editor needs it to
 * decide which ports can be connected.
 */
public enum class PortDirection {
    /** The port receives messages, requests or streams. */
    IN,

    /** The port initiates messages, requests or streams. */
    OUT,
}

/**
 * Describes a port of a block: which tether types it supports and which schema it carries.
 *
 * Instances are immutable; the [tetherTypes] set is copied on construction.
 *
 * @property name the port name, unique within the block definition.
 * @property direction which way the port faces.
 * @property tetherTypes the tether types the port supports; the blueprint decides which one is used. Not empty.
 * @property schema the schema of the values that pass through the port.
 * @property varArg whether this is a VarArg port. Its tethers form a list whose size is fixed when the
 * blueprint starts.
 */
public class PortDefinition(
    public val name: String,
    public val direction: PortDirection,
    tetherTypes: Set<TetherType>,
    public val schema: SchemaRef,
    public val varArg: Boolean = false,
) {
    public val tetherTypes: Set<TetherType> = Collections.unmodifiableSet(LinkedHashSet(tetherTypes))

    init {
        require(name.isNotBlank()) { "Port name must not be blank" }
        require(tetherTypes.isNotEmpty()) { "Port '$name' must support at least one tether type" }
    }

    override fun equals(other: Any?): Boolean =
        other is PortDefinition && name == other.name && direction == other.direction &&
            tetherTypes == other.tetherTypes && schema == other.schema && varArg == other.varArg

    override fun hashCode(): Int = listOf(name, direction, tetherTypes, schema, varArg).hashCode()

    override fun toString(): String =
        "PortDefinition(name=$name, direction=$direction, tetherTypes=$tetherTypes, schema=$schema, varArg=$varArg)"
}

/**
 * Describes a block: the metadata the engine reads to know how to create it and what to inject. It mirrors
 * the JSON config of a block (name, schema references, ports, required drivers, config schema); reading and
 * writing that JSON is the business of the packaging module.
 *
 * Instances are immutable; the collections are copied on construction. Blueprint authors work with
 * definitions, but never see the drivers listed in [requiredDrivers].
 *
 * @property name the name of the block, unique within its provider.
 * @property schemas the schemas this block defines or uses.
 * @property ports the ports of the block; their names are unique.
 * @property requiredDrivers the ids ([DriverType.id]) of the drivers the block needs; no duplicates.
 * @property configSchema the schema of the block configuration, or `null` if the block has none.
 */
public class BlockDefinition(
    public val name: String,
    schemas: List<SchemaRef>,
    ports: List<PortDefinition>,
    requiredDrivers: List<String>,
    public val configSchema: SchemaRef? = null,
) {
    public val schemas: List<SchemaRef> = Collections.unmodifiableList(ArrayList(schemas))
    public val ports: List<PortDefinition> = Collections.unmodifiableList(ArrayList(ports))
    public val requiredDrivers: List<String> = Collections.unmodifiableList(ArrayList(requiredDrivers))

    init {
        require(name.isNotBlank()) { "Block name must not be blank" }
        val duplicatePort = ports.groupingBy { it.name }.eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicatePort == null) { "Block '$name' has more than one port named '${duplicatePort?.key}'" }
        require(requiredDrivers.none { it.isBlank() }) { "Block '$name' lists a blank driver id" }
        val duplicateDriver = requiredDrivers.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicateDriver == null) { "Block '$name' requires driver '${duplicateDriver?.key}' more than once" }
    }

    override fun equals(other: Any?): Boolean =
        other is BlockDefinition && name == other.name && schemas == other.schemas && ports == other.ports &&
            requiredDrivers == other.requiredDrivers && configSchema == other.configSchema

    override fun hashCode(): Int = listOf(name, schemas, ports, requiredDrivers, configSchema).hashCode()

    override fun toString(): String =
        "BlockDefinition(name=$name, schemas=$schemas, ports=$ports, requiredDrivers=$requiredDrivers, " +
            "configSchema=$configSchema)"
}
