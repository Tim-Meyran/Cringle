// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import cringle.contract.BlockDefinition
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import cringle.schema.SchemaConflictException
import cringle.schema.SchemaParseException
import cringle.schema.SchemaParser
import cringle.schema.SchemaRegistry
import cringle.schema.SchemaValidator
import cringle.schema.isAssignable

/** Semantic validation of packages. All problems are collected, none is thrown. */
public object PackageValidator {
    /**
     * Validates [plugin]: block definitions reference only schemas that resolve in the plugin's own schemas, the
     * schemas of [dependencies] and the standard schemas; block names and provider/driver entries are unique.
     */
    public fun validatePlugin(plugin: PluginPackage, dependencies: List<PluginPackage> = emptyList()): List<PackageProblem> {
        val problems = ArrayList<PackageProblem>()
        val registry = registry(listOf(plugin) + dependencies, emptyList(), problems)
        val m = plugin.manifest
        duplicates(m.blocks.map { it.name }, "$.blocks", "block", problems)
        duplicates(m.providers, "$.providers", "provider", problems)
        duplicates(m.drivers, "$.drivers", "driver", problems)
        m.blocks.forEachIndexed { i, block ->
            for (n in listOf(block.name)) {
                if (!PackageNames.name.matches(n)) problems += PackageProblem("$.blocks[$i].name", "invalid block name '$n'")
            }
            problems += schemaRefs(block, "$.blocks[$i]", registry)
        }
        if (m.blocks.isNotEmpty() && m.providers.isEmpty()) {
            problems += PackageProblem("$.providers", "the plugin defines blocks but lists no provider")
        }
        return problems
    }

    /**
     * Validates what a project package says about itself, which is everything that needs no resolved dependency:
     * blueprint names are unique, fabric configs point at blueprints of the project, block ids are unique, and both
     * endpoints of a tether name a block of the same blueprint. A TCP tether needs a port in range, every other type
     * must not carry one.
     *
     * A build checks the project with this, because a project only refers to its dependencies by name and range and
     * resolves them at deploy time. The rules that need the block definitions of those dependencies stay with
     * [validateProject].
     */
    public fun validateProjectSources(project: ProjectPackage): List<PackageProblem> {
        val problems = ArrayList<PackageProblem>()
        duplicates(project.blueprints.map { it.name }, "$.blueprints", "blueprint", problems)
        val known = project.blueprints.map { it.name }.toSet()
        project.manifest.fabrics.forEachIndexed { i, f ->
            if (f.blueprint !in known) problems += PackageProblem("$.fabrics[$i].blueprint", "unknown blueprint '${f.blueprint}'")
        }
        for (blueprint in project.blueprints) {
            val file = "blueprints/${blueprint.name}.json"
            val report: (String, String) -> Unit = { path, message -> problems.add(PackageProblem("$file $path", message)) }
            val ids = blockIds(blueprint, report)
            blueprint.tethers.forEachIndexed { i, t ->
                val path = "$.tethers[$i]"
                for ((side, endpoint) in listOf("from" to t.from, "to" to t.to)) {
                    if (endpoint.block !in ids) report("$path.$side.block", "unknown block id '${endpoint.block}'")
                }
                tetherRules(t, path, report)
            }
        }
        return problems
    }

    /**
     * Validates [project] against the already resolved [plugins] (resolution is not done here): fabric configs point at
     * existing blueprints, and every blueprint is consistent with the block definitions it uses.
     */
    public fun validateProject(project: ProjectPackage, plugins: List<PluginPackage>): List<PackageProblem> {
        val problems = ArrayList<PackageProblem>()
        val registry = registry(plugins, listOf(project.schemas to "${project.manifest.name}@${project.manifest.version}"), problems)
        duplicates(project.blueprints.map { it.name }, "$.blueprints", "blueprint", problems)
        val known = project.blueprints.map { it.name }.toSet()
        project.manifest.fabrics.forEachIndexed { i, f ->
            if (f.blueprint !in known) problems += PackageProblem("$.fabrics[$i].blueprint", "unknown blueprint '${f.blueprint}'")
        }
        val lookup = HashMap<String, BlockDefinition>()
        for (p in plugins) for (b in p.manifest.blocks) lookup["${p.manifest.name}/${b.name}"] = b
        for (blueprint in project.blueprints) {
            problems += validateBlueprint(blueprint, lookup::get, registry, "blueprints/${blueprint.name}.json")
        }
        return problems
    }

