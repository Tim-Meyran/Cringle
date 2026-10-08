// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import com.google.protobuf.Timestamp
import cringle.common.v1.EngineId
import cringle.daemon.v1.CreateEngineRequest
import cringle.daemon.v1.DaemonServiceGrpcKt
import cringle.daemon.v1.DeleteEngineRequest
import cringle.daemon.v1.DeleteEngineResponse
import cringle.daemon.v1.EngineInfo
import cringle.daemon.v1.EngineProcessState
import cringle.daemon.v1.EngineRequest
import cringle.daemon.v1.ListEnginesRequest
import cringle.daemon.v1.ListEnginesResponse
import cringle.daemon.v1.StartAllEnginesRequest
import io.grpc.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DaemonGrpcService(private val daemon: Daemon) : DaemonServiceGrpcKt.DaemonServiceCoroutineImplBase() {
    private suspend fun <T> call(body: () -> T): T = try {
        withContext(Dispatchers.IO) { body() }
    } catch (e: DaemonException) {
        throw when (e.error) {
            DaemonError.NOT_FOUND -> Status.NOT_FOUND
            DaemonError.ALREADY_EXISTS -> Status.ALREADY_EXISTS
            DaemonError.INVALID -> Status.INVALID_ARGUMENT
            DaemonError.FAILED_PRECONDITION -> Status.FAILED_PRECONDITION
        }.withDescription(e.message).asException()
    }

    private fun info(s: EngineSnapshot): EngineInfo = EngineInfo.newBuilder()
        .setEngineId(EngineId.newBuilder().setValue(s.id))
        .setName(s.name)
        .setState(
            when (s.state) {
                ProcessState.STOPPED -> EngineProcessState.ENGINE_PROCESS_STATE_STOPPED
                ProcessState.STARTING -> EngineProcessState.ENGINE_PROCESS_STATE_STARTING
                ProcessState.RUNNING -> EngineProcessState.ENGINE_PROCESS_STATE_RUNNING
                ProcessState.STOPPING -> EngineProcessState.ENGINE_PROCESS_STATE_STOPPING
                ProcessState.CRASHED -> EngineProcessState.ENGINE_PROCESS_STATE_CRASHED
            },
        )
        .setPid(s.pid)
        .setManagementPort(s.managementPort)
        .setExitCode(s.exitCode)
        .setLastError(s.lastError)
        .also { b -> s.startedAt?.let { b.setStartedAt(Timestamp.newBuilder().setSeconds(it.epochSecond).setNanos(it.nano)) } }
        .build()

    override suspend fun createEngine(request: CreateEngineRequest): EngineInfo =
        info(call { daemon.createEngine(request.engineId, request.name) })

    override suspend fun startEngine(request: EngineRequest): EngineInfo = info(call { daemon.supervisor.start(request.engineId.value) })

    override suspend fun stopEngine(request: EngineRequest): EngineInfo = info(call { daemon.supervisor.stop(request.engineId.value) })

    override suspend fun restartEngine(request: EngineRequest): EngineInfo = info(call { daemon.supervisor.restart(request.engineId.value) })

    override suspend fun deleteEngine(request: DeleteEngineRequest): DeleteEngineResponse {
        call { daemon.deleteEngine(request.engineId.value, request.deleteData) }
        return DeleteEngineResponse.getDefaultInstance()
    }

    override suspend fun listEngines(request: ListEnginesRequest): ListEnginesResponse =
        ListEnginesResponse.newBuilder().addAllEngines(daemon.supervisor.list().map(::info)).build()

    override suspend fun getEngine(request: EngineRequest): EngineInfo = info(call { daemon.supervisor.get(request.engineId.value) })

    override suspend fun setLogCollection(request: cringle.daemon.v1.SetLogCollectionRequest): cringle.daemon.v1.SetLogCollectionResponse {
        call {
            daemon.supervisor.get(request.engineId.value) // NOT_FOUND for an unknown engine
            daemon.collector.setEnabled(request.engineId.value, request.enabled)
        }
        return cringle.daemon.v1.SetLogCollectionResponse.getDefaultInstance()
    }

    override suspend fun queryCollectedLogs(request: cringle.daemon.v1.QueryCollectedLogsRequest): cringle.daemon.v1.QueryCollectedLogsResponse {
        val id = request.engineId.value
        val enabled = call { daemon.supervisor.get(id); daemon.collector.enabled(id) }
        val entries = call {
            val level = when (request.minLevel) {
                cringle.engine.v1.LogLevel.LOG_LEVEL_INFO -> cringle.contract.LogLevel.INFO
                cringle.engine.v1.LogLevel.LOG_LEVEL_WARN -> cringle.contract.LogLevel.WARN
                cringle.engine.v1.LogLevel.LOG_LEVEL_ERROR -> cringle.contract.LogLevel.ERROR
                else -> cringle.contract.LogLevel.DEBUG
            }
            daemon.collector.query(
                id,
                cringle.engine.drivers.LogQuery(
                    request.fabric.takeIf { it.isNotEmpty() }, request.block.takeIf { it.isNotEmpty() }, level,
                    if (request.hasSince()) java.time.Instant.ofEpochSecond(request.since.seconds, request.since.nanos.toLong()) else null,
                    if (request.limit > 0) request.limit else 1000,
                ),
            )
        }
        return cringle.daemon.v1.QueryCollectedLogsResponse.newBuilder().setEnabled(enabled).addAllEntries(
            entries.map {
                cringle.engine.v1.LogEntry.newBuilder().setTimestamp(Timestamp.newBuilder().setSeconds(it.timestamp.epochSecond).setNanos(it.timestamp.nano))
                    .setFabric(it.fabric).setBlock(it.block).setMessage(it.message)
                    .setLevel(
                        when (it.level) {
                            cringle.contract.LogLevel.DEBUG -> cringle.engine.v1.LogLevel.LOG_LEVEL_DEBUG
                            cringle.contract.LogLevel.INFO -> cringle.engine.v1.LogLevel.LOG_LEVEL_INFO
                            cringle.contract.LogLevel.WARN -> cringle.engine.v1.LogLevel.LOG_LEVEL_WARN
                            cringle.contract.LogLevel.ERROR -> cringle.engine.v1.LogLevel.LOG_LEVEL_ERROR
                        },
                    ).build()
            },
        ).build()
    }

    override suspend fun startAllEngines(request: StartAllEnginesRequest): ListEnginesResponse {
        call {
            for (e in daemon.supervisor.list()) {
                if (e.state != ProcessState.RUNNING && e.state != ProcessState.STARTING) {
                    try {
                        daemon.supervisor.start(e.id)
                    } catch (_: DaemonException) {
                        // reported through the state and last_error of that engine; the others still start
                    }
                }
            }
        }
        return ListEnginesResponse.newBuilder().addAllEngines(daemon.supervisor.list().map(::info)).build()
    }
}
