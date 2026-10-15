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
 * @property typeParam the name of the [TypeParameter] of the block that gives the type of this port, or `null` for a fixed [schema]. For such a port
 * [schema] is the bound of the parameter; a blueprint binds the parameter per block ([BlockDefinition.bind]).
 * @property responseSchema for a port with [TetherType.REQUEST_RESPONSE]: the schema of the **responses** (a request is checked against [schema]); `null` means the
 * responses have [schema] too. See [response].
 */
public class PortDefinition @JvmOverloads constructor(
    public val name: String,
    public val direction: PortDirection,
    tetherTypes: Set<TetherType>,
    public val schema: SchemaRef,
    public val varArg: Boolean = false,
    public val typeParam: String? = null,
    public val responseSchema: SchemaRef? = null,
) {
    public val tetherTypes: Set<TetherType> = Collections.unmodifiableSet(LinkedHashSet(tetherTypes))

    /** The schema of the responses of a `REQUEST_RESPONSE` port: [responseSchema], else [schema]. */
    public val response: SchemaRef get() = responseSchema ?: schema

    init {
        require(name.isNotBlank()) { "Port name must not be blank" }
        require(tetherTypes.isNotEmpty()) { "Port '$name' must support at least one tether type" }
        require(responseSchema == null || TetherType.REQUEST_RESPONSE in tetherTypes) { "Port '$name' has a response schema but does not support REQUEST_RESPONSE" }
    }

    override fun equals(other: Any?): Boolean =
        other is PortDefinition && name == other.name && direction == other.direction &&
            tetherTypes == other.tetherTypes && schema == other.schema && varArg == other.varArg && typeParam == other.typeParam &&
            responseSchema == other.responseSchema

    override fun hashCode(): Int = listOf(name, direction, tetherTypes, schema, varArg, typeParam, responseSchema).hashCode()

    override fun toString(): String =
        "PortDefinition(name=$name, direction=$direction, tetherTypes=$tetherTypes, schema=$schema, varArg=$varArg, typeParam=$typeParam, responseSchema=$responseSchema)"
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
 * @property typeParameters the type parameters of the block (generics): ports name one with [PortDefinition.typeParam], the blueprint binds it per block
 * instance ([bind]); unbound, a parameter is its bound. Names are unique.
 * @property exclusiveResources what the block holds that only one instance can have at a time (a fixed port, a serial line); a fabric with such a block
 * is updated by stopping the old one before the new one starts, not side by side. No duplicates.
 */
public class BlockDefinition @JvmOverloads constructor(
    public val name: String,
    schemas: List<SchemaRef>,
    ports: List<PortDefinition>,
    requiredDrivers: List<String>,
    public val configSchema: SchemaRef? = null,
    exclusiveResources: List<ExclusiveResource> = emptyList(),
    typeParameters: List<TypeParameter> = emptyList(),
) {
    public val typeParameters: List<TypeParameter> = Collections.unmodifiableList(ArrayList(typeParameters))

    public val exclusiveResources: List<ExclusiveResource> = Collections.unmodifiableList(ArrayList(exclusiveResources))

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
        val duplicateParameter = typeParameters.groupingBy { it.name }.eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicateParameter == null) { "Block '$name' has more than one type parameter named '${duplicateParameter?.key}'" }
        for (p in ports) require(p.typeParam == null || typeParameters.any { it.name == p.typeParam }) { "Port '${p.name}' of block '$name' names the unknown type parameter '${p.typeParam}'" }
        val duplicateResource = exclusiveResources.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicateResource == null) { "Block '$name' declares the exclusive resource ${duplicateResource?.key} more than once" }
    }

    /**
     * The definition of one block instance whose type parameters are bound to [types] (a parameter that is not in [types] stays its bound): the ports have the
     * bound schemas and no [PortDefinition.typeParam]. A definition without type parameters returns itself.
     *
     * @throws IllegalArgumentException if [types] names a parameter the block does not have.
     */
    public fun bind(types: Map<String, SchemaRef>): BlockDefinition {
        val unknown = types.keys.firstOrNull { k -> typeParameters.none { it.name == k } }
        require(unknown == null) { "Block '$name' has no type parameter '$unknown'" }
        if (typeParameters.isEmpty()) return this
        val bound = ports.map { p -> p.typeParam?.let { PortDefinition(p.name, p.direction, p.tetherTypes, types[it] ?: p.schema, p.varArg, null, p.responseSchema) } ?: p }
        return BlockDefinition(name, (schemas + bound.map { it.schema } + bound.mapNotNull { it.responseSchema }).distinct(), bound, requiredDrivers, configSchema, exclusiveResources)
    }

    override fun equals(other: Any?): Boolean =
        other is BlockDefinition && name == other.name && schemas == other.schemas && ports == other.ports &&
            requiredDrivers == other.requiredDrivers && configSchema == other.configSchema && exclusiveResources == other.exclusiveResources &&
            typeParameters == other.typeParameters

    override fun hashCode(): Int = listOf(name, schemas, ports, requiredDrivers, configSchema, exclusiveResources, typeParameters).hashCode()

    override fun toString(): String =
        "BlockDefinition(name=$name, schemas=$schemas, ports=$ports, requiredDrivers=$requiredDrivers, " +
            "configSchema=$configSchema, exclusiveResources=$exclusiveResources, typeParameters=$typeParameters)"
}

/** A type parameter of a block: [name] is an identifier, [bound] the schema every binding must be assignable to (default `cringle.std/Any`). */
public data class TypeParameter(public val name: String, public val bound: SchemaRef = SchemaRef("cringle.std", "Any")) {
    init {
        require(name.isNotBlank()) { "A type parameter needs a name" }
    }
}

/** The kinds of exclusive resource a block can declare. */
public enum class ExclusiveKind {
    /** A fixed network port. */
    PORT,

    /** A serial line. */
    SERIAL,

    /** Any other resource that only one holder can have. */
    OTHER,
}

/** A resource that only one instance of a block can hold at a time; [label] says which (for example `http` or `COM3`), it is not blank. */
public data class ExclusiveResource(public val kind: ExclusiveKind, public val label: String) {
    init {
        require(label.isNotBlank()) { "An exclusive resource needs a label" }
    }

    override fun toString(): String = "$kind '$label'"
}
