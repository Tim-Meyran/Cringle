// SPDX-License-Identifier: Apache-2.0

package cringle.engine.fabric

import cringle.contract.BlockId
import cringle.contract.DriverSet
import cringle.contract.Driver
import cringle.engine.tether.TetherConfig
import cringle.engine.EngineArgs
import cringle.engine.classloading.ContractClassLoader
import cringle.engine.classloading.FabricClassLoaders
import cringle.packaging.Blueprint
import cringle.packaging.ManifestJson
import cringle.packaging.PackageFormatException
import cringle.packaging.PackageValidator
import cringle.packaging.PluginManifest
import cringle.packaging.PluginPackage
import cringle.packaging.ProjectPackage
import cringle.schema.SchemaConflictException
import cringle.schema.SchemaParseException
import cringle.schema.SchemaParser
import cringle.schema.SchemaRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass

/** A plugin version that a fabric loads, with its trust status. */
public data class DeployPlugin(val name: String, val version: String, val trust: PluginTrust)

/** What to deploy: [blueprint] of project [projectName]@[projectVersion] as fabric [fabricId], with exact [plugins]. */
public data class DeployRequest(
    val fabricId: String,
    val projectName: String,
    val projectVersion: String,
    val blueprint: String,
    val plugins: List<DeployPlugin>,
)

/** Turns a [DeployRequest] into a [FabricRuntime] (in state `CREATED`). */
public fun interface FabricDeployer {
    /** Creates the runtime; throws [FabricException] with a readable message if the request cannot be fulfilled. */
    public fun create(request: DeployRequest): FabricRuntime
}

/** Thrown when a fabric id is unknown. */
public class FabricNotFoundException(public val fabricId: String) : RuntimeException("no fabric '$fabricId' on this engine")

/** Owns the fabric instances of one engine. */
public class FabricManager(private val deployer: FabricDeployer) : AutoCloseable {
    private val fabrics = LinkedHashMap<String, FabricRuntime>()

    private fun find(id: String): FabricRuntime = synchronized(fabrics) { fabrics[id] } ?: throw FabricNotFoundException(id)

    /** Creates a fabric from [request]; it is not started. */
    public fun deploy(request: DeployRequest): FabricStatus {
        if (!EngineArgs.idPattern.matches(request.fabricId)) {
            throw FabricException("fabric id '${request.fabricId}' must match ${EngineArgs.idPattern.pattern}")
        }
        synchronized(fabrics) {
            if (request.fabricId in fabrics) throw FabricException("fabric '${request.fabricId}' already exists")
            val runtime = deployer.create(request)
            fabrics[request.fabricId] = runtime
            return runtime.status.value
        }
    }

    /** Starts fabric [id]. */
    public suspend fun start(id: String): FabricStatus = find(id).let {
        it.start()
        it.status.value
    }

    /** Stops fabric [id]. */
    public suspend fun stop(id: String): FabricStatus = find(id).let {
        it.stop()
        it.status.value
    }

    /** Stops and removes fabric [id], releasing its class loaders. */
    public fun remove(id: String) {
        val runtime = synchronized(fabrics) { fabrics.remove(id) } ?: throw FabricNotFoundException(id)
        runtime.close()
    }

    /** Status of fabric [id]. */
    public fun status(id: String): FabricStatus = find(id).status.value

    /** Status of all fabrics in deployment order. */
    public fun list(): List<FabricStatus> = synchronized(fabrics) { fabrics.values.map { it.status.value } }

    /** Stops and removes all fabrics. */
    override fun close() {
        val all = synchronized(fabrics) { fabrics.values.toList().also { fabrics.clear() } }
        all.forEach { runCatching { it.close() } }
    }
}

/** A driver set without drivers, used until built-in drivers exist (#11). */
public object EmptyDriverFactory : DriverFactory {
    override fun driversFor(blockId: BlockId, requiredDrivers: List<String>): DriverSet = object : DriverSet {
        override fun <T : Driver> get(type: KClass<T>): T = throw IllegalArgumentException("No driver registered for ${type.simpleName}")
    }
}

/**
 * Deploys from packages that are already unpacked in the Cringle home (Architecture 15.1):
 * `<home>/projects/<name>/<version>/` and `<home>/plugins/<name>/<version>/`. Downloading and unpacking is #17.
 */
