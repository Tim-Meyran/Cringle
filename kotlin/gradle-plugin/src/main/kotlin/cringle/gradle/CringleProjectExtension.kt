// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import cringle.packaging.FabricConfig
import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * The `cringle { }` block of a project that builds a project package. It describes the metadata that goes into
 * `cringle-project.json` and where the blueprints, schemas and binaries come from.
 *
 * `name` defaults to the project name, `version` to the project version. Everything else starts empty; the
 * [blueprints], [schemas] and [binaries] folders default to the three folders below `src/main/cringle` and are read
 * only if they exist.
 *
 * The dependencies are not resolved by the build: a project names them with a version range, and the deploy resolves
 * them against the Cringle repository (chapter 8.4). Applying this plugin therefore adds no dependency to the build.
 */
public abstract class CringleProjectExtension @Inject constructor(objects: ObjectFactory) {
    /** The package name; must match the grammar of `spec/package-format.md`. */
    public val name: Property<String> = objects.property(String::class.java)

    /** The package version, `MAJOR.MINOR.PATCH[-prerelease]`. */
    public val version: Property<String> = objects.property(String::class.java)

    /** Dependencies on other packages, as name to npm-style version range, resolved at deploy time. */
    public val dependencies: MapProperty<String, String> = objects.mapProperty(String::class.java, String::class.java)

    /** Folder with the blueprint documents; every `*.json` directly below it becomes a `blueprints/` entry. */
    public val blueprints: DirectoryProperty = objects.directoryProperty()

    /** Folder with the schema documents; every `*.json` directly below it becomes a `schemas/` entry. */
    public val schemas: DirectoryProperty = objects.directoryProperty()

    /** Folder with static resources; its whole content becomes `binaries/` entries. */
    public val binaries: DirectoryProperty = objects.directoryProperty()

    private val fabricSpecs: MutableList<FabricSpec> = ArrayList()

    /** The fabric configs, in the order they were declared. */
    public val fabrics: List<FabricConfig>
        get() = fabricSpecs.map { it.build() }

    private val publishSpec: PublishSpec = objects.newInstance(PublishSpec::class.java)

    /**
     * Declares where `cringlePublish` publishes to, as `publish { server = "host:port" }`. The token is not part of
     * the block: it comes from `CRINGLE_TOKEN` or from the profile of `cringle login`, never from a build script.
     */
    public fun publish(action: Action<in PublishSpec>) {
        action.execute(publishSpec)
    }

    /** The block the [publish] method writes into, which the plugin hands to the task. */
    internal val publishSettings: PublishSpec get() = publishSpec

    /** Declares a dependency on [name] that accepts the npm-style range [range]. */
    public fun dependency(name: String, range: String) {
        dependencies.put(name, range)
    }

    /**
     * Declares a fabric that runs copies of the blueprint [blueprint] on the engines that have all roles and all labels
     * the block sets. A fabric never names an engine, which is what makes a project portable.
     */
    public fun fabric(blueprint: String, action: Action<in FabricSpec>) {
        val spec = FabricSpec(blueprint)
        action.execute(spec)
        fabricSpecs += spec
    }
}

/**
 * Collects the parts of one fabric config before it is built. The properties are plain values, so the `cringle { }`
 * block reads `fabric("orders") { instances = 2; roles = listOf("edge") }`.
 */
public class FabricSpec(internal val blueprint: String) {
    /** How many copies of the blueprint run; at least 1. */
    public var instances: Int = 1

    /** The logical roles an engine has to have. */
    public var roles: List<String> = emptyList()

    /** The labels an engine has to have, as label to value. */
    public var labels: Map<String, String> = emptyMap()

    internal fun build(): FabricConfig = FabricConfig(blueprint, instances, roles, labels)
}
