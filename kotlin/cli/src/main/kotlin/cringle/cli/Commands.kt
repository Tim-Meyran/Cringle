// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import cringle.common.v1.EngineId
import cringle.common.v1.FabricId
import cringle.management.v1.AddMachineRequest
import cringle.management.v1.CleanupCacheRequest
import cringle.management.v1.CreateEngineRequest
import cringle.management.v1.DeleteEngineRequest
import cringle.management.v1.DeployProjectRequest
import cringle.management.v1.EngineRef
import cringle.management.v1.FabricRef
import cringle.management.v1.ListEnginesRequest
import cringle.management.v1.ListFabricsRequest
import cringle.management.v1.ListMachinesRequest
import cringle.management.v1.MachineRequest
import cringle.management.v1.ManagedEngine
import cringle.management.v1.ManagedFabric
import cringle.management.v1.QueryLogsRequest
import cringle.management.v1.RecoverRequest
import cringle.management.v1.SetEngineTagsRequest
import cringle.management.v1.UndeployRequest
import cringle.repository.v1.DownloadRequest
import cringle.repository.v1.ListPackagesRequest
import cringle.repository.v1.ListVersionsRequest
import cringle.repository.v1.PackageKind
import cringle.repository.v1.PackageMetadata
import cringle.repository.v1.PluginTrust
import cringle.repository.v1.PublishHeader
import cringle.repository.v1.PublishRequest
import cringle.repository.v1.SetPluginTrustRequest
import cringle.management.v1.AddTrustedComponentRequest
import cringle.management.v1.RemoveTrustRequest
import cringle.router.v1.AddRemoteRouterRequest
import cringle.router.v1.ListTrustRequest
import cringle.router.v1.ListRemoteRoutersRequest
import cringle.router.v1.RemoveRemoteRouterRequest
import cringle.user.v1.CreateGroupRequest
import cringle.user.v1.CreateTokenRequest
import cringle.user.v1.CreateUserRequest
import cringle.user.v1.DeleteUserRequest
import cringle.user.v1.ListGroupsRequest
import cringle.user.v1.ListTokensRequest
import cringle.user.v1.ListUsersRequest
import cringle.user.v1.RevokeTokenRequest
import cringle.user.v1.Role
import cringle.user.v1.WhoAmIRequest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList

/**
 * What a command can use: the connection (opened on first use, pinned to the fingerprint of the server) and the profile.
 * [warn] prints a warning to the error stream.
 */
internal class Env(
    val connection: () -> Connection,
    val profile: Profile,
    val profileFile: Path,
    val readSecret: () -> String?,
    val save: (Profile) -> Unit,
    val warn: (String) -> Unit = {},
    val environment: Map<String, String> = emptyMap(),
) {
    val m get() = connection().management
    val users get() = connection().users
}

/** The message of a command that has to connect and has no fingerprint of the server. */
internal const val NO_FINGERPRINT: String =
    "no fingerprint of the server key: the connection is TLS and pinned. Run 'cringle login --server host:port --fingerprint <sha256>' " +
        "(or with --yes to accept the fingerprint the server shows), or set CRINGLE_FINGERPRINT"

/** One command: its words, arguments, options and what it does. */
internal class Command(
    val words: List<String>,
    val args: String,
    val summary: String,
    val options: List<OptionSpec> = emptyList(),
    val minArgs: Int = 0,
    val maxArgs: Int = minArgs,
    val needsServer: Boolean = true,
    val run: suspend (Env, Parsed) -> Output,
) {
    val name: String get() = words.joinToString(" ")
}

private fun opt(name: String, description: String, placeholder: String = "VALUE", repeatable: Boolean = false) = OptionSpec(name, description, true, repeatable, placeholder)

private fun flag(name: String, description: String) = OptionSpec(name, description, takesValue = false)

private fun engineRef(machine: String, engine: String) = EngineRef.newBuilder().setMachineId(machine).setEngineId(EngineId.newBuilder().setValue(engine)).build()

private fun fabricRef(machine: String, engine: String, fabric: String) =
    FabricRef.newBuilder().setEngine(engineRef(machine, engine)).setFabricId(FabricId.newBuilder().setValue(fabric)).build()

private fun Timestamp.iso(): String = if (seconds == 0L && nanos == 0) "" else Instant.ofEpochSecond(seconds, nanos.toLong()).toString()

private fun Enum<*>.pretty(prefix: String) = name.removePrefix(prefix).lowercase()

private fun engineRow(e: ManagedEngine): Map<String, Any?> = linkedMapOf(
    "machine" to e.machineId,
    "engine" to e.process.engineId.value,
    "name" to e.process.name,
    "state" to e.process.state.pretty("ENGINE_PROCESS_STATE_"),
    "roles" to e.rolesList,
    "labels" to e.labelsMap.toSortedMap(),
)

private fun engineDetail(e: ManagedEngine): Map<String, Any?> = linkedMapOf(
    "machine" to e.machineId,
    "engine" to e.process.engineId.value,
    "name" to e.process.name,
    "state" to e.process.state.pretty("ENGINE_PROCESS_STATE_"),
    "pid" to e.process.pid,
    "managementPort" to e.process.managementPort,
    "startedAt" to e.process.startedAt.iso(),
    "autostart" to e.autostart,
    "roles" to e.rolesList,
    "labels" to e.labelsMap.toSortedMap(),
    "router" to e.status.routerAddress,
    "certificate" to e.status.certificate.fingerprint,
    "fingerprint" to e.status.publicKeyFingerprint,
    "lastError" to e.process.lastError,
)

