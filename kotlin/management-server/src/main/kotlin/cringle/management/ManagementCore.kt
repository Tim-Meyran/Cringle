// SPDX-License-Identifier: Apache-2.0

package cringle.management

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
import cringle.router.users.AuthInterceptor
import cringle.router.v1.RegistryServiceGrpcKt.RegistryServiceCoroutineStub
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

/** An error of the ManagementServer with the gRPC status it is reported as. */
public class ManagementException(public val code: Status.Code, message: String) : RuntimeException(message)

/** The outcome of [ManagementCore.recover]. */
public data class RecoveryReport(val enginesStarted: Int, val fabricsRestored: Int, val problems: List<String>)

/** A machine with its reachability. */
public data class MachineView(val record: MachineRecord, val reachable: Boolean, val lastError: String)

/** An Engine as seen by the ManagementServer: Daemon view, own status (if reachable) and setting. */
public data class EngineView(val machine: String, val process: EngineInfo, val status: GetStatusResponse?, val autostart: Boolean)

/** A fabric with the wish of the ManagementServer. */
public data class FabricView(val machine: String, val engineId: String, val info: FabricInfo, val desiredRunning: Boolean)

/** A log entry with its origin. */
public data class LogView(val machine: String, val engineId: String, val entry: LogEntry)

/** The result of a log query over several Engines. */
public data class LogResult(val entries: List<LogView>, val problems: List<String>)

/**
 * The service layer of the ManagementServer: it knows the machines, talks to their Daemons and Engines and remembers
 * the Engines and fabrics it created, so that [recover] can bring them back after a restart. The gRPC facade is
 * [ManagementServer]; a REST facade for the WebUI can use this class as well.
 *
 * The Daemon and Engine APIs are unauthenticated plaintext until mTLS is available (issue #13). An Engine management
 * API listens on the loopback interface of its machine, so only Engines on the machine of the ManagementServer are
 * reachable for now.
 */
public class ManagementCore(
    private val store: ManagementStore,
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

    private fun channel(address: String): ManagedChannel = channels.computeIfAbsent(address) {
        io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.forTarget(address).usePlaintext().build()
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
    public suspend fun createEngine(machineId: String, engineId: String?, name: String?, autostart: Boolean): EngineView {
        val m = machine(machineId)
        val info = daemon(m).createEngine(
            CreateEngineRequest.newBuilder().setEngineId(engineId.orEmpty()).setName(name.orEmpty()).build(),
        )
        update { d -> d.copy(engines = d.engines.filter { !(it.machine == machineId && it.engineId == info.engineId.value) } + EngineRecord(machineId, info.engineId.value, autostart)) to Unit }
        return EngineView(machineId, info, null, autostart)
    }

    private fun autostart(machineId: String, id: String): Boolean =
        snapshot().engines.firstOrNull { it.machine == machineId && it.engineId == id }?.autostart ?: false

    /** Starts an Engine and waits until it is up. */
    public suspend fun startEngine(machineId: String, id: String): EngineView {
        val m = machine(machineId)
        val info = daemon(m).startEngine(EngineRequest.newBuilder().setEngineId(engineId(id)).build())
        return EngineView(machineId, info, statusOf(m, info), autostart(machineId, id))
    }

    /** Stops an Engine. */
    public suspend fun stopEngine(machineId: String, id: String): EngineView {
        val m = machine(machineId)
        val info = daemon(m).stopEngine(EngineRequest.newBuilder().setEngineId(engineId(id)).build())
        return EngineView(machineId, info, null, autostart(machineId, id))
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
                daemon(m).listEngines(ListEnginesRequest.getDefaultInstance()).enginesList.map { EngineView(m.id, it, null, autostart(m.id, it.engineId.value)) }
            } catch (e: StatusException) {
                if (machineId.isNullOrEmpty()) emptyList() else throw e
            }
        }
    }

    /** One Engine with its own status. */
    public suspend fun getEngine(machineId: String, id: String): EngineView {
        val m = machine(machineId)
        val info = daemon(m).getEngine(EngineRequest.newBuilder().setEngineId(engineId(id)).build())
        return EngineView(machineId, info, statusOf(m, info), autostart(machineId, id))
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

    /** Deploys a fabric on a running Engine and remembers it. */
    public suspend fun deployFabric(machineId: String, id: String, request: DeployFabricRequest, start: Boolean): FabricView {
        val api = runningEngine(machineId, id)
        val fabricId = request.fabricId.value
        var info = api.deployFabric(request)
        update { d ->
            d.copy(fabrics = d.fabrics.filter { !(it.machine == machineId && it.engineId == id && it.fabricId == fabricId) } + FabricRecord(machineId, id, fabricId, request.toByteArray(), false)) to Unit
        }
        if (start) {
            info = api.startFabric(fabricRef(fabricId))
            setDesired(machineId, id, fabricId, true)
        }
        return FabricView(machineId, id, info, start)
    }

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
                                current = api.deployFabric(DeployFabricRequest.parseFrom(f.deploy))
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

    /** The stub of the Router of this machine. */
    public fun router(): RegistryServiceCoroutineStub {
        val address = routerAddress ?: throw ManagementException(Status.Code.FAILED_PRECONDITION, "no router is configured")
        return RegistryServiceCoroutineStub(channel(address))
    }

    override fun close() {
        channels.values.forEach { it.shutdownNow() }
        channels.clear()
    }

    private companion object {
        val MACHINE_ID = Regex("[a-z0-9][a-z0-9-]{0,62}")
        val ADDRESS = Regex("[^:\\s]+:[0-9]{1,5}")
    }
}
