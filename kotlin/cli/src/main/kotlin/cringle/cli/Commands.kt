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


/** The identities a home can hold: component name → (folder below the home, kind of the subject). */
private val CERT_COMPONENTS = linkedMapOf(
    "daemon" to ("daemon" to cringle.common.ComponentKind.DAEMON),
    "router" to ("router" to cringle.common.ComponentKind.ROUTER),
    "management" to ("management" to cringle.common.ComponentKind.MANAGEMENT),
)

/** The Cringle home of this call: the folder of the profile (`--home`, else `CRINGLE_HOME`, else `~/.cringle`). */
private fun certHome(env: Env): Path = env.profileFile.toAbsolutePath().parent

/** The identities of [component] (or of all) that exist in [home]. Never creates one. */
private fun existingIdentities(home: Path, component: String?): List<Pair<String, cringle.common.Identity>> {
    if (component != null && component !in CERT_COMPONENTS) throw UsageException("unknown component '$component': ${CERT_COMPONENTS.keys.joinToString(", ")}")
    return CERT_COMPONENTS.filterKeys { component == null || it == component }.mapNotNull { (name, place) ->
        val dir = home.resolve(place.first)
        if (Files.exists(dir.resolve("certs").resolve("identity.key"))) name to cringle.common.Identity.loadOrCreate(dir, place.second.commonName(name), renew = false) else null
    }
}

private fun certRow(name: String, identity: cringle.common.Identity): Map<String, Any?> = linkedMapOf(
    "component" to name,
    "subject" to identity.certificate.subjectX500Principal.name,
    "fingerprint" to identity.publicKeyFingerprint,
    "validUntil" to identity.certificate.notAfter.toInstant().toString(),
    "daysLeft" to identity.remaining().toDays(),
)

private fun opt(name: String, description: String, placeholder: String = "VALUE", repeatable: Boolean = false) = OptionSpec(name, description, true, repeatable, placeholder)

/** `https://host[:port]` from [url], or a [UsageException]: a login link needs an https address without path, query or fragment. */
private fun webBase(url: String): String {
    val uri = try {
        java.net.URI(url.trim())
    } catch (e: java.net.URISyntaxException) {
        throw UsageException("--web-url is not a URL: $url")
    }
    if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || !(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")) {
        throw UsageException("--web-url must be an https URL like https://host:port, without path, query or fragment")
    }
    return "https://" + uri.rawAuthority
}

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
    "checks" to assertionSummary(f),
)

/** What the assertions of a fabric say in one word: empty without assertions, `ok`, `n violated` or `unknown`. */
private fun assertionSummary(f: ManagedFabric): String {
    val states = f.info.assertionsList.map { it.state }
    val violated = states.count { it == cringle.engine.v1.AssertionState.ASSERTION_STATE_VIOLATED }
    return when {
        states.isEmpty() -> ""
        violated > 0 -> "$violated violated"
        states.all { it == cringle.engine.v1.AssertionState.ASSERTION_STATE_OK } -> "ok"
        else -> "unknown"
    }
}

