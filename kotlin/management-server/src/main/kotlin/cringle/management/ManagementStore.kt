// SPDX-License-Identifier: Apache-2.0

package cringle.management

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A known machine: its Daemon and, optionally, the Repository that is responsible for it. */
public data class MachineRecord(
    val id: String,
    val daemonAddress: String,
    /** Host of the machine as reachable from the ManagementServer; used for the Engine management APIs. */
    val host: String,
    val repositoryAddress: String?,
)

/** An Engine that the ManagementServer created. */
public data class EngineRecord(
    val machine: String,
    val engineId: String,
    val autostart: Boolean,
    val roles: List<String> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
)

/** A fabric that was deployed through the ManagementServer; [deploy] is the serialized `DeployFabricRequest`. */
public data class FabricRecord(
    val machine: String,
    val engineId: String,
    val fabricId: String,
    val deploy: ByteArray,
    val desiredRunning: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is FabricRecord && machine == other.machine && engineId == other.engineId &&
        fabricId == other.fabricId && deploy.contentEquals(other.deploy) && desiredRunning == other.desiredRunning

    override fun hashCode(): Int = listOf(machine, engineId, fabricId, deploy.contentHashCode(), desiredRunning).hashCode()
}

/** A service dependency of [consumerProject] bound to the fabrics [targets] that provide [service] (#171). */
public data class BindingRecord(val consumerProject: String, val service: String, val targets: List<String>)

/** Everything the ManagementServer persists. */
public data class ManagementData(
    val machines: List<MachineRecord> = emptyList(),
    val engines: List<EngineRecord> = emptyList(),
    val fabrics: List<FabricRecord> = emptyList(),
    val bindings: List<BindingRecord> = emptyList(),
)

/** Thrown when the state file exists but cannot be read; the server refuses to start then instead of forgetting its machines. */
public class ManagementStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Persists [ManagementData] as JSON in [file], written atomically. */
public class ManagementStore(private val file: Path) {
    /** The directory of the state file; other files of the ManagementServer (locks) live below it. */
    public val directory: Path get() = file.toAbsolutePath().parent

    /** Reads the state; an empty state if there is no file yet. */
    public fun load(): ManagementData {
        if (!Files.exists(file)) return ManagementData()
        try {
            val root = Json.parseToJsonElement(Files.readString(file)) as JsonObject
            if ((root["format"] as? JsonPrimitive)?.contentOrNull != "1") throw ManagementStoreException("$file: unsupported format")
            fun JsonObject.text(k: String) = (this[k] as JsonPrimitive).content
            return ManagementData(
                machines = (root["machines"] as JsonArray).map {
                    val o = it as JsonObject
                    MachineRecord(o.text("id"), o.text("daemonAddress"), o.text("host"), (o["repositoryAddress"] as? JsonPrimitive)?.contentOrNull)
                },
                engines = (root["engines"] as JsonArray).map {
                    val o = it as JsonObject
                    EngineRecord(
                        o.text("machine"), o.text("engineId"), (o["autostart"] as JsonPrimitive).boolean,
                        (o["roles"] as? JsonArray)?.map { r -> (r as JsonPrimitive).content } ?: emptyList(),
                        (o["labels"] as? JsonObject)?.mapValues { (_, v) -> (v as JsonPrimitive).content } ?: emptyMap(),
                    )
                },
                fabrics = (root["fabrics"] as JsonArray).map {
                    val o = it as JsonObject
                    FabricRecord(
                        o.text("machine"), o.text("engineId"), o.text("fabricId"),
                        java.util.Base64.getDecoder().decode(o.text("deploy")), (o["desiredRunning"] as JsonPrimitive).boolean,
                    )
                },
                bindings = (root["bindings"] as? JsonArray)?.map {
                    val o = it as JsonObject
                    BindingRecord(o.text("consumerProject"), o.text("service"), (o["targets"] as JsonArray).map { t -> (t as JsonPrimitive).content })
                } ?: emptyList(),
            )
        } catch (e: ManagementStoreException) {
            throw e
        } catch (e: Exception) {
            throw ManagementStoreException("$file: cannot read state: ${e.message}", e)
        }
    }

    /** Writes the state. */
    public fun save(data: ManagementData) {
        val json = buildJsonObject {
            put("format", "1")
            putJsonArray("machines") {
                for (m in data.machines) add(buildJsonObject {
                    put("id", m.id)
                    put("daemonAddress", m.daemonAddress)
                    put("host", m.host)
                    if (m.repositoryAddress != null) put("repositoryAddress", m.repositoryAddress)
                })
            }
            putJsonArray("engines") {
                for (e in data.engines) add(buildJsonObject {
                    put("machine", e.machine)
                    put("engineId", e.engineId)
                    put("autostart", e.autostart)
                    putJsonArray("roles") { e.roles.forEach { add(JsonPrimitive(it)) } }
                    put("labels", JsonObject(e.labels.toSortedMap().mapValues { JsonPrimitive(it.value) }))
                })
            }
            putJsonArray("fabrics") {
                for (f in data.fabrics) add(buildJsonObject {
                    put("machine", f.machine)
                    put("engineId", f.engineId)
                    put("fabricId", f.fabricId)
                    put("deploy", java.util.Base64.getEncoder().encodeToString(f.deploy))
                    put("desiredRunning", f.desiredRunning)
                })
            }
            putJsonArray("bindings") {
                for (b in data.bindings) add(buildJsonObject {
                    put("consumerProject", b.consumerProject)
                    put("service", b.service)
                    putJsonArray("targets") { b.targets.forEach { add(JsonPrimitive(it)) } }
                })
            }
        }
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json) + "\n")
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