private fun bindingRow(b: cringle.management.v1.Binding): Map<String, Any?> = linkedMapOf(
    "project" to b.consumerProject,
    "service" to b.service,
    "fabric" to b.targetsList.joinToString(", "),
)

private fun fabricRow(f: ManagedFabric): Map<String, Any?> = linkedMapOf(
    "machine" to f.machineId,
    "engine" to f.engineId.value,
    "fabric" to f.info.fabricId.value,
    "blueprint" to f.info.blueprint,
    "state" to f.info.state.pretty("FABRIC_RUNTIME_STATE_"),
    "desired" to if (f.desiredRunning) "running" else "stopped",
)

private fun fabricDetail(f: ManagedFabric): Map<String, Any?> = fabricRow(f) + mapOf(
    "failure" to f.info.failure,
    "blocks" to f.info.blocksList.map {
        linkedMapOf("block" to it.blockId.value, "state" to it.state.pretty("BLOCK_RUNTIME_STATE_"), "restarts" to it.restarts, "lastError" to it.lastError)
    },
)

private fun packageRow(p: PackageMetadata): Map<String, Any?> = linkedMapOf(
    "kind" to p.kind.pretty("PACKAGE_KIND_"),
    "name" to p.name,
    "version" to p.version,
    "trust" to (if (p.kind == PackageKind.PACKAGE_KIND_PLUGIN) p.trust.pretty("PLUGIN_TRUST_") else ""),
    "size" to p.sizeBytes,
    "sha256" to p.sha256,
)

private fun roleOf(name: String): Role = when (name.lowercase()) {
    "admin" -> Role.ROLE_ADMIN
    "operator" -> Role.ROLE_OPERATOR
    "viewer" -> Role.ROLE_VIEWER
    "end-user" -> Role.ROLE_END_USER
    else -> throw UsageException("unknown role '$name' (admin, operator, viewer, end-user)")
}

private fun roleName(r: Role) = r.pretty("ROLE_").replace('_', '-')

private fun userRow(u: cringle.user.v1.User): Map<String, Any?> = linkedMapOf(
    "id" to u.id,
    "name" to u.name,
    "roles" to u.rolesList.map(::roleName),
    "groups" to u.groupsList,
    "effectiveRoles" to u.effectiveRolesList.map(::roleName),
)

private fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun parseSince(text: String): Timestamp {
    val instant = Regex("(\\d+)([smhd])").matchEntire(text)?.let { m ->
        val n = m.groupValues[1].toLong()
        Instant.now().minus(Duration.ofSeconds(n * when (m.groupValues[2]) { "s" -> 1L; "m" -> 60L; "h" -> 3600L; else -> 86_400L }))
    } ?: try {
        Instant.parse(text)
    } catch (e: Exception) {
        throw UsageException("--since must be a time like 2026-01-01T10:00:00Z or a duration like 30m, 2h, 1d")
    }
    return Timestamp.newBuilder().setSeconds(instant.epochSecond).setNanos(instant.nano).build()
}

private val tagOptions = listOf(
    opt("role", "role of the engine (repeatable)", "ROLE", repeatable = true),
    opt("label", "label of the engine as key=value (repeatable)", "KEY=VALUE", repeatable = true),
)

