// SPDX-License-Identifier: Apache-2.0

package cringle.engine.fabric

import cringle.contract.BlockId
import cringle.contract.DriverSet
import cringle.contract.MigrationScope
import cringle.contract.Driver
import cringle.engine.drivers.BuiltinDrivers
import cringle.engine.tether.TetherConfig
import cringle.engine.tether.TetherInterceptor
import cringle.engine.tether.TetherObserver
import cringle.engine.EngineArgs
import cringle.engine.classloading.ContractClassLoader
import cringle.engine.classloading.FabricClassLoaders
import cringle.engine.tether.RemoteTetherDriver
import cringle.packaging.Blueprint
import cringle.packaging.ManifestJson
import cringle.packaging.PackageFormatException
import cringle.packaging.PackageNames
import cringle.packaging.PackageValidator
import cringle.packaging.PluginManifest
import cringle.packaging.PluginPackage
import cringle.packaging.RemoteEndpoint
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

/** The concrete far end of the service [service] for the service tethers of a fabric (#178); see `ServiceBinding` in the engine API. */
public data class ServiceBinding(
    val service: String,
    val fabric: String,
    val block: String,
    val port: String,
    val fingerprint: String,
    /** Further instances of the service in the order of preference (#173); their own fallbacks are ignored. */
    val fallbacks: List<ServiceBinding> = emptyList(),
) {
    /** The far end of the tether: no address (the registry finds the engine), the fallbacks as alternatives. */
    internal fun toRemote(): RemoteEndpoint = RemoteEndpoint(
        null, fingerprint, fabric, block, port, null,
        fallbacks.map { RemoteEndpoint(null, it.fingerprint, it.fabric, it.block, it.port, null, emptyList(), service) },
        service,
    )

    /** What is wrong with this end itself (not with the fallbacks). */
    internal fun problems(): List<String> = buildList {
        if (!Regex("[0-9a-f]{64}").matches(fingerprint)) add("invalid fingerprint '$fingerprint' of the service '$service': expected 64 lowercase hex characters")
        for (name in listOf(fabric, block, port)) PackageNames.identifierProblem(name)?.let { add("service '$service': $it") }
    }

    /** What is wrong with this end or with one of its fallbacks. */
    internal fun allProblems(): List<String> = problems() + fallbacks.flatMap { it.problems() }
}

