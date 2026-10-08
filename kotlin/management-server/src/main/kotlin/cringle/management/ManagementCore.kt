// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.common.Identity
import cringle.common.PublicKeyFingerprint
import cringle.common.TrustEntry
import cringle.common.TrustKind
import cringle.common.TlsHelper
import cringle.common.TrustStore
import cringle.common.v1.EngineId
import cringle.common.v1.FabricId
import cringle.daemon.v1.CreateEngineRequest
import cringle.daemon.v1.DaemonServiceGrpcKt.DaemonServiceCoroutineStub
import cringle.daemon.v1.DeleteEngineRequest
import cringle.daemon.v1.EngineInfo
import cringle.daemon.v1.EngineProcessState
import cringle.daemon.v1.EngineRequest
import cringle.daemon.v1.ListEnginesRequest
import cringle.engine.v1.DeployFabricRequest
import cringle.engine.v1.EngineManagementServiceGrpcKt.EngineManagementServiceCoroutineStub
import cringle.engine.v1.FabricInfo
import cringle.engine.v1.FabricRequest
import cringle.engine.v1.FabricRuntimeState
import cringle.engine.v1.GetStatusRequest
import cringle.engine.v1.GetStatusResponse
import cringle.engine.v1.ListFabricsRequest
import cringle.engine.v1.LogEntry
import cringle.engine.v1.QueryLogsRequest
import cringle.repository.v1.RepositoryServiceGrpcKt.RepositoryServiceCoroutineStub
import cringle.packaging.LockFile
import cringle.packaging.PackageFormatException
import cringle.packaging.VersionRange
import cringle.router.users.AuthInterceptor
import cringle.router.v1.ListTrustRequest
import cringle.router.v1.RegistryServiceGrpcKt.RegistryServiceCoroutineStub
import cringle.router.v1.RevokeRemoteRouterRequest
import cringle.router.v1.TrustEntryInfo
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An error of the ManagementServer with the gRPC status it is reported as. */
public class ManagementException(public val code: Status.Code, message: String) : RuntimeException(message)

/** The outcome of [ManagementCore.recover]. */
public data class RecoveryReport(val enginesStarted: Int, val fabricsRestored: Int, val problems: List<String>)

/** A machine with its reachability. */
public data class MachineView(val record: MachineRecord, val reachable: Boolean, val lastError: String)

/** An Engine as seen by the ManagementServer: Daemon view, own status (if reachable) and setting. */
public data class EngineView(
    val machine: String,
    val process: EngineInfo,
    val status: GetStatusResponse?,
    val autostart: Boolean,
    val roles: List<String> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
)

/** The outcome of [ManagementCore.deploy]. */
public data class DeployResult(val project: String, val version: String, val lock: String, val fabrics: List<FabricView>)

/** A fabric with the wish of the ManagementServer. */
public data class FabricView(val machine: String, val engineId: String, val info: FabricInfo, val desiredRunning: Boolean)

/** A log entry with its origin. */
public data class LogView(val machine: String, val engineId: String, val entry: LogEntry)

/** The numbers of one Engine with its origin. */
public data class MetricsView(val machine: String, val engineId: String, val metrics: cringle.engine.v1.EngineMetrics)

/** The result of a metrics query over several Engines. */
public data class MetricsResult(val metrics: List<MetricsView>, val problems: List<String>)

/** The result of a log query over several Engines. */
public data class LogResult(val entries: List<LogView>, val problems: List<String>)

/**
 * The service layer of the ManagementServer: it knows the machines, talks to their Daemons and Engines and remembers
 * the Engines and fabrics it created, so that [recover] can bring them back after a restart. The gRPC facade is
 * [ManagementServer]; a REST facade for the WebUI can use this class as well.
 *
 * Every channel to a Daemon, an Engine, the Repository or a Router is mutual TLS with the [identity] and the [trustStore]
 * of the ManagementServer (`<home>/management`): a peer is only talked to if its key is in the trust store, and it has to
 * trust the key of the ManagementServer. An Engine management API listens on the loopback interface of its machine, so
 * only Engines on the machine of the ManagementServer are reachable for now.
 */