private fun fabricDetail(f: ManagedFabric): Map<String, Any?> = fabricRow(f) + mapOf(
    "failure" to f.info.failure,
    "assertions" to f.info.assertionsList.map {
        linkedMapOf(
            "assertion" to it.id, "state" to it.state.pretty("ASSERTION_STATE_"), "since" to java.time.Instant.ofEpochSecond(it.since.seconds, it.since.nanos.toLong()).toString(),
            "detail" to it.detail,
        )
    },
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

/** The text form of a scoped role: `operator@machine:m1`. */
private fun scopedName(a: cringle.user.v1.RoleAssignment): String = "${roleName(a.role)}@${a.scope.kind.pretty("SCOPE_KIND_")}:${a.scope.name}"

/** The scope of `--scope`: `machine:<id>`, `project:<name>`, `fabric:<id>` or `function:<trust|plugin-trust|users>`. */
private fun scopeOf(text: String): cringle.user.v1.Scope {
    if (text == "global") throw UsageException("a global role is given with the roles of the user or group ('user create --role'), not with --scope")
    val kind = when (text.substringBefore(':', "")) {
        "machine" -> cringle.user.v1.ScopeKind.SCOPE_KIND_MACHINE
        "project" -> cringle.user.v1.ScopeKind.SCOPE_KIND_PROJECT
        "fabric" -> cringle.user.v1.ScopeKind.SCOPE_KIND_FABRIC
        "function" -> cringle.user.v1.ScopeKind.SCOPE_KIND_FUNCTION
        else -> throw UsageException("invalid scope '$text' (machine:<id>, project:<name>, fabric:<id> or function:<trust|plugin-trust|users>)")
    }
    return cringle.user.v1.Scope.newBuilder().setKind(kind).setName(text.substringAfter(':')).build()
}

private fun roleScope(a: Parsed, group: Boolean): cringle.user.v1.RoleScopeRequest {
    val scope = a.option("scope") ?: throw UsageException("--scope is required (machine:<id>, project:<name>, fabric:<id> or function:<trust|plugin-trust|users>)")
    val b = cringle.user.v1.RoleScopeRequest.newBuilder().setRole(roleOf(a.positional[1])).setScope(scopeOf(scope))
    if (group) b.group = a.positional[0] else b.userId = a.positional[0]
    return b.build()
}

private fun registryRoleScope(a: Parsed): cringle.user.v1.RoleScopeRequest {
    val scope = a.option("scope") ?: throw UsageException("--scope is required (machine:<id>, project:<name>, fabric:<id> or function:<trust|plugin-trust|users>)")
    return cringle.user.v1.RoleScopeRequest.newBuilder().setRegistry(a.positional[0]).setRole(roleOf(a.positional[1])).setScope(scopeOf(scope)).build()
}

private fun registryRow(r: cringle.user.v1.Registry): Map<String, Any?> = linkedMapOf(
    "name" to r.name,
    "fingerprint" to r.fingerprint,
    "roles" to r.rolesList.map(::roleName),
    "scopedRoles" to r.scopedRolesList.map(::scopedName),
)

private fun userRow(u: cringle.user.v1.User): Map<String, Any?> = linkedMapOf(
    "id" to u.id,
    "name" to u.name,
    "roles" to u.rolesList.map(::roleName),
    "groups" to u.groupsList,
    "effectiveRoles" to u.effectiveRolesList.map(::roleName),
    "scopedRoles" to u.effectiveScopedRolesList.map(::scopedName),
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

/** A duration such as `90s`, `30m`, `2h`, `7d`, in milliseconds. */
private fun parseDurationMs(text: String, option: String): Long {
    val m = Regex("(\\d+)([smhd])").matchEntire(text) ?: throw UsageException("--$option must be a duration like 90s, 30m, 2h or 7d")
    return m.groupValues[1].toLong() * 1000 * when (m.groupValues[2]) { "s" -> 1L; "m" -> 60L; "h" -> 3600L; else -> 86_400L }
}

/** A size such as `500k`, `10m`, `2g` or plain bytes, in bytes. */
private fun parseBytes(text: String, option: String): Long {
    val m = Regex("(\\d+)([kmg]?)", RegexOption.IGNORE_CASE).matchEntire(text) ?: throw UsageException("--$option must be a size like 500k, 10m, 2g or a number of bytes")
    return m.groupValues[1].toLong() * when (m.groupValues[2].lowercase()) { "k" -> 1024L; "m" -> 1024L * 1024; "g" -> 1024L * 1024 * 1024; else -> 1L }
}

private fun dwhKind(text: String): cringle.engine.v1.DwhKind = when (text.lowercase()) {
    "block" -> cringle.engine.v1.DwhKind.DWH_KIND_BLOCK
    "tether" -> cringle.engine.v1.DwhKind.DWH_KIND_TETHER
    else -> throw UsageException("the kind of a partition is block or tether")
}

private val retentionOptions = listOf(
    opt("max-age", "keep records for this long: 90s, 30m, 2h, 7d (default: no limit)", "DURATION"),
    opt("max-size", "keep at most this much: 500k, 10m, 2g or bytes (default: no limit)", "SIZE"),
)

private fun retentionOf(a: Parsed): cringle.engine.v1.DwhRetention = cringle.engine.v1.DwhRetention.newBuilder()
    .setMaxAgeMs(a.option("max-age")?.let { parseDurationMs(it, "max-age") } ?: 0)
    .setMaxBytes(a.option("max-size")?.let { parseBytes(it, "max-size") } ?: 0)
    .build()

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
    Command(listOf("shell"), "", "Interactive mode: reads one command per line from standard input until 'exit'; the global options apply to every command", needsServer = false) { _, _ -> Output.Message("") },
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
    Command(listOf("engine", "collect"), "<machine> <engine> on|off", "Switch the logging collector of the machine on or off for an engine, so that its log entries are kept when it is stopped", minArgs = 3) { env, a ->
        val on = when (a.positional[2].lowercase()) { "on" -> true; "off" -> false; else -> throw UsageException("give on or off") }
        env.m.setLogCollection(cringle.management.v1.SetLogCollectionRequest.newBuilder().setEngine(engineRef(a.positional[0], a.positional[1])).setEnabled(on).build())
        Output.Message("log collection of ${a.positional[0]}/${a.positional[1]} is ${if (on) "on" else "off"}")
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
            flag("no-blue-green", "stop the running fabrics of the project first instead of starting the new ones next to them"),
        ),
        1,
    ) { env, a ->
        val b = DeployProjectRequest.newBuilder().setProject(a.positional[0]).setVersionRange(a.option("version").orEmpty())
        if (a.flag("no-start")) b.start = false
        if (a.flag("relock")) b.relock = true
        if (a.flag("no-blue-green")) b.noBlueGreen = true
        val r = env.m.deploy(b.build())
        Output.Detail(linkedMapOf("project" to r.project, "version" to r.version, "strategy" to r.strategy, "fabrics" to r.fabricsList.map(::fabricRow)))
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
    Command(
        listOf("rollback"), "<project>", "Go back to an earlier version of a project (the one deployed before, or --to <version>), with the lock file it had",
        listOf(opt("to", "the version to go back to (default: the one deployed before the running one)", "VERSION")), minArgs = 1,
    ) { env, a ->
        val r = env.m.rollback(cringle.management.v1.RollbackRequest.newBuilder().setProject(a.positional[0]).setVersion(a.option("to").orEmpty()).build())
        Output.Detail(linkedMapOf("project" to r.project, "version" to r.version, "strategy" to r.strategy, "fabrics" to r.fabricsList.map(::fabricRow)))
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
                "fabric" to it.entry.fabric, "block" to it.entry.block, "source" to it.entry.source, "collected" to it.collected, "message" to it.entry.message,
            )
        }
        val lines = entries.map { "${it["time"]} ${it["level"].toString().uppercase().padEnd(5)} ${it["machine"]}/${it["engine"]} ${it["fabric"]}/${it["block"]}${(it["source"] as String).let { s -> if (s.isEmpty()) "" else " [$s]" }}${if (it["collected"] == true) " (collected)" else ""}: ${it["message"]}" } +
            r.problemsList.map { "warning: could not read logs of $it" }
        Output.Lines(lines, entries)
    },

    // --- data warehouse ---
    Command(listOf("dwh", "list"), "[fabric]", "List the partitions of the data warehouse with size and retention, of one fabric or of all", minArgs = 0, maxArgs = 1) { env, a ->
        val r = env.m.listDwhPartitions(cringle.management.v1.ListDwhPartitionsRequest.newBuilder().setFabric(a.positional.firstOrNull().orEmpty()).build())
        r.problemsList.forEach { env.warn("could not read the data warehouse of $it") }
        Output.Rows(
            r.partitionsList.map {
                linkedMapOf<String, Any?>(
                    "machine" to it.machineId, "engine" to it.engineId.value, "fabric" to it.partition.fabric,
                    "kind" to it.partition.kind.pretty("DWH_KIND_"), "name" to it.partition.name, "bytes" to it.partition.bytes,
                    "maxAgeMs" to it.partition.retention.maxAgeMs, "maxBytes" to it.partition.retention.maxBytes,
                )
            },
            "no partitions",
        )
    },
    Command(
        listOf("dwh", "query"), "<fabric> <block|tether> <name>", "Show the records of a partition: the newest, oldest first",
        listOf(opt("since", "only records since a time (2026-01-01T10:00:00Z) or a duration ago (30m, 2h, 1d)", "TIME"), opt("until", "only records until a time or a duration ago", "TIME"), opt("limit", "at most this many (default 1000)", "N")),
        minArgs = 3,
    ) { env, a ->
        val b = cringle.management.v1.QueryDwhRequest.newBuilder().setFabric(a.positional[0]).setKind(dwhKind(a.positional[1])).setName(a.positional[2]).setLimit((a.long("limit") ?: 0).toInt())
        a.option("since")?.let { b.since = parseSince(it) }
        a.option("until")?.let { b.until = parseSince(it) }
        val r = env.m.queryDwh(b.build())
        Output.Rows(
            r.recordsList.map {
                linkedMapOf<String, Any?>("time" to it.timestamp.iso(), "tags" to it.tagsMap.toSortedMap(), "payload" to kotlinx.serialization.json.Json.parseToJsonElement(it.payloadJson))
            },
            "no records",
        )
    },
    Command(
        listOf("dwh", "record"), "<fabric> on|off", "Switch the recording of all typed tethers of a fabric on or off (tethers with a record of their own are recorded anyway)",
        retentionOptions, 2,
    ) { env, a ->
        val on = when (a.positional[1].lowercase()) { "on" -> true; "off" -> false; else -> throw UsageException("give on or off") }
        env.m.setRecording(cringle.management.v1.SetRecordingRequest.newBuilder().setFabric(a.positional[0]).setAll(on).setDefaultRetention(retentionOf(a)).build())
        Output.Message("recording of ${a.positional[0]} is ${if (on) "on" else "off"}")
    },
    // --- remote debugging (#323) ---
    Command(
        listOf("debug", "break"), "<fabric> <tether> on|off",
        "Set or remove a breakpoint on a tether (as 'dwh list' names it, for example 'c.out -> s.in'): values on it are held back before the receiving block gets them; off releases them",
        minArgs = 3, maxArgs = 3,
    ) { env, a ->
        val on = when (a.positional[2].lowercase()) { "on" -> true; "off" -> false; else -> throw UsageException("give on or off") }
        env.m.setBreakpoint(cringle.management.v1.SetBreakpointRequest.newBuilder().setFabric(a.positional[0]).setTether(a.positional[1]).setEnabled(on).build())
        Output.Message("breakpoint on ${a.positional[1]} of ${a.positional[0]} is ${if (on) "set" else "removed"}")
    },
    Command(listOf("debug", "state"), "<fabric>", "Show the breakpoints of a fabric and the values held at them (the output of the sender, the input of the receiver)", minArgs = 1, maxArgs = 1) { env, a ->
        val s = env.m.getDebugState(cringle.management.v1.GetDebugStateRequest.newBuilder().setFabric(a.positional[0]).build())
        Output.Rows(
            s.heldList.map {
                linkedMapOf<String, Any?>("id" to it.id, "tether" to it.tether, "from" to it.from, "to" to it.to, "kind" to it.kind, "since" to java.time.Instant.ofEpochMilli(it.sinceEpochMillis).toString(), "payload" to it.payload)
            },
            "no value is held; breakpoints: " + s.breakpointsList.ifEmpty { listOf("none") }.joinToString(", "),
        )
    },
    Command(
        listOf("debug", "resume"), "<fabric> [<tether>]", "Release the held values of a fabric (of one tether, or of all); --one releases only the oldest and keeps the breakpoints, so the next value is held again",
        listOf(flag("one", "release only the oldest value (step)")), 1, 2,
    ) { env, a ->
        val r = env.m.resumeFabric(cringle.management.v1.ResumeFabricRequest.newBuilder().setFabric(a.positional[0]).setTether(a.positional.getOrElse(1) { "" }).setOne(a.flag("one")).build())
        Output.Message("released ${r.released} value(s)", mapOf("released" to r.released))
    },
    Command(
        listOf("dwh", "retention"), "<fabric> <block|tether> <name>", "Set the retention of a partition (no option: no limit)", retentionOptions, 3,
    ) { env, a ->
        env.m.setDwhRetention(
            cringle.management.v1.SetDwhRetentionRequest.newBuilder().setFabric(a.positional[0]).setKind(dwhKind(a.positional[1])).setName(a.positional[2]).setRetention(retentionOf(a)).build(),
        )
        Output.Message("retention of ${a.positional[2]} of ${a.positional[0]} is set")
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
        Output.Detail(linkedMapOf("name" to g.name, "roles" to g.rolesList.map(::roleName), "scopedRoles" to g.scopedRolesList.map(::scopedName)))
    },
    Command(listOf("group", "list"), "", "List the groups") { env, _ ->
        Output.Rows(env.users.listGroups(ListGroupsRequest.getDefaultInstance()).groupsList.map { linkedMapOf<String, Any?>("name" to it.name, "roles" to it.rolesList.map(::roleName), "scopedRoles" to it.scopedRolesList.map(::scopedName)) }, "no groups")
    },
    Command(
        listOf("user", "grant"), "<user-id> <role>", "Give a user a role for one object only (a machine, a project, a fabric or a framework function)",
        listOf(opt("scope", "machine:<id>, project:<name>, fabric:<id> or function:<trust|plugin-trust|users>", "SCOPE")), minArgs = 2, maxArgs = 2,
    ) { env, a ->
        val request = roleScope(a, group = false)
        Output.Detail(userRow(env.users.grantRole(request).user))
    },
    Command(
        listOf("user", "revoke"), "<user-id> <role>", "Take a scoped role of a user back",
        listOf(opt("scope", "the scope the role was given for", "SCOPE")), minArgs = 2, maxArgs = 2,
    ) { env, a ->
        Output.Detail(userRow(env.users.revokeRole(roleScope(a, group = false)).user))
    },
    Command(
        listOf("group", "grant"), "<group> <role>", "Give a group a role for one object only; its members have it",
        listOf(opt("scope", "machine:<id>, project:<name>, fabric:<id> or function:<trust|plugin-trust|users>", "SCOPE")), minArgs = 2, maxArgs = 2,
    ) { env, a ->
        val g = env.users.grantRole(roleScope(a, group = true)).group
        Output.Detail(linkedMapOf("name" to g.name, "roles" to g.rolesList.map(::roleName), "scopedRoles" to g.scopedRolesList.map(::scopedName)))
    },
    Command(
        listOf("group", "revoke"), "<group> <role>", "Take a scoped role of a group back",
        listOf(opt("scope", "the scope the role was given for", "SCOPE")), minArgs = 2, maxArgs = 2,
    ) { env, a ->
        val g = env.users.revokeRole(roleScope(a, group = true)).group
        Output.Detail(linkedMapOf("name" to g.name, "roles" to g.rolesList.map(::roleName), "scopedRoles" to g.scopedRolesList.map(::scopedName)))
    },
    Command(
        listOf("token", "create"), "<user-id>", "Create a token for a user; the value is shown once",
        listOf(
            opt("label", "what the token is for", "LABEL"), opt("ttl-days", "lifetime in days; default: does not expire", "DAYS"),
            opt("web-url", "public https address of the web interface: adds the login link <url>/login#token=...", "URL"),
            flag("qr", "also draw the QR code of the login link in the terminal (needs --web-url)"),
        ), 1,
    ) { env, a ->
        val base = a.option("web-url")?.let(::webBase)
        if (a.flag("qr") && base == null) throw UsageException("--qr needs --web-url (the QR code holds the login link)")
        val r = env.users.createToken(
            CreateTokenRequest.newBuilder().setUserId(a.positional[0]).setLabel(a.option("label").orEmpty()).setTtlSeconds((a.long("ttl-days") ?: 0) * 86_400).build(),
        )
        val fields = linkedMapOf<String, Any?>("token" to r.token, "id" to r.info.id, "user" to r.info.userId, "label" to r.info.label, "expires" to r.info.expiresAt.iso())
        if (base != null) fields["loginLink"] = "$base/login#token=${r.token}"
        if (a.flag("qr")) {
            // the QR code is for the eyes: the JSON output has the fields only
            val qr = cringle.common.qr.QrCode.encode(fields["loginLink"] as String).toText()
            Output.Lines(fields.map { "${it.key}: ${it.value}" } + "" + qr.trimEnd('\n').split('\n'), listOf(fields))
        } else {
            Output.Detail(fields)
        }
    },
    // --- registries of other sites (federation, #295) ---
    Command(listOf("registry", "key"), "", "Show the public key with which this site signs federated tokens (the other site enters it with 'registry trust')") { env, _ ->
        val k = env.users.getRegistryKey(cringle.user.v1.GetRegistryKeyRequest.getDefaultInstance())
        Output.Detail(linkedMapOf("fingerprint" to k.fingerprint, "publicKey" to k.publicKeyPem))
    },
    Command(
        listOf("registry", "trust"), "<name>", "Trust the registry of another site: its users are accepted as user@<name> (compare the fingerprint with the other site)",
        listOf(
            opt("key-file", "PEM file with the public key of the other registry, or its certificate", "FILE"),
            opt("role", "role of every user of that registry: admin, operator or viewer (repeatable; default: none)", "ROLE", repeatable = true),
        ),
        1,
    ) { env, a ->
        val file = a.option("key-file") ?: throw UsageException("--key-file is required")
        val roles = a.options("role").map { roleOf(it) }
        val r = env.users.trustRegistry(
            cringle.user.v1.TrustRegistryRequest.newBuilder().setName(a.positional[0]).setPublicKeyPem(Files.readString(Paths.get(file))).addAllRoles(roles).build(),
        ).registry
        Output.Detail(registryRow(r))
    },
    Command(listOf("registry", "list"), "", "List the trusted registries with their rights") { env, _ ->
        Output.Rows(env.users.listRegistries(cringle.user.v1.ListRegistriesRequest.getDefaultInstance()).registriesList.map(::registryRow), "no trusted registry")
    },
    Command(listOf("registry", "untrust"), "<name>", "Stop trusting a registry; its tokens are refused from now on", minArgs = 1) { env, a ->
        env.users.untrustRegistry(cringle.user.v1.UntrustRegistryRequest.newBuilder().setName(a.positional[0]).build())
        Output.Message("no longer trusting registry ${a.positional[0]}")
    },
    Command(
        listOf("registry", "grant"), "<name> <role>", "Give all users of a trusted registry a role for one object only",
        listOf(opt("scope", "machine:<id>, project:<name>, fabric:<id> or function:<trust|plugin-trust|users>", "SCOPE")), minArgs = 2, maxArgs = 2,
    ) { env, a ->
        Output.Detail(registryRow(env.users.grantRole(registryRoleScope(a)).registry))
    },
    Command(
        listOf("registry", "revoke"), "<name> <role>", "Take a scoped role of a registry back",
        listOf(opt("scope", "the scope the role was given for", "SCOPE")), minArgs = 2, maxArgs = 2,
    ) { env, a ->
        Output.Detail(registryRow(env.users.revokeRole(registryRoleScope(a)).registry))
    },
    Command(
        listOf("registry", "issue-token"), "<user>", "Issue a token for a user of this site that a registry which trusts this one accepts as <user>@<name>; shown once",
        listOf(
            opt("as", "the name under which the other site has entered this registry", "NAME"),
            opt("ttl", "lifetime like 2h or 7d, at most 30d (default 24h)", "DURATION"),
        ),
        1,
    ) { env, a ->
        val name = a.option("as") ?: throw UsageException("--as is required: the name the other site uses for this registry")
        val seconds = a.option("ttl")?.let { parseDurationMs(it, "ttl") / 1000 } ?: 0
        val r = env.users.issueFederatedToken(
            cringle.user.v1.IssueFederatedTokenRequest.newBuilder().setRegistryName(name).setUser(a.positional[0]).setTtlSeconds(seconds).build(),
        )
        Output.Detail(linkedMapOf("token" to r.token, "user" to "${a.positional[0]}@$name", "expires" to r.expiresAt.iso()))
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

    // --- certificates of the local home ---
    Command(
        listOf("cert", "status"), "[--component <name>]", "Show the certificates of the daemon, router and management server in the local home with the days left",
        listOf(opt("component", "daemon, router or management (default: all)", "NAME")),
        needsServer = false,
    ) { env, a ->
        Output.Rows(existingIdentities(certHome(env), a.option("component")).map { (name, identity) -> certRow(name, identity) }, "no identity in ${certHome(env)}")
    },
    Command(
        listOf("cert", "renew"), "[--component <name>] [--force]", "Renew the certificates in the local home that end within 30 days (or all with --force); the key and so the trust stay",
        listOf(
            opt("component", "daemon, router or management (default: all)", "NAME"),
            flag("force", "renew even if the certificate is valid for longer"),
        ),
        needsServer = false,
    ) { env, a ->
        val renewed = ArrayList<Map<String, Any?>>()
        for ((name, identity) in existingIdentities(certHome(env), a.option("component"))) {
            val due = if (a.flag("force")) identity.renew().let { true } else identity.renewIfDue()
            if (due) renewed += certRow(name, identity)
        }
        if (renewed.isNotEmpty()) env.warn("running components keep the certificate they started with; restart them to use the new one")
        Output.Rows(renewed, "nothing to renew: every certificate is valid for more than 30 days")
    },

    // --- installation ---
    Command(
        listOf("setup"), "[--bind <loopback|all>] [--components LIST] [--port N] [--web-port N] [--repository-port N] [--daemon-port N]",
        "Change the address and the ports of the installed services (without options: ask for each, an empty answer keeps the value) and restart them",
        listOf(
            opt("bind", "loopback (default) or all (every network interface)", "VALUE"),
            opt("components", "what the daemon runs besides itself: management, repository, both (comma) or none", "LIST"),
            opt("port", "port of the management server (default 7500)", "PORT"),
            opt("web-port", "port of the web interface (default 8443)", "PORT"),
            opt("repository-port", "port of the repository (default 7600)", "PORT"),
            opt("daemon-port", "port of the daemon (default 7400)", "PORT"),
            opt("config-file", "the settings file (default: /etc/cringle/cringle.env, on Windows service/cringle-daemon.xml of the installation)", "FILE"),
            opt("install-root", "the installation root (default: the parent of cringle.home)", "DIR"),
            flag("show", "only show the current settings"),
            flag("no-restart", "do not restart the services"),
        ),
        needsServer = false,
    ) { env, a ->
        val platform = Platform.current()
        val root = a.option("install-root")?.let { Paths.get(it).toAbsolutePath().normalize() }
            ?: System.getProperty(Distribution.HOME_PROPERTY)?.takeIf { it.isNotBlank() }?.let { Paths.get(it).toRealPath().parent }
        val file = a.option("config-file")?.let { Paths.get(it) } ?: ServiceSettings.defaultFile(platform, root)
        if (!Files.isRegularFile(file)) throw UsageException("$file does not exist: Cringle is not installed here (use --config-file <file>)")
        val current = ServiceSettings.read(file, platform)
        val given = linkedMapOf(
            ServiceSettings.BIND to a.option("bind"), ServiceSettings.COMPONENTS to a.option("components"),
            ServiceSettings.MANAGEMENT_PORT to a.option("port"), ServiceSettings.WEB_PORT to a.option("web-port"),
            ServiceSettings.REPOSITORY_PORT to a.option("repository-port"), ServiceSettings.DAEMON_PORT to a.option("daemon-port"),
        )
        val changes = LinkedHashMap<String, String>()
        if (a.flag("show")) {
            // nothing to change
        } else if (given.values.all { it == null }) {
            val labels = mapOf(
                ServiceSettings.BIND to "Listen on (loopback or all)", ServiceSettings.COMPONENTS to "Components (management, repository, both separated by a comma, or none)",
                ServiceSettings.MANAGEMENT_PORT to "Port of the management server",
                ServiceSettings.WEB_PORT to "Port of the web interface", ServiceSettings.REPOSITORY_PORT to "Port of the repository", ServiceSettings.DAEMON_PORT to "Port of the daemon",
            )
            for (key in given.keys) {
                System.err.print("${labels.getValue(key)} [${current.getValue(key)}]: ")
                System.err.flush()
                val answer = env.readSecret()?.trim().orEmpty()
                if (answer.isNotEmpty() && answer != current[key]) changes[key] = answer
            }
        } else {
            for ((key, value) in given) if (value != null) changes[key] = value
        }
        for ((key, value) in changes) ServiceSettings.problem(key, value)?.let { throw UsageException("${key.removePrefix("CRINGLE_").lowercase()}: $it") }
        val result = LinkedHashMap<String, Any?>()
        if (changes.isNotEmpty()) {
            ServiceSettings.write(file, changes)
            if (a.flag("no-restart")) {
                env.warn("the services keep the old settings until they are restarted")
            } else {
                val services = if (platform == Platform.WINDOWS) WindowsServiceController() else SystemdServiceController()
                val name = if (platform == Platform.WINDOWS) "cringle-daemon" else "cringle-daemon.service"
                if (services.isActive(name)) {
                    services.restart(name)
                    result["restarted"] = name
                }
            }
        }
        val now = current + changes
        result["file"] = file.toString()
        result["listen"] = now.getValue(ServiceSettings.BIND)
        result["components"] = ServiceSettings.normalizeComponents(now.getValue(ServiceSettings.COMPONENTS)) ?: now.getValue(ServiceSettings.COMPONENTS)
        result["managementPort"] = now.getValue(ServiceSettings.MANAGEMENT_PORT)
        result["webPort"] = now.getValue(ServiceSettings.WEB_PORT)
        result["repositoryPort"] = now.getValue(ServiceSettings.REPOSITORY_PORT)
        result["daemonPort"] = now.getValue(ServiceSettings.DAEMON_PORT)
        Output.Detail(result)
    },
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