/** All commands of `cringle`. */
internal val COMMANDS: List<Command> = listOf(
    // --- connection ---
    Command(
        listOf("login"), "", "Store server address, user token and the pinned fingerprint of the server key in the profile",
        listOf(
            opt("token-file", "file with the user token", "FILE"),
            opt("token", "user token (ends up in the shell history; prefer --token-file or standard input)", "TOKEN"),
            opt("fingerprint", "SHA-256 fingerprint of the key of the server (64 hexadecimal characters); the server has to present exactly this key", "SHA256"),
            flag("yes", "accept the fingerprint that the server shows instead of giving --fingerprint"),
        ),
        needsServer = false,
    ) { env, a ->
        val server = env.profile.server ?: throw UsageException("no server: use --server host:port or CRINGLE_SERVER")
        if (a.option("token") != null && a.option("token-file") != null) throw UsageException("use either --token or --token-file, not both")
        val token = when {
            a.option("token") != null -> {
                env.warn("the token is now in the history of your shell and visible to other users in the process list; use --token-file or pipe it to standard input instead")
                a.option("token")!!.trim().takeIf { it.isNotEmpty() } ?: throw UsageException("--token is empty")
            }
            a.option("token-file") != null -> {
                val file = Paths.get(a.option("token-file")!!)
                val text = try {
                    Files.readString(file)
                } catch (e: java.io.IOException) {
                    throw UsageException("cannot read the token file $file: ${e.message}")
                }
                text.trim().takeIf { it.isNotEmpty() } ?: throw UsageException("the token file $file is empty")
            }
            else -> env.readSecret()?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw UsageException("no token: use --token-file, or pipe it to standard input")
        }
        val pinned = pinnedFingerprint(server, a.option("fingerprint"), a.flag("yes"))
        // the server checks the token; a server without user management does not know the call and accepts anything
        val connection = Connection(server, token, pinned)
        val who = try {
            connection.users.whoAmI(WhoAmIRequest.getDefaultInstance()).user.name
        } catch (e: io.grpc.StatusException) {
            if (e.status.code == io.grpc.Status.Code.UNIMPLEMENTED) null else throw e
        } finally {
            connection.close()
        }
        env.save(Profile(server, token, pinned))
        Output.Message(if (who == null) "stored profile for $server (the server does not use logins)" else "logged in to $server as $who", mapOf("server" to server, "user" to who))
    },
    Command(listOf("logout"), "", "Remove the stored token from the profile", needsServer = false) { env, _ ->
        env.save(Profile(env.profile.server, null, env.profile.fingerprint))
        Output.Message("logged out")
    },
    Command(listOf("whoami"), "", "Show the user the token belongs to") { env, _ ->
        val u = env.users.whoAmI(WhoAmIRequest.getDefaultInstance()).user
        Output.Detail(userRow(u))
    },

    // --- machines ---
    Command(
        listOf("machine", "add"), "<id> <daemon-address>", "Add a machine (its daemon) to the machine park",
        listOf(opt("host", "host of the machine as seen from the ManagementServer", "HOST"), opt("repository", "repository responsible for this machine (host:port)", "ADDRESS")),
        2,
    ) { env, a ->
        val r = env.m.addMachine(
            AddMachineRequest.newBuilder().setMachineId(a.positional[0]).setDaemonAddress(a.positional[1])
                .setHost(a.option("host").orEmpty()).setRepositoryAddress(a.option("repository").orEmpty()).build(),
        )
        Output.Detail(linkedMapOf("machine" to r.machineId, "daemon" to r.daemonAddress, "host" to r.host, "repository" to r.repositoryAddress, "reachable" to r.reachable, "error" to r.lastError))
    },
    Command(listOf("machine", "list"), "", "List the machines and whether their daemons answer") { env, _ ->
        Output.Rows(
            env.m.listMachines(ListMachinesRequest.getDefaultInstance()).machinesList.map {
                linkedMapOf<String, Any?>("machine" to it.machineId, "daemon" to it.daemonAddress, "reachable" to it.reachable, "repository" to it.repositoryAddress, "error" to it.lastError)
            },
            "no machines",
        )
    },
    Command(listOf("machine", "remove"), "<id>", "Forget a machine (its engines keep running)", minArgs = 1) { env, a ->
        env.m.removeMachine(MachineRequest.newBuilder().setMachineId(a.positional[0]).build())
        Output.Message("removed machine ${a.positional[0]}")
    },

    // --- engines ---
    Command(
        listOf("engine", "create"), "<machine>", "Create an engine on a machine",
        listOf(opt("id", "engine id; allocated by the daemon if omitted", "ID"), opt("name", "display name", "NAME"), flag("no-autostart", "do not start the engine again after a restart")) + tagOptions,
        1,
    ) { env, a ->
        val b = CreateEngineRequest.newBuilder().setMachineId(a.positional[0]).setEngineId(a.option("id").orEmpty()).setName(a.option("name").orEmpty())
            .addAllRoles(a.options("role")).putAllLabels(a.pairs("label"))
        if (a.flag("no-autostart")) b.autostart = false
        Output.Detail(engineDetail(env.m.createEngine(b.build())))
    },
    Command(listOf("engine", "start"), "<machine> <engine>", "Start an engine", minArgs = 2) { env, a -> Output.Detail(engineDetail(env.m.startEngine(engineRef(a.positional[0], a.positional[1])))) },
    Command(listOf("engine", "stop"), "<machine> <engine>", "Stop an engine", minArgs = 2) { env, a -> Output.Detail(engineDetail(env.m.stopEngine(engineRef(a.positional[0], a.positional[1])))) },
    Command(
        listOf("engine", "delete"), "<machine> <engine>", "Stop and remove an engine", listOf(flag("delete-data", "also delete the data directory of the engine")), 2,
    ) { env, a ->
        env.m.deleteEngine(DeleteEngineRequest.newBuilder().setEngine(engineRef(a.positional[0], a.positional[1])).setDeleteData(a.flag("delete-data")).build())
        Output.Message("deleted engine ${a.positional[0]}/${a.positional[1]}")
    },
    Command(listOf("engine", "list"), "[machine]", "List engines, of one machine or of all", minArgs = 0, maxArgs = 1) { env, a ->
        Output.Rows(env.m.listEngines(ListEnginesRequest.newBuilder().setMachineId(a.positional.firstOrNull().orEmpty()).build()).enginesList.map(::engineRow), "no engines")
    },
    Command(listOf("engine", "status"), "<machine> <engine>", "Show one engine with its own status", minArgs = 2) { env, a -> Output.Detail(engineDetail(env.m.getEngine(engineRef(a.positional[0], a.positional[1])))) },
    Command(listOf("engine", "tag"), "<machine> <engine>", "Set the roles and labels of an engine (replaces the old ones)", tagOptions, 2) { env, a ->
        Output.Detail(
            engineDetail(
                env.m.setEngineTags(SetEngineTagsRequest.newBuilder().setEngine(engineRef(a.positional[0], a.positional[1])).addAllRoles(a.options("role")).putAllLabels(a.pairs("label")).build()),
            ),
        )
    },

    // --- fabrics and deployment ---
    Command(listOf("fabric", "list"), "[machine engine]", "List fabrics, of one engine or of all", minArgs = 0, maxArgs = 2) { env, a ->
        if (a.positional.size == 1) throw UsageException("give machine and engine, or neither")
        val request = ListFabricsRequest.newBuilder()
        if (a.positional.size == 2) request.engine = engineRef(a.positional[0], a.positional[1])
        Output.Rows(env.m.listFabrics(request.build()).fabricsList.map(::fabricRow), "no fabrics")
    },
    Command(listOf("fabric", "status"), "<machine> <engine> <fabric>", "Show one fabric with its blocks", minArgs = 3) { env, a ->
        Output.Detail(fabricDetail(env.m.getFabric(fabricRef(a.positional[0], a.positional[1], a.positional[2]))))
    },
    Command(listOf("fabric", "start"), "<machine> <engine> <fabric>", "Start a fabric", minArgs = 3) { env, a ->
        Output.Detail(fabricDetail(env.m.startFabric(fabricRef(a.positional[0], a.positional[1], a.positional[2]))))
    },
    Command(listOf("fabric", "stop"), "<machine> <engine> <fabric>", "Stop a fabric", minArgs = 3) { env, a ->
        Output.Detail(fabricDetail(env.m.stopFabric(fabricRef(a.positional[0], a.positional[1], a.positional[2]))))
    },
    Command(listOf("fabric", "remove"), "<machine> <engine> <fabric>", "Remove a fabric", minArgs = 3) { env, a ->
        env.m.removeFabric(fabricRef(a.positional[0], a.positional[1], a.positional[2]))
        Output.Message("removed fabric ${a.positional[2]}")
    },
    Command(
        listOf("deploy"), "<project>", "Deploy a project from the repository onto the engines its fabric configuration selects",
        listOf(
            opt("version", "version range, default: the highest release", "RANGE"),
            flag("no-start", "deploy the fabrics but do not start them"),
            flag("relock", "resolve the dependencies again instead of using the lock file of this version"),
        ),
        1,
    ) { env, a ->
        val b = DeployProjectRequest.newBuilder().setProject(a.positional[0]).setVersionRange(a.option("version").orEmpty())
        if (a.flag("no-start")) b.start = false
        if (a.flag("relock")) b.relock = true
        val r = env.m.deploy(b.build())
        Output.Detail(linkedMapOf("project" to r.project, "version" to r.version, "fabrics" to r.fabricsList.map(::fabricRow)))
    },
    Command(listOf("bind"), "<project> <service> <fabric>...", "Bind a service dependency of a project to the fabrics that provide it, the preferred one first", minArgs = 3, maxArgs = 20) { env, a ->
        Output.Detail(bindingRow(env.m.bind(cringle.management.v1.Binding.newBuilder().setConsumerProject(a.positional[0]).setService(a.positional[1]).addAllTargets(a.positional.drop(2)).build())))
    },
    Command(listOf("unbind"), "<project> <service>", "Remove the binding of a service dependency", minArgs = 2) { env, a ->
        env.m.unbind(cringle.management.v1.UnbindRequest.newBuilder().setConsumerProject(a.positional[0]).setService(a.positional[1]).build())
        Output.Message("unbound ${a.positional[1]} of ${a.positional[0]}")
    },
    Command(listOf("bindings"), "[project]", "List the bindings of service dependencies, of one project or of all", minArgs = 0, maxArgs = 1) { env, a ->
        Output.Rows(env.m.listBindings(cringle.management.v1.ListBindingsRequest.newBuilder().setConsumerProject(a.positional.firstOrNull().orEmpty()).build()).bindingsList.map(::bindingRow), "no bindings")
    },
    Command(listOf("undeploy"), "<project>", "Stop and remove all fabrics of a project", minArgs = 1) { env, a ->
        val removed = env.m.undeploy(UndeployRequest.newBuilder().setProject(a.positional[0]).build()).removedList
        Output.Detail(linkedMapOf("removed" to removed))
    },
    Command(
        listOf("cache", "cleanup"), "[machine]", "Remove package versions that no fabric uses from the cache of a machine, or of all",
        listOf(opt("min-unused-days", "only versions unused for at least this many days (default 0)", "DAYS")), minArgs = 0, maxArgs = 1,
    ) { env, a ->
        val r = env.m.cleanupCache(CleanupCacheRequest.newBuilder().setMachineId(a.positional.firstOrNull().orEmpty()).setMinUnusedSeconds((a.long("min-unused-days") ?: 0) * 86_400).build())
        Output.Detail(linkedMapOf("removed" to r.removedList, "problems" to r.problemsList))
    },
    Command(listOf("recover"), "", "Start registered engines and restore their fabrics (also done when the server starts)") { env, _ ->
        val r = env.m.recover(RecoverRequest.getDefaultInstance())
        Output.Detail(linkedMapOf("enginesStarted" to r.enginesStarted, "fabricsRestored" to r.fabricsRestored, "problems" to r.problemsList))
    },

    // --- logs ---
    Command(
        listOf("logs"), "[machine engine]", "Show log entries, of one engine or of all running engines",
        listOf(
            opt("fabric", "only this fabric", "FABRIC"), opt("block", "only this block", "BLOCK"), opt("level", "minimum level: debug, info, warn, error", "LEVEL"),
            opt("since", "only entries since a time (2026-01-01T10:00:00Z) or a duration (30m, 2h, 1d)", "TIME"), opt("limit", "at most this many entries, the newest (default 1000)", "N"),
        ),
        minArgs = 0, maxArgs = 2,
    ) { env, a ->
        if (a.positional.size == 1) throw UsageException("give machine and engine, or neither")
        val b = QueryLogsRequest.newBuilder().setFabric(a.option("fabric").orEmpty()).setBlock(a.option("block").orEmpty()).setLimit((a.long("limit") ?: 0).toInt())
        if (a.positional.size == 2) b.engine = engineRef(a.positional[0], a.positional[1])
        a.option("level")?.let {
            b.minLevel = when (it.lowercase()) {
                "debug" -> cringle.engine.v1.LogLevel.LOG_LEVEL_DEBUG
                "info" -> cringle.engine.v1.LogLevel.LOG_LEVEL_INFO
                "warn" -> cringle.engine.v1.LogLevel.LOG_LEVEL_WARN
                "error" -> cringle.engine.v1.LogLevel.LOG_LEVEL_ERROR
                else -> throw UsageException("--level must be debug, info, warn or error")
            }
        }
        a.option("since")?.let { b.since = parseSince(it) }
        val r = env.m.queryLogs(b.build())
        val entries = r.entriesList.map {
            linkedMapOf<String, Any?>(
                "time" to it.entry.timestamp.iso(), "level" to it.entry.level.pretty("LOG_LEVEL_"), "machine" to it.machineId, "engine" to it.engineId.value,
                "fabric" to it.entry.fabric, "block" to it.entry.block, "source" to it.entry.source, "message" to it.entry.message,
            )
        }
        val lines = entries.map { "${it["time"]} ${it["level"].toString().uppercase().padEnd(5)} ${it["machine"]}/${it["engine"]} ${it["fabric"]}/${it["block"]}${(it["source"] as String).let { s -> if (s.isEmpty()) "" else " [$s]" }}: ${it["message"]}" } +
            r.problemsList.map { "warning: could not read logs of $it" }
        Output.Lines(lines, entries)
    },

    // --- metrics ---
    Command(
        listOf("metrics"), "[machine engine]", "Show the numbers of one engine or of all running engines: CPU, memory, errors, and per fabric or tether",
        listOf(
            flag("fabrics", "one row per fabric (CPU time, errors, blocks)"), flag("tethers", "one row per tether (messages, bytes, errors)"),
            opt("fabric", "only this fabric (with --fabrics or --tethers)", "FABRIC"),
        ),
        minArgs = 0, maxArgs = 2,
    ) { env, a ->
        if (a.positional.size == 1) throw UsageException("give machine and engine, or neither")
        if (a.flag("fabrics") && a.flag("tethers")) throw UsageException("give --fabrics or --tethers, not both")
        val b = cringle.management.v1.GetMetricsRequest.newBuilder()
        if (a.positional.size == 2) b.engine = engineRef(a.positional[0], a.positional[1])
        val r = env.m.getMetrics(b.build())
        val only = a.option("fabric")
        val rows = ArrayList<Map<String, Any?>>()
        for (m in r.metricsList) {
            val e = m.metrics
            val origin = linkedMapOf<String, Any?>("machine" to m.machineId, "engine" to m.engineId.value)
            val fabrics = e.fabricsList.filter { only == null || it.fabricId.value == only }
            when {
                a.flag("fabrics") -> fabrics.forEach { f ->
                    rows += LinkedHashMap(origin).apply {
                        put("fabric", f.fabricId.value); put("cpuMs", if (f.cpuTimeNs < 0) -1 else f.cpuTimeNs / 1_000_000)
                        put("errors", f.errors); put("blocks", f.blocksList.joinToString(", ") { "${it.blockId}:${it.errors}" })
                    }
                }
                a.flag("tethers") -> fabrics.forEach { f ->
                    f.tethersList.forEach { t ->
                        rows += LinkedHashMap(origin).apply {
                            put("fabric", f.fabricId.value); put("tether", t.tetherId); put("type", t.type)
                            put("messages", t.messages); put("bytes", t.bytes); put("errors", t.errors)
                        }
                    }
                }
                else -> rows += LinkedHashMap(origin).apply {
                    put("cpu", if (e.processCpuLoad < 0) "n/a" else "%.0f%%".format(e.processCpuLoad * 100))
                    put("heapUsedMb", e.heapUsedBytes / (1024 * 1024)); put("heapMaxMb", e.heapMaxBytes / (1024 * 1024))
                    put("threads", e.threadCount); put("fabrics", e.fabricsCount); put("errors", e.fabricsList.sumOf { it.errors })
                }
            }
        }
        r.problemsList.forEach { env.warn("could not read metrics of $it") }
        Output.Rows(rows, "no running engines")
    },

    // --- routers ---
    Command(
        listOf("router", "add"), "<address>", "Connect the router of the ManagementServer machine to another router",
        listOf(opt("fingerprint", "SHA-256 fingerprint of the key of the remote router, which a router with mTLS wants confirmed", "SHA256")),
        1,
    ) { env, a ->
        val r = env.m.addRemoteRouter(AddRemoteRouterRequest.newBuilder().setAddress(a.positional[0]).setExpectedFingerprint(a.option("fingerprint").orEmpty()).build()).router
        Output.Detail(linkedMapOf("address" to r.address, "cachedEngines" to r.cachedEngines, "lastRefresh" to r.lastRefresh.iso(), "error" to r.lastError))
    },
    Command(listOf("router", "remove"), "<address>", "Disconnect a remote router", minArgs = 1) { env, a ->
        env.m.removeRemoteRouter(RemoveRemoteRouterRequest.newBuilder().setAddress(a.positional[0]).build())
        Output.Message("removed router ${a.positional[0]}")
    },
    Command(listOf("router", "list"), "", "List the connected remote routers") { env, _ ->
        Output.Rows(
            env.m.listRemoteRouters(ListRemoteRoutersRequest.getDefaultInstance()).routersList.map {
                linkedMapOf<String, Any?>("address" to it.address, "cachedEngines" to it.cachedEngines, "lastRefresh" to it.lastRefresh.iso(), "error" to it.lastError)
            },
            "no remote routers",
        )
    },

    // --- trust ---
    Command(listOf("trust", "list"), "", "List what the ManagementServer and its router trust, with kind and origin") { env, _ ->
        Output.Rows(
            env.m.listTrust(ListTrustRequest.getDefaultInstance()).entriesList.map {
                linkedMapOf<String, Any?>(
                    "fingerprint" to it.fingerprint,
                    "kind" to it.kind,
                    "name" to it.name,
                    "address" to it.address,
                    "origin" to it.origin,
                    "addedAt" to it.addedAt.iso(),
                )
            },
            "nothing is trusted",
        )
    },
    Command(
        listOf("trust", "add"), "<router-address>", "Trust another router by the fingerprint of its key and connect the router of this machine to it",
        listOf(
            opt("fingerprint", "SHA-256 fingerprint of the key of the router, as its operator gave it to you", "SHA256"),
            flag("yes", "accept the fingerprint that the router shows instead of giving --fingerprint"),
        ),
        1,
    ) { env, a ->
        val address = a.positional[0]
        val actual = probeFingerprint(address, "router")
        val given = a.option("fingerprint")?.trim()?.lowercase()
        if (given != null) {
            if (!cringle.common.PublicKeyFingerprint.pattern.matches(given)) {
                throw UsageException("--fingerprint is not a SHA-256 fingerprint (64 hexadecimal characters): '${a.option("fingerprint")}'")
            }
            if (given != actual) throw IllegalStateException("the router at $address presents the key fingerprint $actual, not the expected $given; nothing was added")
        } else if (!a.flag("yes")) {
            throw UsageException(
                "the router at $address presents the key fingerprint $actual; check it with the operator of the router and run again " +
                    "with --fingerprint $actual (or --yes to accept it); nothing was added",
            )
        }
        val r = env.m.addRemoteRouter(AddRemoteRouterRequest.newBuilder().setAddress(address).setExpectedFingerprint(actual).build()).router
        Output.Detail(linkedMapOf("address" to r.address, "fingerprint" to actual, "cachedEngines" to r.cachedEngines, "lastRefresh" to r.lastRefresh.iso(), "error" to r.lastError))
    },
    Command(
        listOf("trust", "add-component"), "<fingerprint>", "Trust a component (a daemon, a repository, a server) by the fingerprint of its key",
        listOf(
            opt("name", "name of the entry (required)", "NAME"),
            opt("kind", "COMPONENT (default) or SERVER", "KIND"),
            opt("address", "where the component can be reached (host:port)", "ADDRESS"),
        ),
        1,
    ) { env, a ->
        val name = a.option("name") ?: throw UsageException("--name is required")
        val entry = env.m.addTrustedComponent(
            AddTrustedComponentRequest.newBuilder().setFingerprint(a.positional[0]).setName(name)
                .setKind(a.option("kind").orEmpty()).setAddress(a.option("address").orEmpty()).build(),
        )
        Output.Detail(linkedMapOf("fingerprint" to entry.fingerprint, "kind" to entry.kind, "name" to entry.name, "address" to entry.address))
    },
    Command(listOf("trust", "revoke"), "<fingerprint>", "Remove the trust in a key; for a router also the engines that came through it", minArgs = 1) { env, a ->
        val removed = env.m.removeTrust(RemoveTrustRequest.newBuilder().setFingerprint(a.positional[0]).build()).removedEntries
        Output.Message("revoked ${a.positional[0]} ($removed ${if (removed == 1) "entry" else "entries"} removed)", mapOf("fingerprint" to a.positional[0], "removed" to removed))
    },

    // --- repository ---
    Command(listOf("repo", "publish"), "<package-file>", "Publish a project or plugin package", minArgs = 1) { env, a ->
        val file = Paths.get(a.positional[0])
        if (!Files.isRegularFile(file)) throw UsageException("${a.positional[0]} is not a file")
        val hash = sha256(file)
        val requests = flow {
            emit(PublishRequest.newBuilder().setHeader(PublishHeader.newBuilder().setExpectedSha256(hash)).build())
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    emit(PublishRequest.newBuilder().setChunk(ByteString.copyFrom(buffer, 0, n)).build())
                }
            }
        }
        val p = env.m.publishPackage(requests).metadata
        Output.Detail(packageRow(p))
    },
    Command(listOf("repo", "list"), "", "List the packages of the repository", listOf(opt("kind", "plugin or project", "KIND"))) { env, a ->
        val kind = when (a.option("kind")?.lowercase()) {
            null -> PackageKind.PACKAGE_KIND_UNSPECIFIED
            "plugin" -> PackageKind.PACKAGE_KIND_PLUGIN
            "project" -> PackageKind.PACKAGE_KIND_PROJECT
            else -> throw UsageException("--kind must be plugin or project")
        }
        Output.Rows(env.m.listPackages(ListPackagesRequest.newBuilder().setKind(kind).build()).packagesList.map(::packageRow), "the repository is empty")
    },
    Command(listOf("repo", "versions"), "<name>", "List the versions of a package", minArgs = 1) { env, a ->
        Output.Rows(env.m.listVersions(ListVersionsRequest.newBuilder().setName(a.positional[0]).build()).versionsList.map(::packageRow), "no such package")
    },
    Command(listOf("repo", "download"), "<name> <version> <file>", "Download a package (the hash is verified)", minArgs = 3) { env, a ->
        val target = Paths.get(a.positional[2])
        val part = target.resolveSibling(target.fileName.toString() + ".part")
        var meta: PackageMetadata? = null
        try {
            Files.newOutputStream(part).use { out ->
                for (r in env.m.downloadPackage(DownloadRequest.newBuilder().setName(a.positional[0]).setVersion(a.positional[1]).build()).toList()) {
                    if (r.hasMetadata()) meta = r.metadata else r.chunk.writeTo(out)
                }
            }
            val expected = meta?.sha256 ?: throw IllegalStateException("the server sent no metadata")
            val actual = sha256(part)
            if (actual != expected) throw IllegalStateException("downloaded file has hash $actual, expected $expected")
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(part)
        }
        Output.Message("downloaded ${a.positional[0]}@${a.positional[1]} to $target", mapOf("file" to target.toString(), "sha256" to meta?.sha256))
    },
    Command(listOf("repo", "trust"), "<plugin> <trusted|untrusted>", "Set the trust status of a plugin (all versions)", minArgs = 2) { env, a ->
        val trust = when (a.positional[1].lowercase()) {
            "trusted" -> PluginTrust.PLUGIN_TRUST_TRUSTED
            "untrusted" -> PluginTrust.PLUGIN_TRUST_UNTRUSTED
            else -> throw UsageException("trust must be 'trusted' or 'untrusted'")
        }
        Output.Rows(env.m.setPluginTrust(SetPluginTrustRequest.newBuilder().setName(a.positional[0]).setTrust(trust).build()).packagesList.map(::packageRow))
    },

    // --- users ---
    Command(
        listOf("user", "create"), "<name>", "Create a user",
        listOf(opt("role", "role: admin, operator, viewer, end-user (repeatable)", "ROLE", repeatable = true), opt("group", "group of the user (repeatable)", "GROUP", repeatable = true)), 1,
    ) { env, a ->
        val u = env.users.createUser(CreateUserRequest.newBuilder().setName(a.positional[0]).addAllRoles(a.options("role").map(::roleOf)).addAllGroups(a.options("group")).build()).user
        Output.Detail(userRow(u))
    },
    Command(listOf("user", "list"), "", "List the users") { env, _ ->
        Output.Rows(env.users.listUsers(ListUsersRequest.getDefaultInstance()).usersList.map(::userRow), "no users")
    },
    Command(listOf("user", "delete"), "<user-id>", "Delete a user and its tokens", minArgs = 1) { env, a ->
        env.users.deleteUser(DeleteUserRequest.newBuilder().setUserId(a.positional[0]).build())
        Output.Message("deleted user ${a.positional[0]}")
    },
    Command(
        listOf("group", "create"), "<name>", "Create a group that gives its members roles",
        listOf(opt("role", "role: admin, operator, viewer, end-user (repeatable)", "ROLE", repeatable = true)), 1,
    ) { env, a ->
        val g = env.users.createGroup(CreateGroupRequest.newBuilder().setName(a.positional[0]).addAllRoles(a.options("role").map(::roleOf)).build()).group
        Output.Detail(linkedMapOf("name" to g.name, "roles" to g.rolesList.map(::roleName)))
    },
    Command(listOf("group", "list"), "", "List the groups") { env, _ ->
        Output.Rows(env.users.listGroups(ListGroupsRequest.getDefaultInstance()).groupsList.map { linkedMapOf<String, Any?>("name" to it.name, "roles" to it.rolesList.map(::roleName)) }, "no groups")
    },
    Command(
        listOf("token", "create"), "<user-id>", "Create a token for a user; the value is shown once",
        listOf(opt("label", "what the token is for", "LABEL"), opt("ttl-days", "lifetime in days; default: does not expire", "DAYS")), 1,
    ) { env, a ->
        val r = env.users.createToken(
            CreateTokenRequest.newBuilder().setUserId(a.positional[0]).setLabel(a.option("label").orEmpty()).setTtlSeconds((a.long("ttl-days") ?: 0) * 86_400).build(),
        )
        Output.Detail(linkedMapOf("token" to r.token, "id" to r.info.id, "user" to r.info.userId, "label" to r.info.label, "expires" to r.info.expiresAt.iso()))
    },
    Command(listOf("token", "list"), "<user-id>", "List the tokens of a user (never their values)", minArgs = 1) { env, a ->
        Output.Rows(
            env.users.listTokens(ListTokensRequest.newBuilder().setUserId(a.positional[0]).build()).tokensList.map {
                linkedMapOf<String, Any?>("id" to it.id, "label" to it.label, "created" to it.createdAt.iso(), "expires" to it.expiresAt.iso(), "revoked" to it.revoked)
            },
            "no tokens",
        )
    },
    Command(listOf("token", "revoke"), "<token-id>", "Revoke a token", minArgs = 1) { env, a ->
        env.users.revokeToken(RevokeTokenRequest.newBuilder().setTokenId(a.positional[0]).build())
        Output.Message("revoked token ${a.positional[0]}")
    },

    // --- installation ---
    Command(
        listOf("self-update"), "[--version <v>]", "Update this installation to a newer release (or --check for the available version)",
        listOf(
            flag("check", "only show the current and the latest version"),
            opt("version", "update to this version instead of the latest", "VERSION"),
            flag("allow-major", "allow an update to a different major version"),
            opt("timeout", "seconds to wait for the services after the switch (default 30)", "SECONDS"),
            opt("install-root", "the installation root (default: the parent of cringle.home)", "DIR"),
        ),
        needsServer = false,
    ) { env, a ->
        val root = a.option("install-root")?.let { Paths.get(it).toAbsolutePath().normalize() }
            ?: System.getProperty(Distribution.HOME_PROPERTY)?.takeIf { it.isNotBlank() }?.let { Paths.get(it).toRealPath().parent }
            ?: throw UsageException("not an installed distribution: cringle.home is not set; use --install-root <dir>")
        if (!Files.exists(root.resolve("current"))) throw UsageException("$root is not an installed distribution (no 'current' link)")
        val platform = Platform.current()
        val base = env.environment["CRINGLE_RELEASE_BASE_URL"]?.takeIf { it.isNotBlank() }
        val source = HttpReleaseSource(base ?: "https://github.com/Tim-Meyran/Cringle/releases/download", base == null)
        val services = if (platform == Platform.WINDOWS) WindowsServiceController() else SystemdServiceController()
        val links = linkSwitcherFor(platform)
        val timeout = Duration.ofSeconds(a.long("timeout") ?: 30)
        val update = SelfUpdate(root, Distribution.version(), platform, source, services, links, timeout, a.flag("allow-major"), Duration.ofSeconds(3))
        val lines = if (a.flag("check")) update.check() else update.update(a.option("version"))
        Output.Lines(lines, lines.map { mapOf("line" to it) })
    },
)