public class ManagementCore(
    private val store: ManagementStore,
    /** The identity the ManagementServer presents to Daemons, Engines, Repositories and Routers. */
    public val identity: Identity,
    /** The peers the ManagementServer talks to: their keys have to be in it. */
    public val trustStore: TrustStore,
    /** Address of the Repository that is responsible for machines without their own; `null` if there is none. */
    public val defaultRepository: String? = null,
    /** Token the ManagementServer uses at the Repository. */
    private val repositoryToken: String? = null,
    /** Address of the Router of the ManagementServer machine; `null` if there is none. */
    public val routerAddress: String? = null,
    private val probeTimeoutSeconds: Long = 3,
) : AutoCloseable {
    private val lock = Any()
    private var data: ManagementData = store.load()
    private val channels = ConcurrentHashMap<String, ManagedChannel>()

    private fun <T> update(change: (ManagementData) -> Pair<ManagementData, T>): T = synchronized(lock) {
        val (next, result) = change(data)
        if (next != data) {
            store.save(next)
            data = next
        }
        result
    }

    private fun snapshot(): ManagementData = synchronized(lock) { data }

    /**
     * Binds the service dependency [service] of [project] to the fabrics [targets] in the order of preference (#171, #173);
     * replaces an earlier binding of the pair. A target has to be a fabric that was deployed through this ManagementServer.
     * The running fabrics of [project] that use the service switch to the new list without a redeploy, and the
     * allow-lists of the old and the new targets are updated; what fails is reported after the binding is stored.
     */
    public suspend fun bind(project: String, service: String, targets: List<String>): BindingRecord {
        if (project.isBlank()) throw ManagementException(Status.Code.INVALID_ARGUMENT, "a project name is required")
        cringle.packaging.PackageNames.nameProblem(service)?.let { throw ManagementException(Status.Code.INVALID_ARGUMENT, "service '$service': $it") }
        if (targets.isEmpty()) throw ManagementException(Status.Code.INVALID_ARGUMENT, "a binding needs at least one target fabric")
        if (targets.distinct().size != targets.size) throw ManagementException(Status.Code.INVALID_ARGUMENT, "a fabric is named more than once: $targets")
        val record = BindingRecord(project, service, targets)
        val old = update { d ->
            targets.firstOrNull { t -> d.fabrics.none { it.fabricId == t } }?.let { throw ManagementException(Status.Code.NOT_FOUND, "fabric '$it' is not known") }
            val before = d.bindings.firstOrNull { it.consumerProject == project && it.service == service }?.targets.orEmpty()
            d.copy(bindings = d.bindings.filterNot { it.consumerProject == project && it.service == service } + record) to before
        }
        applyBindingChange(project, service, old + targets)
        return record
    }

    /** Removes the binding of [service] for [project]. */
    public suspend fun unbind(project: String, service: String) {
        val old = update { d ->
            val before = d.bindings.firstOrNull { it.consumerProject == project && it.service == service }
                ?: throw ManagementException(Status.Code.NOT_FOUND, "service '$service' of project '$project' is not bound")
            d.copy(bindings = d.bindings - before) to before.targets
        }
        // the running fabrics keep their instances until they are deployed again, but the services no longer accept them
        for (fabricId in old) {
            try {
                syncCallers(fabricId)
            } catch (e: StatusException) {
                throw ManagementException(Status.Code.INTERNAL, "unbound, but the allow-list of $fabricId could not be updated: ${e.status.description ?: e.status.code.name}")
            }
        }
    }

    /** The bindings of [project], or of all projects if it is blank, sorted by project and service. */
    public fun listBindings(project: String): List<BindingRecord> =
        snapshot().bindings.filter { project.isBlank() || it.consumerProject == project }.sortedWith(compareBy({ it.consumerProject }, { it.service }))

    private fun channel(address: String): ManagedChannel = channels.computeIfAbsent(address) {
        io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.forTarget(address).sslContext(TlsHelper.channelCredentials(identity, trustStore)).build()
    }

    private fun machine(id: String): MachineRecord =
        snapshot().machines.firstOrNull { it.id == id } ?: throw ManagementException(Status.Code.NOT_FOUND, "machine '$id' is not known")

    private fun daemon(m: MachineRecord) = DaemonServiceCoroutineStub(channel(m.daemonAddress))

    private fun engineApi(m: MachineRecord, port: Int) = EngineManagementServiceCoroutineStub(channel("${m.host}:$port"))

    private fun probe(stub: DaemonServiceCoroutineStub) = stub.withDeadlineAfter(probeTimeoutSeconds, TimeUnit.SECONDS)

    private fun engineId(id: String): EngineId = EngineId.newBuilder().setValue(id).build()

    // --- Machines ---

    /** Adds a machine. Reachability is reported, but not required. */
    public suspend fun addMachine(id: String, daemonAddress: String, host: String?, repositoryAddress: String?): MachineView {
        if (!MACHINE_ID.matches(id)) throw ManagementException(Status.Code.INVALID_ARGUMENT, "machine id '$id' must match ${MACHINE_ID.pattern}")
        if (!ADDRESS.matches(daemonAddress)) throw ManagementException(Status.Code.INVALID_ARGUMENT, "daemon address '$daemonAddress' must be host:port")
        val record = MachineRecord(id, daemonAddress, host?.takeIf { it.isNotEmpty() } ?: daemonAddress.substringBeforeLast(':'), repositoryAddress?.takeIf { it.isNotEmpty() })
        update { d ->
            if (d.machines.any { it.id == id }) throw ManagementException(Status.Code.ALREADY_EXISTS, "machine '$id' is already known")
            d.copy(machines = d.machines + record) to Unit
        }
        return view(record)
    }

    /** Forgets a machine and what was recorded for it. */
    public fun removeMachine(id: String) {
        machine(id)
        update { d -> d.copy(machines = d.machines.filter { it.id != id }, engines = d.engines.filter { it.machine != id }, fabrics = d.fabrics.filter { it.machine != id }) to Unit }
    }

    private suspend fun view(m: MachineRecord): MachineView = try {
        probe(daemon(m)).listEngines(ListEnginesRequest.getDefaultInstance())
        MachineView(m, true, "")
    } catch (e: StatusException) {
        MachineView(m, false, e.status.description ?: e.status.code.name)
    }

    /** All machines, probed concurrently. */
    public suspend fun listMachines(): List<MachineView> = coroutineScope {
        snapshot().machines.map { m -> async { view(m) } }.awaitAll()
    }

    // --- Engines ---

    /** Creates an Engine through the Daemon of [machineId]; it is not started. */
    public suspend fun createEngine(
        machineId: String,
        engineId: String?,
        name: String?,
        autostart: Boolean,
        roles: List<String> = emptyList(),
        labels: Map<String, String> = emptyMap(),
    ): EngineView {
        val m = machine(machineId)
        val info = daemon(m).createEngine(
            CreateEngineRequest.newBuilder().setEngineId(engineId.orEmpty()).setName(name.orEmpty()).build(),
        )
        update { d ->
            d.copy(engines = d.engines.filter { !(it.machine == machineId && it.engineId == info.engineId.value) } + EngineRecord(machineId, info.engineId.value, autostart, roles.distinct(), labels)) to Unit
        }
        return view(machineId, info, null)
    }

    private fun record(machineId: String, id: String): EngineRecord? = snapshot().engines.firstOrNull { it.machine == machineId && it.engineId == id }

    private fun autostart(machineId: String, id: String): Boolean = record(machineId, id)?.autostart ?: false

    private fun view(machineId: String, info: EngineInfo, status: GetStatusResponse?): EngineView {
        val r = record(machineId, info.engineId.value)
        return EngineView(machineId, info, status, r?.autostart ?: false, r?.roles ?: emptyList(), r?.labels ?: emptyMap())
    }

    /** Sets the roles and labels of an Engine that was created through this server. */
    public suspend fun setEngineTags(machineId: String, id: String, roles: List<String>, labels: Map<String, String>): EngineView {
        val m = machine(machineId)
        val info = daemon(m).getEngine(EngineRequest.newBuilder().setEngineId(engineId(id)).build())
        update { d ->
            val existing = d.engines.firstOrNull { it.machine == machineId && it.engineId == id }
            val next = (existing ?: EngineRecord(machineId, id, false)).copy(roles = roles.distinct(), labels = labels)
            d.copy(engines = d.engines.filter { it !== existing } + next) to Unit
        }
        return view(machineId, info, null)
    }

    /** Starts an Engine and waits until it is up. */
    public suspend fun startEngine(machineId: String, id: String): EngineView {
        val m = machine(machineId)
        val info = daemon(m).startEngine(EngineRequest.newBuilder().setEngineId(engineId(id)).build())
        return view(machineId, info, statusOf(m, info))
    }

    /** Stops an Engine. */
    public suspend fun stopEngine(machineId: String, id: String): EngineView {
        val m = machine(machineId)
        val info = daemon(m).stopEngine(EngineRequest.newBuilder().setEngineId(engineId(id)).build())
        return view(machineId, info, null)
    }

    /** Stops and removes an Engine and forgets its fabrics. */
    public suspend fun deleteEngine(machineId: String, id: String, deleteData: Boolean) {
        val m = machine(machineId)
        daemon(m).deleteEngine(DeleteEngineRequest.newBuilder().setEngineId(engineId(id)).setDeleteData(deleteData).build())
        update { d -> d.copy(engines = d.engines.filter { !(it.machine == machineId && it.engineId == id) }, fabrics = d.fabrics.filter { !(it.machine == machineId && it.engineId == id) }) to Unit }
    }

    private suspend fun statusOf(m: MachineRecord, info: EngineInfo): GetStatusResponse? {
        if (info.state != EngineProcessState.ENGINE_PROCESS_STATE_RUNNING || info.managementPort == 0) return null
        return try {
            engineApi(m, info.managementPort).withDeadlineAfter(probeTimeoutSeconds, TimeUnit.SECONDS).getStatus(GetStatusRequest.getDefaultInstance())
        } catch (e: StatusException) {
            null
        }
    }

    /** The Engines of [machineId], or of all machines. Unreachable machines are skipped when listing all. */
    public suspend fun listEngines(machineId: String?): List<EngineView> {
        val machines = if (machineId.isNullOrEmpty()) snapshot().machines else listOf(machine(machineId))
        return machines.flatMap { m ->
            try {
                daemon(m).listEngines(ListEnginesRequest.getDefaultInstance()).enginesList.map { view(m.id, it, null) }
            } catch (e: StatusException) {
                if (machineId.isNullOrEmpty()) emptyList() else throw e
            }
        }
    }

    /** One Engine with its own status. */
    public suspend fun getEngine(machineId: String, id: String): EngineView {
        val m = machine(machineId)
        val info = daemon(m).getEngine(EngineRequest.newBuilder().setEngineId(engineId(id)).build())
        return view(machineId, info, statusOf(m, info))
    }

    private suspend fun runningEngine(machineId: String, id: String): EngineManagementServiceCoroutineStub {
        val m = machine(machineId)
        val info = daemon(m).getEngine(EngineRequest.newBuilder().setEngineId(engineId(id)).build())
        if (info.state != EngineProcessState.ENGINE_PROCESS_STATE_RUNNING || info.managementPort == 0) {
            throw ManagementException(Status.Code.FAILED_PRECONDITION, "engine '$id' on machine '$machineId' is not running")
        }
        return engineApi(m, info.managementPort)
    }

    // --- Fabrics ---

    private fun fabricRef(id: String) = FabricRequest.newBuilder().setFabricId(FabricId.newBuilder().setValue(id)).build()

    private fun desired(machineId: String, engineId: String, fabricId: String): Boolean =
        snapshot().fabrics.firstOrNull { it.machine == machineId && it.engineId == engineId && it.fabricId == fabricId }?.desiredRunning ?: false

    private fun setDesired(machineId: String, engineId: String, fabricId: String, running: Boolean) = update { d ->
        d.copy(fabrics = d.fabrics.map { if (it.machine == machineId && it.engineId == engineId && it.fabricId == fabricId) it.copy(desiredRunning = running) else it }) to Unit
    }

    /**
     * Deploys a fabric on a running Engine and remembers it. The request is stored in `state.json` as it was passed in
     * (for [recover]), so a `source.token` the caller put into it is stored in plain text; [deploy] never sets one,
     * it is added by [withToken] only when a request is sent.
     */
    public suspend fun deployFabric(machineId: String, id: String, request: DeployFabricRequest, start: Boolean): FabricView {
        val api = runningEngine(machineId, id)
        val fabricId = request.fabricId.value
        var info = api.deployFabric(withToken(request))
        update { d ->
            d.copy(fabrics = d.fabrics.filter { !(it.machine == machineId && it.engineId == id && it.fabricId == fabricId) } + FabricRecord(machineId, id, fabricId, request.toByteArray(), false)) to Unit
        }
        applyRecording(api, fabricId)
        if (start) {
            info = api.startFabric(fabricRef(fabricId))
            setDesired(machineId, id, fabricId, true)
        }
        return FabricView(machineId, id, info, start)
    }

    /**
     * Adds the token of the ManagementServer for the Repository to a request that is sent to an Engine. This token is
     * not stored by the ManagementServer itself (it comes from its start arguments); a token that the caller put into
     * the request is kept, see [deployFabric].
     */
    private fun withToken(request: DeployFabricRequest): DeployFabricRequest =
        if (request.hasSource() && repositoryToken != null) request.toBuilder().apply { sourceBuilder.token = repositoryToken }.build() else request

    /** Starts a fabric and remembers that it should run. */
    public suspend fun startFabric(machineId: String, id: String, fabricId: String): FabricView {
        val info = runningEngine(machineId, id).startFabric(fabricRef(fabricId))
        setDesired(machineId, id, fabricId, true)
        return FabricView(machineId, id, info, true)
    }

    /** Stops a fabric and remembers that it should not run. */
    public suspend fun stopFabric(machineId: String, id: String, fabricId: String): FabricView {
        val info = runningEngine(machineId, id).stopFabric(fabricRef(fabricId))
        setDesired(machineId, id, fabricId, false)
        return FabricView(machineId, id, info, false)
    }

    /** Removes a fabric from its Engine and forgets it. */
    public suspend fun removeFabric(machineId: String, id: String, fabricId: String) {
        runningEngine(machineId, id).removeFabric(fabricRef(fabricId))
        update { d -> d.copy(fabrics = d.fabrics.filter { !(it.machine == machineId && it.engineId == id && it.fabricId == fabricId) }) to Unit }
    }

    /** One fabric. */
    public suspend fun getFabric(machineId: String, id: String, fabricId: String): FabricView =
        FabricView(machineId, id, runningEngine(machineId, id).getFabricStatus(fabricRef(fabricId)), desired(machineId, id, fabricId))

    /** The fabrics of one Engine, or of all running Engines of a machine, or of all machines. */
    public suspend fun listFabrics(machineId: String?, id: String?): List<FabricView> {
        if (!machineId.isNullOrEmpty() && !id.isNullOrEmpty()) {
            return runningEngine(machineId, id).listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList.map { FabricView(machineId, id, it, desired(machineId, id, it.fabricId.value)) }
        }
        val result = ArrayList<FabricView>()
        for (e in listEngines(machineId)) {
            if (e.process.state != EngineProcessState.ENGINE_PROCESS_STATE_RUNNING) continue
            runCatching { listFabrics(e.machine, e.process.engineId.value) }.onSuccess { result += it }
        }
        return result
    }

    // --- Logs ---

    /** Queries the logs of one Engine, or fans out to all running Engines. */
    public suspend fun queryLogs(machineId: String?, id: String?, request: QueryLogsRequest): LogResult {
        val limit = if (request.limit > 0) request.limit else 1000
        val problems = ArrayList<String>()
        val entries = ArrayList<LogView>()
        if (!machineId.isNullOrEmpty() && !id.isNullOrEmpty()) {
            runningEngine(machineId, id).queryLogs(request).entriesList.forEach { entries += LogView(machineId, id, it) }
        } else {
            for (e in listEngines(machineId)) {
                if (e.process.state != EngineProcessState.ENGINE_PROCESS_STATE_RUNNING) continue
                val eid = e.process.engineId.value
                try {
                    runningEngine(e.machine, eid).queryLogs(request).entriesList.forEach { entries += LogView(e.machine, eid, it) }
                } catch (ex: StatusException) {
                    problems += "${e.machine}/$eid: ${ex.status.description ?: ex.status.code.name}"
                } catch (ex: ManagementException) {
                    problems += "${e.machine}/$eid: ${ex.message}"
                }
            }
        }
        val sorted = entries.sortedWith(compareBy({ it.entry.timestamp.seconds }, { it.entry.timestamp.nanos })).takeLast(limit)
        return LogResult(sorted, problems)
    }

    /** The numbers of the running Engine [machineId]/[id], or of all running Engines (of [machineId] if given) if no Engine is named (#191). */
    public suspend fun getMetrics(machineId: String?, id: String?): MetricsResult {
        val request = cringle.engine.v1.GetMetricsRequest.getDefaultInstance()
        if (!machineId.isNullOrEmpty() && !id.isNullOrEmpty()) {
            return MetricsResult(listOf(MetricsView(machineId, id, runningEngine(machineId, id).getMetrics(request).metrics)), emptyList())
        }
        val problems = ArrayList<String>()
        val result = ArrayList<MetricsView>()
        for (e in listEngines(machineId)) {
            if (e.process.state != EngineProcessState.ENGINE_PROCESS_STATE_RUNNING) continue
            val eid = e.process.engineId.value
            try {
                result += MetricsView(e.machine, eid, runningEngine(e.machine, eid).getMetrics(request).metrics)
            } catch (ex: StatusException) {
                problems += "${e.machine}/$eid: ${ex.status.description ?: ex.status.code.name}"
            } catch (ex: ManagementException) {
                problems += "${e.machine}/$eid: ${ex.message}"
            }
        }
        return MetricsResult(result.sortedWith(compareBy({ it.machine }, { it.engineId })), problems)
    }

    // --- Data warehouse (#194) ---

    private fun fabricRecord(fabricId: String): FabricRecord =
        snapshot().fabrics.firstOrNull { it.fabricId == fabricId }
            ?: throw ManagementException(Status.Code.NOT_FOUND, "fabric '$fabricId' is not known to the ManagementServer")

    /** The records of a partition of the fabric [request.fabric], asked from the Engine it runs on. */
    public suspend fun queryDwh(request: cringle.management.v1.QueryDwhRequest): cringle.engine.v1.QueryDwhResponse {
        val record = fabricRecord(request.fabric)
        val engineRequest = cringle.engine.v1.QueryDwhRequest.newBuilder()
            .setFabricId(FabricId.newBuilder().setValue(request.fabric)).setKind(request.kind).setName(request.name).setLimit(request.limit)
        if (request.hasSince()) engineRequest.since = request.since
        if (request.hasUntil()) engineRequest.until = request.until
        return runningEngine(record.machine, record.engineId).queryDwh(engineRequest.build())
    }

    /** The partitions of the fabric [fabric], or of all fabrics on all running Engines if it is empty. */
    public suspend fun listDwhPartitions(fabric: String): Pair<List<Triple<String, String, cringle.engine.v1.DwhPartitionInfo>>, List<String>> {
        val request = cringle.engine.v1.ListDwhPartitionsRequest.newBuilder().setFabric(fabric).build()
        if (fabric.isNotEmpty()) {
            val record = fabricRecord(fabric)
            val answer = runningEngine(record.machine, record.engineId).listDwhPartitions(request)
            return answer.partitionsList.map { Triple(record.machine, record.engineId, it) } to emptyList()
        }
        val problems = ArrayList<String>()
        val result = ArrayList<Triple<String, String, cringle.engine.v1.DwhPartitionInfo>>()
        for (e in listEngines(null)) {
            if (e.process.state != EngineProcessState.ENGINE_PROCESS_STATE_RUNNING) continue
            val eid = e.process.engineId.value
            try {
                runningEngine(e.machine, eid).listDwhPartitions(request).partitionsList.forEach { result += Triple(e.machine, eid, it) }
            } catch (ex: StatusException) {
                problems += "${e.machine}/$eid: ${ex.status.description ?: ex.status.code.name}"
            } catch (ex: ManagementException) {
                problems += "${e.machine}/$eid: ${ex.message}"
            }
        }
        return result to problems
    }

    /** Switches the recording of the tethers of [fabric] on or off and remembers it, so that a fabric that is deployed again or restored gets it again. */
    public suspend fun setRecording(fabric: String, all: Boolean, default: cringle.engine.v1.DwhRetention?) {
        val record = fabricRecord(fabric)
        val request = cringle.engine.v1.SetRecordingRequest.newBuilder().setFabricId(FabricId.newBuilder().setValue(fabric)).setAll(all)
        if (default != null) request.defaultRetention = default
        runningEngine(record.machine, record.engineId).setRecording(request.build())
        update { d ->
            val others = d.recordings.filterNot { it.fabricId == fabric }
            d.copy(recordings = if (all) others + RecordingRecord(fabric, default?.maxAgeMs ?: 0, default?.maxBytes ?: 0) else others) to Unit
        }
    }

    /** Sets the retention of a partition of [fabric] on its Engine. */
    public suspend fun setDwhRetention(request: cringle.management.v1.SetDwhRetentionRequest) {
        val record = fabricRecord(request.fabric)
        runningEngine(record.machine, record.engineId).setDwhRetention(
            cringle.engine.v1.SetDwhRetentionRequest.newBuilder().setFabricId(FabricId.newBuilder().setValue(request.fabric))
                .setKind(request.kind).setName(request.name).setRetention(request.retention).build(),
        )
    }

    /** Applies the remembered recording mode of [fabricId] to the fabric that was just deployed on [api]. */
    private suspend fun applyRecording(api: EngineManagementServiceCoroutineStub, fabricId: String) {
        val mode = snapshot().recordings.firstOrNull { it.fabricId == fabricId } ?: return
        runCatching {
            api.setRecording(
                cringle.engine.v1.SetRecordingRequest.newBuilder().setFabricId(FabricId.newBuilder().setValue(fabricId)).setAll(true)
                    .setDefaultRetention(cringle.engine.v1.DwhRetention.newBuilder().setMaxAgeMs(mode.maxAgeMs).setMaxBytes(mode.maxBytes)).build(),
            )
        }
    }

    // --- Recovery ---

    /**
     * Brings the recorded state back: Engines with autostart are re-registered at their Daemon if needed and started,
     * and the fabrics of running Engines are deployed again if the Engine lost them and started if they should run.
     * What fails is reported, not thrown.
     */
    public suspend fun recover(): RecoveryReport {
        val problems = ArrayList<String>()
        var started = 0
        var restored = 0
        val snap = snapshot()
        for (m in snap.machines) {
            val registered = try {
                probe(daemon(m)).listEngines(ListEnginesRequest.getDefaultInstance()).enginesList.associateBy { it.engineId.value }
            } catch (e: StatusException) {
                problems += "machine ${m.id} is not reachable: ${e.status.description ?: e.status.code.name}"
                continue
            }
            for (rec in snap.engines.filter { it.machine == m.id && it.autostart }) {
                try {
                    var info = registered[rec.engineId]
                    if (info == null) {
                        info = daemon(m).createEngine(CreateEngineRequest.newBuilder().setEngineId(rec.engineId).build())
                    }
                    if (info.state != EngineProcessState.ENGINE_PROCESS_STATE_RUNNING) {
                        daemon(m).startEngine(EngineRequest.newBuilder().setEngineId(engineId(rec.engineId)).build())
                        started++
                    }
                } catch (e: StatusException) {
                    problems += "engine ${m.id}/${rec.engineId} cannot be started: ${e.status.description ?: e.status.code.name}"
                }
            }
            for (engineRec in snap.engines.filter { it.machine == m.id }) {
                val fabrics = snap.fabrics.filter { it.machine == m.id && it.engineId == engineRec.engineId }
                if (fabrics.isEmpty()) continue
                try {
                    val api = runningEngine(m.id, engineRec.engineId)
                    val present = api.listFabrics(ListFabricsRequest.getDefaultInstance()).fabricsList.associateBy { it.fabricId.value }
                    for (f in fabrics) {
                        try {
                            var current = present[f.fabricId]
                            var changed = false
                            if (current == null) {
                                current = api.deployFabric(withToken(DeployFabricRequest.parseFrom(f.deploy)))
                                applyRecording(api, f.fabricId)
                                changed = true
                            }
                            if (f.desiredRunning && current.state != FabricRuntimeState.FABRIC_RUNTIME_STATE_RUNNING) {
                                api.startFabric(fabricRef(f.fabricId))
                                changed = true
                            }
                            if (changed) restored++
                        } catch (e: StatusException) {
                            problems += "fabric ${m.id}/${engineRec.engineId}/${f.fabricId} cannot be restored: ${e.status.description ?: e.status.code.name}"
                        }
                    }
                } catch (e: StatusException) {
                    problems += "fabrics of engine ${m.id}/${engineRec.engineId} cannot be restored: ${e.status.description ?: e.status.code.name}"
                } catch (e: ManagementException) {
                    problems += "fabrics of engine ${m.id}/${engineRec.engineId} cannot be restored: ${e.message}"
                }
            }
        }
        return RecoveryReport(started, restored, problems)
    }

    // --- Deployment ---

    private val repositories = ConcurrentHashMap<String, cringle.repository.RepositoryClient>()

    private fun repositoryClient(address: String) = repositories.computeIfAbsent(address) { cringle.repository.RepositoryClient(it, repositoryToken, cringle.repository.RepositoryTls(identity, trustStore)) }

    private fun repositoryOf(m: MachineRecord): String =
        m.repositoryAddress ?: defaultRepository ?: throw ManagementException(Status.Code.FAILED_PRECONDITION, "no repository is configured for machine '${m.id}'")

    private fun projectOf(f: FabricRecord): String = DeployFabricRequest.parseFrom(f.deploy).project.name

    private fun fabricIdFor(project: String, blueprint: String, index: Int): String {
        val base = "$project-$blueprint".lowercase().replace(Regex("[^a-z0-9-]"), "-").trimStart('-').ifEmpty { "fabric" }
        val suffix = "-${index + 1}"
        return base.take(63 - suffix.length) + suffix
    }

    private val projectLocks = ConcurrentHashMap<String, Mutex>()

    /** Deploy and undeploy of one project never overlap; other projects are not held up. */
    private fun projectLock(project: String): Mutex = projectLocks.computeIfAbsent(project) { Mutex() }

    /** Test hook: runs before each fabric of a deployment is sent to its Engine. */
    internal var beforeFabricDeploy: suspend (fabricId: String) -> Unit = {}

    private fun lockPath(project: String, version: String) = store.directory.resolve("locks").resolve("$project-$version.lock.json")

    /**
     * The lock file of the project version that a deploy for [range] would pick (the highest release in the range),
     * with that version; `null` if there is none. A lock file that cannot be used is an error, not a reason to
     * resolve again behind the back of the user: `--relock` is the way out.
     */
    private suspend fun existingLock(repo: cringle.repository.RepositoryClient, project: String, range: String): Pair<String, LockFile>? {
        val wanted = try {
            VersionRange.parse(range.ifBlank { "*" })
        } catch (_: IllegalArgumentException) {
            return null // resolving reports the invalid range
        }
        val candidate = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.versions(project) }.filter { wanted.matches(it) }.maxOrNull()
        } catch (e: cringle.repository.RepositoryClientException) {
            throw ManagementException(e.status, "repository: ${e.message}")
        } ?: return null
        val version = candidate.toString()
        val path = lockPath(project, version)
        if (!java.nio.file.Files.exists(path)) return null
        val lock = try {
            LockFile.parse(java.nio.file.Files.readString(path))
        } catch (e: PackageFormatException) {
            throw ManagementException(Status.Code.FAILED_PRECONDITION, "lock file $path is damaged (${e.message}); deploy with --relock to resolve again")
        }
        val problems = lock.problems()
        if (problems.isNotEmpty() || lock.packages[project]?.version != version) {
            val what = problems.joinToString { "${it.path}: ${it.message}" }.ifEmpty { "it does not lock $project@$version" }
            throw ManagementException(Status.Code.FAILED_PRECONDITION, "lock file $path is inconsistent ($what); deploy with --relock to resolve again")
        }
        // the locked bytes must be the ones the repository has
        for ((name, locked) in lock.packages) {
            val entry = try {
                repo.get(name, locked.version)
            } catch (e: cringle.repository.RepositoryClientException) {
                throw ManagementException(e.status, "repository: locked package $name@${locked.version}: ${e.message}")
            }
            if (!entry.sha256.equals(locked.hash, ignoreCase = true)) {
                throw ManagementException(
                    Status.Code.FAILED_PRECONDITION,
                    "$name@${locked.version} has hash ${entry.sha256} in the repository, but the lock file $path says ${locked.hash}; deploy with --relock to accept the repository's version",
                )
            }
        }
        return version to lock
    }

    /**
     * Deploys [project] (version [range], default any release) and places every fabric of the fabric configuration on
     * running Engines with all required roles and labels (the least loaded first); the Engines download what they miss.
     *
     * Versions come from the lock file `locks/<project>-<version>.lock.json` if there is one for the version this deploy
     * picks; otherwise (or with [relock]) the project is resolved against the Repository and the lock file is written.
     * Deploy and [undeploy] of one project run one after the other.
     *
     * Everything that can fail without touching the running system is done first: resolving, reading the package,
     * placement and building the requests. Only then are the fabrics of the project that are deployed already removed
     * and the new ones deployed. If that fails, the new fabrics are removed again and the previous ones are restored
     * from their recorded requests.
     */
    public suspend fun deploy(project: String, range: String, start: Boolean, relock: Boolean = false): DeployResult {
        val defaultAddress = defaultRepository ?: throw ManagementException(Status.Code.FAILED_PRECONDITION, "no repository is configured")
        val repo = repositoryClient(defaultAddress)
        val result = projectLock(project).withLock { deployLocked(project, range, start, relock, repo) }
        afterChange(project, "deployed")
        return result
    }

    private suspend fun deployLocked(project: String, range: String, start: Boolean, relock: Boolean, repo: cringle.repository.RepositoryClient): DeployResult {
        val pinned = if (relock) null else existingLock(repo, project, range)
        val lock: LockFile
        val version: String
        if (pinned != null) {
            version = pinned.first
            lock = pinned.second
        } else {
            val resolution = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cringle.packaging.Resolver.resolve(mapOf(project to range.ifBlank { "*" }), repo) }
            } catch (e: cringle.packaging.ResolutionException) {
                throw ManagementException(if (e.failure == cringle.packaging.ResolutionFailure.UNKNOWN_PACKAGE) Status.Code.NOT_FOUND else Status.Code.FAILED_PRECONDITION, e.message ?: "resolution failed")
            } catch (e: cringle.repository.RepositoryClientException) {
                throw ManagementException(e.status, "repository: ${e.message}")
            }
            version = resolution.packages.getValue(project).version.toString()
            lock = resolution.toLock()
            val problems = lock.problems()
            if (problems.isNotEmpty()) throw ManagementException(Status.Code.INTERNAL, "inconsistent lock: " + problems.joinToString { "${it.path}: ${it.message}" })
        }
        val lockText = lock.encode()
        val rootHash = lock.packages.getValue(project).hash

        val projectPackage = readProjectPackage(repo, project, version)
        val configs = projectPackage.manifest.fabrics
        if (configs.isEmpty()) throw ManagementException(Status.Code.FAILED_PRECONDITION, "project $project@$version has no fabric configuration")
        for (c in configs) {
            if (projectPackage.blueprints.none { it.name == c.blueprint }) throw ManagementException(Status.Code.FAILED_PRECONDITION, "fabric configuration refers to unknown blueprint '${c.blueprint}'")
        }

        // place on the running Engines, counting the load without the fabrics this deploy is going to replace
        val previous = snapshot().fabrics.filter { projectOf(it) == project }
        val engines = listEngines(null).filter { it.process.state == EngineProcessState.ENGINE_PROCESS_STATE_RUNNING }
        val load = HashMap<Pair<String, String>, Int>()
        snapshot().fabrics.filter { it !in previous }.forEach { load.merge(it.machine to it.engineId, 1, Int::plus) }
        val plan = ArrayList<Triple<EngineView, String, String>>() // engine, fabric id, blueprint
        for (config in configs) {
            val candidates = engines
                .filter { e -> e.roles.containsAll(config.roles) && config.labels.all { (k, v) -> e.labels[k] == v } }
                .sortedWith(compareBy({ load[it.machine to it.process.engineId.value] ?: 0 }, { it.machine }, { it.process.engineId.value }))
            if (candidates.size < config.instances) {
                throw ManagementException(
                    Status.Code.FAILED_PRECONDITION,
                    "blueprint '${config.blueprint}' needs ${config.instances} running engine(s) with roles ${config.roles} and labels ${config.labels}, but only ${candidates.size} match",
                )
            }
            for (i in 0 until config.instances) {
                val e = candidates[i]
                load.merge(e.machine to e.process.engineId.value, 1, Int::plus)
                plan += Triple(e, fabricIdFor(project, config.blueprint, i), config.blueprint)
            }
        }
        if (plan.map { it.second }.distinct().size != plan.size) throw ManagementException(Status.Code.FAILED_PRECONDITION, "the fabric configuration of $project@$version yields duplicate fabric ids")

        val plugins = lock.packages.filterKeys { it != project }.toSortedMap()
        val requests = ArrayList<Triple<EngineView, String, DeployFabricRequest>>()
        val planned = plan.associate { (engine, fabricId, blueprint) -> fabricId to (engine to projectPackage.blueprints.first { it.name == blueprint }) }
        // services before the fabrics that use them (#179)
        val ordered = plan.sortedBy { (_, _, blueprint) -> if (projectPackage.blueprints.first { it.name == blueprint }.provides.isEmpty()) 1 else 0 }
        for ((engine, fabricId, blueprint) in ordered) {
            val machine = machine(engine.machine)
            val address = repositoryOf(machine)
            val client = repositoryClient(address)
            val builder = DeployFabricRequest.newBuilder()
                .setFabricId(FabricId.newBuilder().setValue(fabricId))
                .setProject(cringle.common.v1.ProjectRef.newBuilder().setName(project).setVersion(version))
                .setBlueprint(blueprint)
            val bp = planned.getValue(fabricId).second
            for (service in bp.tethers.mapNotNull { it.service }.distinct()) {
                builder.addServiceBindings(resolveBinding(project, service, planned, repo))
            }
            if (bp.provides.isNotEmpty()) builder.addAllServiceCallers(callersOf(fabricId, project, planned))
            val source = cringle.engine.v1.PackageSource.newBuilder().setRepositoryAddress(address)
            source.addArtifacts(artifact(cringle.engine.v1.ArtifactKind.ARTIFACT_KIND_PROJECT, project, version, rootHash))
            for ((name, locked) in plugins) {
                val trust = try {
                    client.get(name, locked.version).trust
                } catch (e: cringle.repository.RepositoryClientException) {
                    throw ManagementException(e.status, "repository $address: ${e.message}")
                }
                builder.addPlugins(
                    cringle.engine.v1.DeployedPlugin.newBuilder()
                        .setPlugin(cringle.common.v1.PluginRef.newBuilder().setName(name).setVersion(locked.version))
                        .setTrust(if (trust == cringle.repository.PluginTrust.TRUSTED) cringle.engine.v1.PluginTrust.PLUGIN_TRUST_TRUSTED else cringle.engine.v1.PluginTrust.PLUGIN_TRUST_UNTRUSTED),
                )
                source.addArtifacts(artifact(cringle.engine.v1.ArtifactKind.ARTIFACT_KIND_PLUGIN, name, locked.version, locked.hash))
            }
            builder.setSource(source)
            requests += Triple(engine, fabricId, builder.build())
        }

        // nothing has been touched so far; a lock that cannot be written stops the deploy here
        if (pinned == null) writeAtomically(lockPath(project, version), lockText)

        undeployLocked(project)
        val touched = ArrayList<Triple<String, String, String>>() // machine, engine, fabric
        val deployed = ArrayList<FabricView>()
        try {
            for ((engine, fabricId, request) in requests) {
                val engineId = engine.process.engineId.value
                touched += Triple(engine.machine, engineId, fabricId)
                try {
                    beforeFabricDeploy(fabricId)
                    deployed += deployFabric(engine.machine, engineId, request, start)
                } catch (e: StatusException) {
                    throw ManagementException(e.status.code, "deploying $fabricId on ${engine.machine}/$engineId: ${e.status.description ?: e.status.code.name}")
                }
            }
        } catch (e: Exception) {
            // cleanup must not be cancelled with the call that failed
            val notRestored = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                touched.forEach { (machineId, engineId, fabricId) -> runCatching { removeFabricQuietly(machineId, engineId, fabricId) } }
                restore(previous)
            }
            if (notRestored.isEmpty()) throw e
            val code = (e as? ManagementException)?.code ?: Status.Code.INTERNAL
            throw ManagementException(code, "${e.message}; the previous fabrics of $project could not all be restored: ${notRestored.joinToString()}")
        }
        return DeployResult(project, version, lockText, deployed)
    }

    private suspend fun readProjectPackage(repo: cringle.repository.RepositoryClient, project: String, version: String): cringle.packaging.ProjectPackage = try {
        val entry = repo.get(project, version)
        if (entry.kind != cringle.packaging.PackageKind.PROJECT) throw ManagementException(Status.Code.INVALID_ARGUMENT, "'$project' is a plugin, not a project")
        val file = java.nio.file.Files.createTempFile("cringle-deploy", ".cringle")
        try {
            repo.download(project, version, file)
            cringle.packaging.PackageReader.readProject(file)
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    } catch (e: cringle.repository.RepositoryClientException) {
        throw ManagementException(e.status, "repository: ${e.message}")
    }

    /** The public key fingerprint that the running Engine [machineId]/[engineId] reports. */
    private suspend fun fingerprintOf(machineId: String, engineId: String): String {
        val fingerprint = runningEngine(machineId, engineId).getStatus(GetStatusRequest.getDefaultInstance()).publicKeyFingerprint
        if (fingerprint.isEmpty()) throw ManagementException(Status.Code.FAILED_PRECONDITION, "engine '$engineId' on machine '$machineId' does not report its key")
        return fingerprint
    }

    /**
     * The binding of the service [service] for a fabric of [project] (#179): the fabric that the binding of #171 names,
     * the block and port it provides the service on, and the key of the Engine that runs it. [planned] are the fabrics
     * of the deploy in progress; the others are looked up in the recorded state. Fails before anything is touched.
     */
    private suspend fun resolveBinding(
        project: String,
        service: String,
        planned: Map<String, Pair<EngineView, cringle.packaging.Blueprint>>,
        repo: cringle.repository.RepositoryClient,
    ): cringle.engine.v1.ServiceBinding {
        val binding = snapshot().bindings.firstOrNull { it.consumerProject == project && it.service == service }
            ?: throw ManagementException(Status.Code.FAILED_PRECONDITION, "project '$project' uses the service '$service', but it is not bound (cringle bind $project $service <fabric>)")
        val first = resolveTarget(project, service, binding.targets.first(), planned, repo)
        if (binding.targets.size == 1) return first
        return first.toBuilder().addAllFallbacks(binding.targets.drop(1).map { resolveTarget(project, service, it, planned, repo) }).build()
    }

    private suspend fun resolveTarget(
        project: String,
        service: String,
        target: String,
        planned: Map<String, Pair<EngineView, cringle.packaging.Blueprint>>,
        repo: cringle.repository.RepositoryClient,
    ): cringle.engine.v1.ServiceBinding {
        val fingerprint: String
        val blueprint: cringle.packaging.Blueprint
        val inPlan = planned[target]
        if (inPlan != null) {
            fingerprint = inPlan.first.status?.publicKeyFingerprint.orEmpty()
            blueprint = inPlan.second
            if (fingerprint.isEmpty()) throw ManagementException(Status.Code.FAILED_PRECONDITION, "engine ${inPlan.first.machine}/${inPlan.first.process.engineId.value} does not report its key")
        } else {
            val record = snapshot().fabrics.firstOrNull { it.fabricId == target }
                ?: throw ManagementException(Status.Code.FAILED_PRECONDITION, "the fabric '$target' that '$service' of project '$project' is bound to is not deployed")
            fingerprint = fingerprintOf(record.machine, record.engineId)
            val request = DeployFabricRequest.parseFrom(record.deploy)
            blueprint = readProjectPackage(repo, request.project.name, request.project.version).blueprints.first { it.name == request.blueprint }
        }
        val provided = blueprint.provides.firstOrNull { it.service == service }
            ?: throw ManagementException(Status.Code.FAILED_PRECONDITION, "the fabric '$target' does not provide the service '$service'")
        return cringle.engine.v1.ServiceBinding.newBuilder()
            .setService(service).setFabric(target).setBlock(provided.block).setPort(provided.port).setFingerprint(fingerprint).build()
    }

    /**
     * The keys of the Engines that may call the service fabric [serviceFabric]: those that run fabrics of the projects whose
     * bindings name it. [project] with its [planned] fabrics is the deploy in progress, whose fabrics are not recorded yet.
     */
    private suspend fun callersOf(serviceFabric: String, project: String?, planned: Map<String, Pair<EngineView, cringle.packaging.Blueprint>>): List<String> {
        val consumers = snapshot().bindings.filter { serviceFabric in it.targets }.map { it.consumerProject }.toSet()
        val keys = LinkedHashSet<String>()
        for (consumer in consumers) {
            if (consumer == project) planned.values.forEach { (engine, _) -> engine.status?.publicKeyFingerprint?.takeIf { it.isNotEmpty() }?.let { keys += it } }
            else snapshot().fabrics.filter { projectOf(it) == consumer }.map { it.machine to it.engineId }.distinct().forEach { (m, e) -> keys += fingerprintOf(m, e) }
        }
        return keys.toList()
    }

    /**
     * After a deploy or undeploy of [project] (#179): the allow-lists of the service fabrics it uses or provides are updated,
     * and the consumers of the service fabrics of [project] are deployed again if a service now runs on another Engine.
     * Called without the lock of the project; what fails is reported after the rest was tried.
     */
    private suspend fun afterChange(project: String, what: String) {
        val problems = ArrayList<String>()
        val services = LinkedHashSet<String>()
        snapshot().bindings.filter { it.consumerProject == project }.forEach { services += it.targets }
        snapshot().fabrics.filter { projectOf(it) == project }.map { it.fabricId }.forEach { services += it }
        for (fabricId in services) {
            try {
                syncCallers(fabricId)
            } catch (e: StatusException) {
                problems += "allow-list of $fabricId: ${e.status.description ?: e.status.code.name}"
            } catch (e: ManagementException) {
                problems += "allow-list of $fabricId: ${e.message}"
            }
        }
        val mine = snapshot().fabrics.filter { projectOf(it) == project }.map { it.fabricId }.toSet()
        val consumers = snapshot().bindings.filter { b -> b.targets.any { it in mine } }.map { it.consumerProject }.filter { it != project }.toSet()
        for (consumer in consumers) {
            try {
                projectLock(consumer).withLock { rebind(consumer) }
            } catch (e: StatusException) {
                problems += "consumer $consumer: ${e.status.description ?: e.status.code.name}"
            } catch (e: ManagementException) {
                problems += "consumer $consumer: ${e.message}"
            }
        }
        if (problems.isNotEmpty()) throw ManagementException(Status.Code.INTERNAL, "$project is $what, but its services could not all be updated: ${problems.joinToString("; ")}")
    }

    /**
     * After a binding changed (#173): the running fabrics of [project] that use [service] get the new instances through
     * `UpdateServiceBindings` (no redeploy) and their recorded requests are updated; the allow-lists of the [targets]
     * (old and new) are brought up to date.
     */
    private suspend fun applyBindingChange(project: String, service: String, targets: List<String>) {
        val problems = ArrayList<String>()
        try {
            projectLock(project).withLock {
                val defaultAddress = defaultRepository ?: return@withLock
                val repo = repositoryClient(defaultAddress)
                for (record in snapshot().fabrics.filter { projectOf(it) == project }) {
                    val request = DeployFabricRequest.parseFrom(record.deploy)
                    if (request.serviceBindingsList.none { it.service == service }) continue
                    try {
                        val fresh = resolveBinding(project, service, emptyMap(), repo)
                        runningEngine(record.machine, record.engineId).updateServiceBindings(
                            cringle.engine.v1.UpdateServiceBindingsRequest.newBuilder().setFabricId(FabricId.newBuilder().setValue(record.fabricId)).addBindings(fresh).build(),
                        )
                        val next = request.toBuilder().clearServiceBindings()
                            .addAllServiceBindings(request.serviceBindingsList.map { if (it.service == service) fresh else it }).build().toByteArray()
                        update { d -> d.copy(fabrics = d.fabrics.map { if (it.fabricId == record.fabricId) it.copy(deploy = next) else it }) to Unit }
                    } catch (e: StatusException) {
                        problems += "${record.fabricId}: ${e.status.description ?: e.status.code.name}"
                    } catch (e: ManagementException) {
                        problems += "${record.fabricId}: ${e.message}"
                    }
                }
            }
        } catch (e: ManagementException) {
            problems += e.message.orEmpty()
        }
        for (fabricId in targets.distinct()) {
            try {
                syncCallers(fabricId)
            } catch (e: StatusException) {
                problems += "allow-list of $fabricId: ${e.status.description ?: e.status.code.name}"
            } catch (e: ManagementException) {
                problems += "allow-list of $fabricId: ${e.message}"
            }
        }
        if (problems.isNotEmpty()) throw ManagementException(Status.Code.INTERNAL, "the binding of '$service' of $project is stored, but could not be applied everywhere: ${problems.joinToString("; ")}")
    }

    /** Sets the allowed callers of the service fabric [fabricId] on its Engine and in its recorded request, if they changed. */
    private suspend fun syncCallers(fabricId: String) {
        val record = snapshot().fabrics.firstOrNull { it.fabricId == fabricId } ?: return
        val request = DeployFabricRequest.parseFrom(record.deploy)
        val callers = callersOf(fabricId, null, emptyMap())
        if (callers.toSet() == request.serviceCallersList.toSet()) return
        runningEngine(record.machine, record.engineId).setServiceCallers(
            cringle.engine.v1.SetServiceCallersRequest.newBuilder().setFabricId(FabricId.newBuilder().setValue(fabricId)).addAllFingerprints(callers).build(),
        )
        val changed = request.toBuilder().clearServiceCallers().addAllServiceCallers(callers).build().toByteArray()
        update { d -> d.copy(fabrics = d.fabrics.map { if (it.fabricId == fabricId) it.copy(deploy = changed) else it }) to Unit }
    }

    /** Deploys the fabrics of [consumer] again whose bound service now has another fabric, port or key. */
    private suspend fun rebind(consumer: String) {
        val defaultAddress = defaultRepository ?: throw ManagementException(Status.Code.FAILED_PRECONDITION, "no repository is configured")
        val repo = repositoryClient(defaultAddress)
        for (record in snapshot().fabrics.filter { projectOf(it) == consumer }) {
            val request = DeployFabricRequest.parseFrom(record.deploy)
            if (request.serviceBindingsCount == 0) continue
            val fresh = request.serviceBindingsList.map { resolveBinding(consumer, it.service, emptyMap(), repo) }
            if (fresh == request.serviceBindingsList) continue
            val next = request.toBuilder().clearServiceBindings().addAllServiceBindings(fresh).build()
            removeFabricQuietly(record.machine, record.engineId, record.fabricId)
            deployFabric(record.machine, record.engineId, next, record.desiredRunning)
        }
    }

    /** Deploys [previous] again on their Engines from the recorded requests; returns the ones that could not be restored. */
    private suspend fun restore(previous: List<FabricRecord>): List<String> {
        val failed = ArrayList<String>()
        for (f in previous) {
            try {
                deployFabric(f.machine, f.engineId, DeployFabricRequest.parseFrom(f.deploy), f.desiredRunning)
            } catch (e: StatusException) {
                failed += "${f.machine}/${f.engineId}/${f.fabricId}: ${e.status.description ?: e.status.code.name}"
            } catch (e: ManagementException) {
                failed += "${f.machine}/${f.engineId}/${f.fabricId}: ${e.message}"
            }
        }
        return failed
    }

    private fun artifact(kind: cringle.engine.v1.ArtifactKind, name: String, version: String, hash: String) =
        cringle.engine.v1.PackageArtifact.newBuilder().setKind(kind).setName(name).setVersion(version).setSha256(hash).build()

    private suspend fun removeFabricQuietly(machineId: String, engineId: String, fabricId: String) {
        try {
            val api = runningEngine(machineId, engineId)
            runCatching { api.stopFabric(fabricRef(fabricId)) }
            runCatching { api.removeFabric(fabricRef(fabricId)) }
        } catch (_: StatusException) {
            // the engine is gone, and so is the fabric
        } catch (_: ManagementException) {
        }
        update { d -> d.copy(fabrics = d.fabrics.filter { !(it.machine == machineId && it.engineId == engineId && it.fabricId == fabricId) }) to Unit }
    }

    /** Stops and removes all fabrics of [project]; returns them as `<machine>/<engine>/<fabric>`. */
    public suspend fun undeploy(project: String): List<String> {
        val removed = projectLock(project).withLock { undeployLocked(project) }
        afterChange(project, "undeployed")
        return removed
    }

    private suspend fun undeployLocked(project: String): List<String> {
        val fabrics = snapshot().fabrics.filter { projectOf(it) == project }
        for (f in fabrics) removeFabricQuietly(f.machine, f.engineId, f.fabricId)
        return fabrics.map { "${it.machine}/${it.engineId}/${it.fabricId}" }
    }

    /** Removes unused package versions from the cache of [machineId], or of all machines. */
    public suspend fun cleanupCache(machineId: String?, minUnusedSeconds: Long): Pair<List<String>, List<String>> {
        val machines = if (machineId.isNullOrEmpty()) snapshot().machines else listOf(machine(machineId))
        val removed = ArrayList<String>()
        val problems = ArrayList<String>()
        for (m in machines) {
            val running = try {
                daemon(m).listEngines(ListEnginesRequest.getDefaultInstance()).enginesList.firstOrNull { it.state == EngineProcessState.ENGINE_PROCESS_STATE_RUNNING && it.managementPort != 0 }
            } catch (e: StatusException) {
                problems += "${m.id}: ${e.status.description ?: e.status.code.name}"
                continue
            }
            if (running == null) {
                problems += "${m.id}: no running engine to clean the cache with"
                continue
            }
            try {
                engineApi(m, running.managementPort).cleanupCache(cringle.engine.v1.CleanupCacheRequest.newBuilder().setMinUnusedSeconds(minUnusedSeconds).build())
                    .also { r -> r.failedList.forEach { problems += "${m.id}: could not remove $it" } }
                    .removedList.forEach { removed += "${m.id}: $it" }
            } catch (e: StatusException) {
                problems += "${m.id}: ${e.status.description ?: e.status.code.name}"
            }
        }
        return removed to problems
    }

    // --- Repository and Router (used by the gRPC facade) ---

    private class Bearer(private val token: String) : ClientInterceptor {
        override fun <Q, R> interceptCall(method: MethodDescriptor<Q, R>, options: CallOptions, next: Channel): ClientCall<Q, R> =
            object : ForwardingClientCall.SimpleForwardingClientCall<Q, R>(next.newCall(method, options)) {
                override fun start(listener: Listener<R>, headers: Metadata) {
                    headers.put(AuthInterceptor.AUTHORIZATION, "Bearer $token")
                    super.start(listener, headers)
                }
            }
    }

    /** The stub of the responsible Repository. */
    public fun repository(): RepositoryServiceCoroutineStub {
        val address = defaultRepository ?: throw ManagementException(Status.Code.FAILED_PRECONDITION, "no repository is configured")
        val stub = RepositoryServiceCoroutineStub(channel(address))
        return if (repositoryToken == null) stub else stub.withInterceptors(Bearer(repositoryToken))
    }

    // --- Trust (Architecture 5.1, 5.3) ---

    private fun trustInfo(e: TrustEntry): TrustEntryInfo = TrustEntryInfo.newBuilder()
        .setFingerprint(e.fingerprint)
        .setName(e.name)
        .setKind(e.kind.name)
        .setAddress(e.address.orEmpty())
        .setOrigin(e.origin.orEmpty())
        .setAddedAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(e.addedAt.epochSecond).setNanos(e.addedAt.nano))
        .build()

    /** What the Router of this machine trusts; empty without a Router or if it has no TLS (it then has no trust). */
    private suspend fun routerTrust(): List<TrustEntryInfo> {
        if (routerAddress == null) return emptyList()
        return try {
            router().listTrust(ListTrustRequest.getDefaultInstance()).entriesList
        } catch (e: StatusException) {
            emptyList()
        }
    }

    /**
     * Everything that is trusted: the trust store of the ManagementServer and the one of its Router, where the Engines that
     * came through a remote Router carry that Router as their origin. An entry that both know is listed once.
     */
    public suspend fun listTrust(): List<TrustEntryInfo> {
        val local = trustStore.list().map(::trustInfo)
        val known = local.map { it.fingerprint }.toSet()
        return local + routerTrust().filter { it.fingerprint !in known }
    }

    /** Trusts [fingerprint] as a `COMPONENT` or a `SERVER`; the key is given by the operator, never fetched. */
    public fun addTrustedComponent(fingerprint: String, name: String, kind: String, address: String): TrustEntryInfo {
        val text = fingerprint.trim().lowercase()
        if (!PublicKeyFingerprint.pattern.matches(text)) {
            throw ManagementException(Status.Code.INVALID_ARGUMENT, "'$fingerprint' is not a SHA-256 fingerprint (64 hexadecimal characters)")
        }
        if (name.isBlank()) throw ManagementException(Status.Code.INVALID_ARGUMENT, "a name for the entry is required")
        val trustKind = when (kind.ifEmpty { "COMPONENT" }.uppercase()) {
            "COMPONENT" -> TrustKind.COMPONENT
            "SERVER" -> TrustKind.SERVER
            else -> throw ManagementException(Status.Code.INVALID_ARGUMENT, "the kind of a trusted component is COMPONENT or SERVER, not '$kind'")
        }
        if (text == identity.publicKeyFingerprint) throw ManagementException(Status.Code.INVALID_ARGUMENT, "the ManagementServer does not trust its own key")
        val entry = TrustEntry(text, name.trim(), trustKind, address.takeIf { it.isNotEmpty() })
        trustStore.add(entry)
        return trustInfo(entry)
    }

    /**
     * Removes the trust in [fingerprint] and returns how many entries are gone. A remote Router is revoked at the Router of
     * this machine, which drops the Router and the Engines that came through it; an entry of the own trust store is deleted
     * together with the entries that came through it. An Engine that was trusted through a Router is not removed alone.
     */
    public suspend fun removeTrust(fingerprint: String): Int {
        val text = fingerprint.trim().lowercase()
        var removed = 0
        val remoteRouter = routerTrust().firstOrNull { it.fingerprint == text && it.kind == TrustKind.ROUTER.name && it.origin.isEmpty() }
        if (remoteRouter != null) {
            removed += try {
                router().revokeRemoteRouter(RevokeRemoteRouterRequest.newBuilder().setAddress(remoteRouter.address).build()).removedEntries
            } catch (e: StatusException) {
                throw ManagementException(e.status.code, "router: ${e.status.description ?: e.status.code.name}")
            }
        }
        val before = trustStore.list().size
        trustStore.remove(text)
        removed += before - trustStore.list().size
        if (removed == 0) {
            val through = routerTrust().firstOrNull { it.fingerprint == text }
            throw ManagementException(
                Status.Code.NOT_FOUND,
                if (through != null) "'$text' is trusted through the router ${through.origin}: revoke that router" else "'$text' is not trusted",
            )
        }
        return removed
    }

    /** The stub of the Router of this machine. */
    public fun router(): RegistryServiceCoroutineStub {
        val address = routerAddress ?: throw ManagementException(Status.Code.FAILED_PRECONDITION, "no router is configured")
        return RegistryServiceCoroutineStub(channel(address))
    }

    override fun close() {
        repositories.values.forEach { runCatching { it.close() } }
        repositories.clear()
        channels.values.forEach { it.shutdownNow() }
        channels.clear()
    }

    private companion object {
        val MACHINE_ID = Regex("[a-z0-9][a-z0-9-]{0,62}")
        val ADDRESS = Regex("[^:\\s]+:[0-9]{1,5}")
    }
}
