// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import cringle.contract.BlockDefinition
import cringle.contract.IsolationLevel
import cringle.contract.TetherType
import kotlinx.serialization.json.JsonObject

/** Thrown when a package file (manifest, blueprint, block definition) is not valid. [path] is a JSON path or ZIP entry. */
public class PackageFormatException(public val path: String, problem: String) : RuntimeException("$path: $problem")

/** One finding of a validator: where ([path]) and what ([message]). */
public data class PackageProblem(public val path: String, public val message: String)

/** The two kinds of package. */
public enum class PackageKind(public val manifestFile: String, public val jsonName: String) {
    /** A project package (`cringle-project.json`). */
    PROJECT("cringle-project.json", "project"),

    /** A plugin package (`cringle-plugin.json`). */
    PLUGIN("cringle-plugin.json", "plugin"),
}

/** Optional plugin processors that migrate persisted state on update or downgrade; values are class names. */
public data class ProcessorSet(public val update: String? = null, public val downgrade: String? = null)

/**
 * Deployment instruction: run [instances] copies of the blueprint named [blueprint] on engines that have all
 * [roles] and all [labels]. Engines are never referenced by ID.
 */
public data class FabricConfig(
    public val blueprint: String,
    public val instances: Int,
    public val roles: List<String>,
    public val labels: Map<String, String>,
)

/** Manifest of a plugin package. Version ranges in [dependencies] are opaque strings here (parsed by #6). */
public data class PluginManifest(
    public val name: String,
    public val version: String,
    public val dependencies: Map<String, String> = emptyMap(),
    public val providers: List<String> = emptyList(),
    public val drivers: List<String> = emptyList(),
    public val blocks: List<BlockDefinition> = emptyList(),
    public val libs: List<String> = emptyList(),
    public val schemas: List<String> = emptyList(),
    public val processors: ProcessorSet = ProcessorSet(),
)

/** Manifest of a project package. */
public data class ProjectManifest(
    public val name: String,
    public val version: String,
    public val dependencies: Map<String, String> = emptyMap(),
    public val blueprints: List<String> = emptyList(),
    public val schemas: List<String> = emptyList(),
    public val fabrics: List<FabricConfig> = emptyList(),
)

/** One end of a tether. [index] selects the slot of a VarArg port and must be `null` for plain ports. */
public data class Endpoint(public val block: String, public val port: String, public val index: Int? = null)

/** A tether of fixed [type] from an OUT port to an IN port. */
public data class TetherDef(public val type: TetherType, public val from: Endpoint, public val to: Endpoint)

/**
 * A block instance in a blueprint. [block] is `pluginName/blockName`; [varArgCounts] gives the fixed size of every
 * VarArg port of the block.
 */
public data class BlueprintBlock(
    public val id: String,
    public val block: String,
    public val config: JsonObject = JsonObject(emptyMap()),
    public val isolation: IsolationLevel = IsolationLevel.SHARED,
    public val varArgCounts: Map<String, Int> = emptyMap(),
)

/** A blueprint. It has no version of its own and no engine placement; it always runs in one engine. */
public data class Blueprint(
    public val name: String,
    public val blocks: List<BlueprintBlock>,
    public val tethers: List<TetherDef>,
)

/** A read project package. [schemas] maps ZIP entry to schema text; [files] lists every entry name, sorted. */
public data class ProjectPackage(
    public val manifest: ProjectManifest,
    public val blueprints: List<Blueprint>,
    public val schemas: Map<String, String>,
    public val files: List<String>,
)

/** A read plugin package. [schemas] maps ZIP entry to schema text; [files] lists every entry name, sorted. */
public data class PluginPackage(
    public val manifest: PluginManifest,
    public val schemas: Map<String, String>,
    public val files: List<String>,
)