public class LocalFabricDeployer(
    private val home: Path,
    private val engineDir: Path,
    private val drivers: DriverFactory = EmptyDriverFactory,
    private val wiring: PortWiring = UnconnectedPorts,
    /** In-process tether settings; the schema registry of the deployment is filled in. `null` uses [wiring] instead. */
    private val tethers: TetherConfig? = TetherConfig(),
    private val contract: ContractClassLoader = ContractClassLoader(),
    private val defaultRestart: RestartPolicy = RestartPolicy(),
    private val watchdog: WatchdogConfig = WatchdogConfig(),
) : FabricDeployer {
    private class LoadedPlugin(val root: Path, val manifest: PluginManifest, val schemas: Map<String, String>, val trust: PluginTrust)

    override fun create(request: DeployRequest): FabricRuntime {
        val projectRoot = home.resolve("projects").resolve(request.projectName).resolve(request.projectVersion)
        val projectManifest = try {
            ManifestJson.parseProject(read(projectRoot.resolve("cringle-project.json"), "project ${request.projectName}@${request.projectVersion}"))
        } catch (e: PackageFormatException) {
            throw FabricException("invalid project ${request.projectName}@${request.projectVersion}: ${e.message}", e)
        }
        if (projectManifest.name != request.projectName || projectManifest.version != request.projectVersion) {
            throw FabricException("project directory ${request.projectName}@${request.projectVersion} contains ${projectManifest.name}@${projectManifest.version}")
        }
        val blueprints = try {
            projectManifest.blueprints.map { ManifestJson.parseBlueprint(read(projectRoot.resolve(it), it), it) }
        } catch (e: PackageFormatException) {
            throw FabricException("invalid blueprint in project ${request.projectName}: ${e.message}", e)
        }
        val blueprint = blueprints.firstOrNull { it.name == request.blueprint }
            ?: throw FabricException("project ${request.projectName}@${request.projectVersion} has no blueprint '${request.blueprint}' (has: ${blueprints.joinToString { it.name }})")
        val projectSchemas = projectManifest.schemas.associateWith { read(projectRoot.resolve(it), it) }

        val plugins = request.plugins.map { load(it) }
        val duplicate = plugins.groupingBy { it.manifest.name }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicate != null) throw FabricException("plugin '${duplicate.key}' is listed more than once; only one version per plugin can be loaded")

        val pluginPackages = plugins.map { PluginPackage(it.manifest, it.schemas, emptyList()) }
        val problems = ArrayList<String>()
        PackageValidator.validateProject(ProjectPackage(projectManifest, blueprints, projectSchemas, emptyList()), pluginPackages)
            .forEach { problems += "${it.path}: ${it.message}" }
        pluginPackages.forEach { p ->
            PackageValidator.validatePlugin(p, pluginPackages - p).forEach { problems += "plugin ${p.manifest.name}: ${it.path}: ${it.message}" }
        }
        if (problems.isNotEmpty()) throw FabricException("cannot deploy ${request.blueprint} of ${request.projectName}:\n" + problems.joinToString("\n") { "  $it" })

        val registry = SchemaRegistry()
        try {
            for ((entry, text) in projectSchemas) registry.add(SchemaParser.parse(text, "${request.projectName}:$entry"))
            for (p in plugins) for ((entry, text) in p.schemas) registry.add(SchemaParser.parse(text, "${p.manifest.name}:$entry"))
        } catch (e: SchemaParseException) {
            throw FabricException("invalid schema: ${e.message}", e)
        } catch (e: SchemaConflictException) {
            throw FabricException("invalid schema: ${e.message}", e)
        }

        val loaders = FabricClassLoaders(contract)
        try {
            val blocks = HashMap<String, ResolvedBlock>()
            for (p in plugins) {
                val providers = loaders.openProviders("${p.manifest.name}@${p.manifest.version}", p.root, p.manifest)
                for (definition in p.manifest.blocks) {
                    val provider = providers.firstOrNull { pr -> pr.definitions.any { it.name == definition.name } }
                        ?: throw FabricException("no provider of plugin '${p.manifest.name}' creates block '${definition.name}'")
                    blocks["${p.manifest.name}/${definition.name}"] = ResolvedBlock(provider, definition, p.trust)
                }
            }
            val paths = FabricPaths(engineDir, request.fabricId)
            return FabricRuntime(
                FabricSpec(
                    id = request.fabricId,
                    blueprint = blueprint,
                    resolver = BlockResolver { blocks[it] },
                    drivers = drivers,
                    paths = paths,
                    wiring = wiring,
                    tethers = tethers?.let { TetherConfig(registry, it.bufferCapacity, it.requestTimeout, it.observer, it.interceptor) },
                    defaultRestart = defaultRestart,
                    schemas = registry,
                    logger = paths.fileLogger(),
                    watchdog = watchdog,
                    onClose = loaders,
                ),
            )
        } catch (e: Throwable) {
            loaders.close()
            throw if (e is FabricException) e else FabricException("cannot load plugins of ${request.blueprint}: ${e.message}", e)
        }
    }

    private fun load(plugin: DeployPlugin): LoadedPlugin {
        val root = home.resolve("plugins").resolve(plugin.name).resolve(plugin.version)
        val label = "plugin ${plugin.name}@${plugin.version}"
        val manifest = try {
            ManifestJson.parsePlugin(read(root.resolve("cringle-plugin.json"), label))
        } catch (e: PackageFormatException) {
            throw FabricException("invalid $label: ${e.message}", e)
        }
        if (manifest.name != plugin.name || manifest.version != plugin.version) {
            throw FabricException("$label directory contains ${manifest.name}@${manifest.version}")
        }
        return LoadedPlugin(root, manifest, manifest.schemas.associateWith { read(root.resolve(it), it) }, plugin.trust)
    }

    private fun read(file: Path, what: String): String {
        if (!Files.isRegularFile(file)) throw FabricException("$what is not unpacked in the Cringle home (missing $file)")
        return Files.readString(file)
    }
}
