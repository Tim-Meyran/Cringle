// SPDX-License-Identifier: Apache-2.0

package cringle.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Category of a [DaemonException]; the gRPC layer maps it to a status. */
public enum class DaemonError { NOT_FOUND, ALREADY_EXISTS, INVALID, FAILED_PRECONDITION }

/** Thrown for requests the daemon cannot fulfil. */
public class DaemonException(public val error: DaemonError, message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** An engine known to the daemon. */
public data class RegisteredEngine(val id: String, val name: String)

/** The persistent list of engines of this machine: `<home>/daemon/engines.json`, written atomically. */
public class EngineRegister(private val file: Path) {
    /** Loads the register; empty if the file does not exist. A damaged file is an error, never silently reset. */
    public fun load(): List<RegisteredEngine> {
        if (!Files.exists(file)) return emptyList()
        try {
            val root = Json.parseToJsonElement(Files.readString(file)) as JsonObject
            return (root["engines"] as JsonArray).map {
                val o = it as JsonObject
                RegisteredEngine((o["id"] as JsonPrimitive).content, (o["name"] as JsonPrimitive).content)
            }
        } catch (e: Exception) {
            throw DaemonException(DaemonError.FAILED_PRECONDITION, "$file: cannot read engine register: ${e.message}", e)
        }
    }

    /** Stores [engines]. */
    public fun save(engines: List<RegisteredEngine>) {
        val json = buildJsonObject {
            put("format", "1")
            putJsonArray("engines") {
                for (e in engines) add(buildJsonObject { put("id", e.id); put("name", e.name) })
            }
        }
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json) + "\n")
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