/**
 * The fingerprint a login pins the server to (Architecture 5.1: no trust on first use without the operator). The server is
 * asked for the fingerprint of its key first. With [given] it has to be exactly that key, otherwise nothing is stored and
 * the command stops with exit code 2 and both values; without it the operator has to confirm what the server shows
 * with [accept] (`--yes`), or the command prints the fingerprint and stops, so that it can be checked first.
 */
private fun pinnedFingerprint(server: String, given: String?, accept: Boolean): String {
    val actual = probeFingerprint(server, "server")
    val wanted = given?.trim()?.lowercase()
    if (wanted != null) {
        if (!cringle.common.PublicKeyFingerprint.pattern.matches(wanted)) {
            throw UsageException("--fingerprint is not a SHA-256 fingerprint (64 hexadecimal characters): '$given'")
        }
        if (wanted != actual) throw UsageException("the server at $server presents the key fingerprint $actual, not the expected $wanted; nothing was stored")
        return actual
    }
    if (!accept) {
        throw UsageException(
            "the server at $server presents the key fingerprint $actual; check it with the operator of the server and run again " +
                "with --fingerprint $actual (or --yes to accept it); nothing was stored",
        )
    }
    return actual
}

/** The fingerprint of the key that the TLS server at [address] (`host:port`) presents; [what] names it in the messages. */
private fun probeFingerprint(address: String, what: String): String {
    val host = address.substringBeforeLast(':', "")
    val port = address.substringAfterLast(':', "").toIntOrNull()
    if (host.isEmpty() || port == null) throw UsageException("the $what address '$address' has to be host:port")
    return try {
        cringle.common.TlsHelper.probeServerFingerprint(host, port)
    } catch (e: java.io.IOException) {
        // an SSLException is an IOException as well
        throw IllegalStateException("cannot reach the $what $address over TLS: ${e.message}")
    }
}