/** What to deploy: [blueprint] of project [projectName]@[projectVersion] as fabric [fabricId], with exact [plugins]. */
public data class DeployRequest(
    val fabricId: String,
    val projectName: String,
    val projectVersion: String,
    val blueprint: String,
    val plugins: List<DeployPlugin>,
    /** Public key fingerprints of the engines that may call the provided service ports of the blueprint (#177). */
    val serviceCallers: List<String> = emptyList(),
    /** The concrete far ends of the service tethers of the blueprint (#178). */
    val serviceBindings: List<ServiceBinding> = emptyList(),
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

    /** Switches the recording of all typed tethers of the fabric [id] on or off (#193). */
    public fun setRecording(id: String, all: Boolean, default: cringle.packaging.RecordConfig?) {
        find(id).setRecording(all, default)
    }

    /** Sets or removes a breakpoint on a tether of the fabric [id] (#323). */
    public fun setBreakpoint(id: String, tether: String, enabled: Boolean): Unit = find(id).setBreakpoint(tether, enabled)

    /** The breakpoints of the fabric [id] and the values held at them. */
    public fun debugState(id: String): FabricDebugger.State = find(id).debugState()

    /** Releases the values held at the breakpoints of the fabric [id]; returns how many. */
    public fun resume(id: String, tether: String?, one: Boolean): Int = find(id).resume(tether, one)

    /** Replaces the instances of services that the fabric [id] calls, without a redeploy (see [FabricRuntime.updateServiceBindings]). */
    public fun updateServiceBindings(id: String, bindings: List<ServiceBinding>) {
        bindings.flatMap { it.allProblems() }.firstOrNull()?.let { throw FabricException(it) }
        find(id).updateServiceBindings(bindings.associate { it.service to it.toRemote() })
    }

    /** Replaces the engines that may call the provided service ports of the fabric [id] (see [FabricRuntime.setServiceCallers]). */
    public fun setServiceCallers(id: String, fingerprints: List<String>) {
        find(id).setServiceCallers(fingerprints)
    }

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
    public fun stats(): List<cringle.engine.metrics.FabricStats> = synchronized(fabrics) { fabrics.values.toList() }.map { it.stats() }

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
    /** Built-in drivers of the engine; when set, they replace [drivers] (bound to each deployed fabric). */
    private val builtin: BuiltinDrivers? = null,
    private val wiring: PortWiring = UnconnectedPorts,
    /** In-process tether settings; the schema registry of the deployment is filled in. `null` uses [wiring] instead. */
    private val tethers: TetherConfig? = TetherConfig(),
    private val contract: ContractClassLoader = ContractClassLoader(),
    private val defaultRestart: RestartPolicy = RestartPolicy(),
    private val watchdog: WatchdogConfig = WatchdogConfig(),
    /** Runs the tethers that end on another engine; `null` rejects a blueprint that has one. */
    private val remoteTethers: RemoteTetherDriver? = null,
) : FabricDeployer {
    private class LoadedPlugin(val root: Path, val manifest: PluginManifest, val schemas: Map<String, String>, val trust: PluginTrust)

    override fun create(request: DeployRequest): FabricRuntime {
        val projectRoot = home.resolve("projects")
            .resolve(nameOf(request.projectName, "project name"))
            .resolve(versionOf(request.projectVersion, "project version"))
        val projectManifest = try {
            ManifestJson.parseProject(read(projectRoot.resolve("cringle-project.json"), "project ${request.projectName}@${request.projectVersion}"))
        } catch (e: PackageFormatException) {
            throw FabricException("invalid project ${request.projectName}@${request.projectVersion}: ${e.message}", e)
        }
        if (projectManifest.name != request.projectName || projectManifest.version != request.projectVersion) {
            throw FabricException("project directory ${request.projectName}@${request.projectVersion} contains ${projectManifest.name}@${projectManifest.version}")
        }
        val parsed = try {
            projectManifest.blueprints.map { ManifestJson.parseBlueprint(read(projectRoot.resolve(it), it), it) }
        } catch (e: PackageFormatException) {
            throw FabricException("invalid blueprint in project ${request.projectName}: ${e.message}", e)
        }
        val blueprints = parsed.map { if (it.name == request.blueprint) it.bindServices(request.serviceBindings) else it }
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
            val dataBase = dataBaseOf(request)
            val paths = FabricPaths(engineDir, request.fabricId, dataBase)
            val migrations = dataBase?.let { base ->
                val providerIds = plugins.map { "${it.manifest.name}@${it.manifest.version}" }
                val units = ArrayList<MigrationUnit>()
                for (b in blueprint.blocks) {
                    val plugin = plugins.firstOrNull { it.manifest.name == b.block.substringBefore('/') } ?: continue
                    val id = "${plugin.manifest.name}@${plugin.manifest.version}"
                    units += MigrationUnit(
                        MigrationScope.PLUGIN, b.id, plugin.manifest.name, plugin.manifest.version, base.resolve(b.id), plugin.manifest.processors,
                    ) { loaders.openProcessor(listOf(id), it) }
                }
                units += MigrationUnit(
                    MigrationScope.PROJECT, null, projectManifest.name, projectManifest.version, base, projectManifest.processors,
                ) { loaders.openProcessor(providerIds, it) }
                DataMigrations(base, units)
            }
            val recorder = builtin?.let { cringle.engine.dwh.TetherRecorder(it.dwh, request.fabricId) { message -> paths.fileLogger().log(FabricLogger.Level.WARN, message) } }
            val debugger = tethers?.let { FabricDebugger() }
            return FabricRuntime(
                FabricSpec(
                    id = request.fabricId,
                    blueprint = blueprint,
                    resolver = BlockResolver { blocks[it] },
                    drivers = builtin?.factoryFor(request.fabricId, paths) ?: drivers,
                    paths = paths,
                    wiring = wiring,
                    tethers = tethers?.let {
                        TetherConfig(
                            registry, it.bufferCapacity, it.requestTimeout, listOfNotNull(it.observer, recorder).let { o -> if (o.size < 2) o.firstOrNull() else TetherObserver { t, k, p -> o.forEach { x -> x.observe(t, k, p) } } },
                            listOfNotNull(it.interceptor, debugger).let { i -> if (i.size < 2) i.firstOrNull() else TetherInterceptor { t, k, p -> i.forEach { x -> x.beforeDelivery(t, k, p) } } },
                            it.tcp ?: builtin?.let { b -> { block: String -> b.tcp.driverFor(request.fabricId, block) } },
                            it.serial ?: builtin?.let { b -> { block: String -> b.serial.driverFor(request.fabricId, block) } },
                            remote = remoteTethers?.portsFor(request.fabricId),
                            serviceCallers = request.serviceCallers,
                        )
                    },
                    defaultRestart = defaultRestart,
                    schemas = registry,
                    logger = paths.fileLogger(),
                    watchdog = watchdog,
                    onClose = loaders,
                    recorder = recorder,
                    debugger = debugger,
                    migrations = migrations,
                ),
            )
        } catch (e: Throwable) {
            loaders.close()
            throw if (e is FabricException) e else FabricException("cannot load plugins of ${request.blueprint}: ${e.message}", e)
        }
    }

    /**
     * `<home>/data/<project>/<blueprint>/<n>`: where the blocks of this instance keep their data, the same for every id and version of the fabric
     * (`n` is the number at the end of the id, without the color of a Blue-Green update). `null` if the request names no project.
     */
    private fun dataBaseOf(request: DeployRequest): java.nio.file.Path? {
        val project = request.projectName
        if (project.isEmpty()) return null
        val instance = Regex("-(\\d+)b?$").find(request.fabricId)?.groupValues?.get(1) ?: "0"
        val root = home.resolve("data")
        val path = root.resolve(project).resolve(request.blueprint).resolve(instance).normalize()
        if (!path.startsWith(root.normalize())) throw FabricException("the data folder of '$project/${request.blueprint}' would leave $root")
        return path
    }

    private fun load(plugin: DeployPlugin): LoadedPlugin {
        val label = "plugin ${plugin.name}@${plugin.version}"
        val root = home.resolve("plugins")
            .resolve(nameOf(plugin.name, "plugin name"))
            .resolve(versionOf(plugin.version, "plugin version"))
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

    /** The names and versions of a deploy request become directories of the Cringle home, so they are checked first. */
    private fun nameOf(value: String, what: String): String = checked(value, what) { PackageNames.nameProblem(it) }

    /** @see nameOf */
    private fun versionOf(value: String, what: String): String = checked(value, what) { PackageNames.versionProblem(it) }

    private inline fun checked(value: String, what: String, problem: (String) -> String?): String {
        val p = problem(value)
        if (p != null) throw FabricException("$what: $p")
        return value
    }

    private fun read(file: Path, what: String): String {
        if (!Files.isRegularFile(file)) throw FabricException("$what is not unpacked in the Cringle home (missing $file)")
        return Files.readString(file)
    }
}

/** This blueprint with the `service` of every service tether replaced by the `remote` of the binding of that service (#178). */
internal fun Blueprint.bindServices(bindings: List<ServiceBinding>): Blueprint {
    bindings.groupingBy { it.service }.eachCount().entries.firstOrNull { it.value > 1 }
        ?.let { throw FabricException("service '${it.key}' is bound more than once") }
    if (bindings.isEmpty()) return this
    // the validators look at the primary end; the fallbacks are checked here
    bindings.flatMap { b -> b.fallbacks.flatMap { it.problems() } }.firstOrNull()?.let { throw FabricException(it) }
    val byService = bindings.associateBy { it.service }
    return copy(
        tethers = tethers.map { t ->
            val binding = t.service?.let { byService[it] } ?: return@map t
            t.copy(remote = binding.toRemote(), service = null)
        },
    )
}
