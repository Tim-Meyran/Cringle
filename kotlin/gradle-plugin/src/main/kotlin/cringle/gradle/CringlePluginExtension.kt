// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.contract.BlockDefinition
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * The `cringle { }` block of a plugin project. It describes the metadata that goes into `cringle-plugin.json` and
 * where the schemas and binaries come from.
 *
 * `name` defaults to the project name, `version` to the project version. Everything else starts empty; the
 * [schemas] and [binaries] folders default to `src/main/cringle/schemas` and `src/main/cringle/binaries` and are
 * read only if they exist.
 */
public abstract class CringlePluginExtension @Inject constructor(objects: ObjectFactory) {
    /** The package name; must match the grammar of `spec/package-format.md`. */
    public val name: Property<String> = objects.property(String::class.java)

    /** The package version, `MAJOR.MINOR.PATCH[-prerelease]`. */
    public val version: Property<String> = objects.property(String::class.java)

    /** Dependencies on other plugins, as name to npm-style version range. */
    public val dependencies: MapProperty<String, String> = objects.mapProperty(String::class.java, String::class.java)

    /** Class names of the block providers this plugin ships. */
    public val providers: ListProperty<String> = objects.listProperty(String::class.java)

    /** Class names of the drivers this plugin ships. */
    public val drivers: ListProperty<String> = objects.listProperty(String::class.java)

    /** Folder with the schema documents; every `*.json` directly below it becomes a `schemas/` entry. */
    public val schemas: DirectoryProperty = objects.directoryProperty()

    /** Folder with static resources; its whole content becomes `binaries/` entries. */
    public val binaries: DirectoryProperty = objects.directoryProperty()

    private val blockSpecs: MutableList<BlockSpec> = ArrayList()

    /** The block definitions, in the order they were declared. */
    public val blocks: List<BlockDefinition>
        get() = blockSpecs.map { it.build() }

    /** Declares a dependency on [name] that accepts the npm-style range [range]. */
    public fun dependency(name: String, range: String) {
        dependencies.put(name, range)
    }

    /** Declares the block provider class [className]. */
    public fun provider(className: String) {
        providers.add(className)
    }

    /** Declares the driver class [className]. */
    public fun driver(className: String) {
        drivers.add(className)
    }

    /**
     * Declares a block called [name]. Block definitions are described inline; reading them from JSON files is not
     * part of this plugin.
     */
    public fun block(name: String, action: Action<in BlockSpec>) {
        val spec = BlockSpec(name)
        action.execute(spec)
        blockSpecs += spec
    }
}

/** Collects the parts of one [BlockDefinition] before it is built. */
public class BlockSpec(internal val name: String) {
    private val schemaRefs: MutableList<String> = ArrayList()
    private val portSpecs: MutableList<PortSpec> = ArrayList()
    private val driverIds: MutableList<String> = ArrayList()
    private var configRef: String? = null

    /** Declares that this block defines or uses the schema [ref], written as `namespace/Name`. */
    public fun schema(ref: String) {
        schemaRefs += ref
    }

    /** Declares the schema [ref] of the block configuration, written as `namespace/Name`. */
    public fun configSchema(ref: String) {
        configRef = ref
    }

    /** Declares that this block needs the driver with the id [id]. */
    public fun requiredDriver(id: String) {
        driverIds += id
    }

    /**
     * Declares a port. [direction] says which way it faces, [schema] is the type that passes through it and
     * [types] are the tether types it supports; a port needs at least one.
     */
    public fun port(
        name: String,
        direction: PortDirection,
        schema: String,
        vararg types: TetherType,
        varArg: Boolean = false,
    ) {
        portSpecs += PortSpec(name, direction, schema, types.toList(), varArg)
    }

    internal fun build(): BlockDefinition {
        if (portSpecs.map { it.name }.distinct().size != portSpecs.size) {
            throw GradleException("block '$name' declares more than one port with the same name")
        }
        if (driverIds.distinct().size != driverIds.size) {
            throw GradleException("block '$name' requires the same driver id more than once")
        }
        return BlockDefinition(
            name = name,
            schemas = schemaRefs.map { parseRef(it) },
            ports = portSpecs.map {
                if (it.types.isEmpty()) {
                    throw GradleException("port '${it.name}' of block '$name' must support at least one tether type")
                }
                PortDefinition(it.name, it.direction, it.types.toSet(), parseRef(it.schema), it.varArg)
            },
            requiredDrivers = driverIds.toList(),
            configSchema = configRef?.let { parseRef(it) },
        )
    }

    private fun parseRef(text: String): SchemaRef = try {
        SchemaRef.parse(text)
    } catch (e: IllegalArgumentException) {
        throw GradleException("block '$name': ${e.message}", e)
    }

    private class PortSpec(
        val name: String,
        val direction: PortDirection,
        val schema: String,
        val types: List<TetherType>,
        val varArg: Boolean,
    )
}