    /** Validates one [blueprint]; [block] resolves `pluginName/blockName` to a definition. Paths start with [file]. */
    public fun validateBlueprint(
        blueprint: Blueprint,
        block: (String) -> BlockDefinition?,
        registry: SchemaRegistry,
        file: String = blueprint.name,
    ): List<PackageProblem> {
        val problems = ArrayList<PackageProblem>()
        fun problem(path: String, message: String) = problems.add(PackageProblem("$file $path", message))
        blockIds(blueprint) { path, message -> problem(path, message) }
        val instances = HashMap<String, Pair<BlueprintBlock, BlockDefinition>>()
        val unresolved = HashSet<String>()
        blueprint.blocks.forEachIndexed { i, b ->
            val path = "$.blocks[$i]"
            val definition = block(b.block)
            if (definition == null) {
                problem("$path.block", "unknown block '${b.block}'")
                unresolved += b.id
                return@forEachIndexed
            }
            instances.putIfAbsent(b.id, b to definition)
            val varArgPorts = definition.ports.filter { it.varArg }.map { it.name }.toSet()
            for (name in varArgPorts) {
                if (name !in b.varArgCounts) problem("$path.varArgCounts", "missing size of VarArg port '$name'")
            }
            for (name in b.varArgCounts.keys) {
                if (name !in varArgPorts) problem("$path.varArgCounts.$name", "'$name' is not a VarArg port of '${b.block}'")
            }
            val configSchema = definition.configSchema
            if (configSchema != null) {
                for (e in SchemaValidator(registry).validate(b.config, configSchema)) {
                    problem("$path.config${e.path.removePrefix("$")}", e.message)
                }
            } else if (b.config.isNotEmpty()) {
                problem("$path.config", "'${b.block}' takes no configuration")
            }
        }
        blueprint.tethers.forEachIndexed { i, t ->
            val path = "$.tethers[$i]"
            val from = endpoint(t.from, "$path.from", instances, unresolved, PortDirection.OUT, ::problem)
            val to = endpoint(t.to, "$path.to", instances, unresolved, PortDirection.IN, ::problem)
            if (from != null && t.type !in from.tetherTypes) problem("$path.type", "port '${t.from.port}' does not support ${t.type}")
            if (to != null && t.type !in to.tetherTypes) problem("$path.type", "port '${t.to.port}' does not support ${t.type}")
            tetherRules(t, path) { path, message -> problem(path, message) }
            if (from != null && to != null && !isAssignable(from.schema, to.schema, registry)) {
                problem(path, "schema ${from.schema} of '${t.from.port}' is not assignable to ${to.schema} of '${t.to.port}'")
            }
        }
        return problems
    }

    /**
     * The block ids of [blueprint], reported to [problem] if one of them is declared twice. The path of a finding is
     * `$.blocks[<i>].id`, so it is relative to whatever the caller puts in front of it.
     */
    private fun blockIds(blueprint: Blueprint, problem: (String, String) -> Unit): Set<String> {
        val ids = LinkedHashSet<String>()
        blueprint.blocks.forEachIndexed { i, b ->
            if (!ids.add(b.id)) problem("$.blocks[$i].id", "duplicate block id '${b.id}'")
        }
        return ids
    }

    /**
     * The rules a tether has on its own, without knowing its ports: only a TCP tether has a port, and it needs one in
     * range and never buffers. Shared by the validators that resolve the ports and the ones that do not, so both
     * report the same wording.
     */
    private fun tetherRules(t: TetherDef, path: String, problem: (String, String) -> Unit) {
        if (t.type == TetherType.TCP) {
            if (t.port == null || t.port !in 1..65535) problem("$path.port", "a TCP tether needs a 'port' between 1 and 65535")
            if (t.delivery != DeliveryPolicy.DROP) problem("$path.delivery", "a TCP tether only supports delivery DROP")
        } else if (t.port != null) {
            problem("$path.port", "only TCP tethers have a 'port'")
        }
    }

    private fun endpoint(
        e: Endpoint,
        path: String,
        instances: Map<String, Pair<BlueprintBlock, BlockDefinition>>,
        unresolved: Set<String>,
        direction: PortDirection,
        report: (String, String) -> Boolean,
    ): PortDefinition? {
        if (e.block in unresolved) return null
        val (instance, definition) = instances[e.block] ?: run {
            report("$path.block", "unknown block id '${e.block}'")
            return null
        }
        val port = definition.ports.firstOrNull { it.name == e.port } ?: run {
            report("$path.port", "block '${e.block}' has no port '${e.port}'")
            return null
        }
        if (port.direction != direction) {
            report("$path.port", "port '${e.port}' is ${port.direction} but must be $direction here")
        }
        if (port.varArg) {
            val size = instance.varArgCounts[port.name]
            when {
                e.index == null -> report("$path.index", "VarArg port '${e.port}' needs an index")
                size != null && e.index >= size -> report("$path.index", "index ${e.index} is outside 0 until $size")
            }
        } else if (e.index != null) {
            report("$path.index", "port '${e.port}' is not a VarArg port and takes no index")
        }
        return port
    }

    private fun schemaRefs(block: BlockDefinition, path: String, registry: SchemaRegistry): List<PackageProblem> {
        val refs = block.schemas.mapIndexed { i, r -> "$path.schemas[$i]" to r } +
            block.ports.mapIndexed { i, p -> "$path.ports[$i].schema" to p.schema } +
            listOfNotNull(block.configSchema?.let { "$path.configSchema" to it })
        return refs.filter { (_, ref) -> registry.resolve(ref) == null }
            .map { (p, ref: SchemaRef) -> PackageProblem(p, "schema '$ref' does not resolve") }
    }

    private fun duplicates(values: List<String>, path: String, what: String, problems: MutableList<PackageProblem>) {
        values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            problems += PackageProblem(path, "duplicate $what '$it'")
        }
    }

    private fun registry(
        plugins: List<PluginPackage>,
        extra: List<Pair<Map<String, String>, String>>,
        problems: MutableList<PackageProblem>,
    ): SchemaRegistry {
        val registry = SchemaRegistry()
        val sources = plugins.map { it.schemas to "${it.manifest.name}@${it.manifest.version}" } + extra
        for ((schemas, owner) in sources) {
            for ((entry, text) in schemas) {
                val origin = "$owner:$entry"
                try {
                    registry.add(SchemaParser.parse(text, origin))
                } catch (e: SchemaParseException) {
                    problems += PackageProblem(origin, e.message ?: "invalid schema")
                } catch (e: SchemaConflictException) {
                    problems += PackageProblem(origin, e.message ?: "schema conflict")
                }
            }
        }
        registry.problems().forEach { problems += PackageProblem("${it.origin} ${it.path}", it.message) }
        return registry
    }
}
