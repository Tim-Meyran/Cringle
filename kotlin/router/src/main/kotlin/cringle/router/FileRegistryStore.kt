// SPDX-License-Identifier: Apache-2.0

package cringle.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/** Thrown when the registry file exists but cannot be read; the router refuses to start with a damaged registry. */
public class RegistryStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Stores the registry as pretty-printed JSON in [file], written atomically (temporary file, then move). */
public class FileRegistryStore(private val file: Path) : RegistryStore {
    override fun load(): RegistryData {
        if (!Files.exists(file)) return RegistryData(emptyList(), emptyList())
        try {
            val root = Json.parseToJsonElement(Files.readString(file)) as JsonObject
            val format = (root["format"] as? JsonPrimitive)?.contentOrNull
            if (format != "1") throw RegistryStoreException("$file: unsupported registry format '$format'")
            return RegistryData(
                (root["engines"] as JsonArray).map { engine(it as JsonObject) },
                (root["remotes"] as JsonArray).map { remote(it as JsonObject) },
            )
        } catch (e: RegistryStoreException) {
            throw e
        } catch (e: Exception) {
            throw RegistryStoreException("$file: cannot read registry: ${e.message}", e)
        }
    }

    private fun text(o: JsonObject, key: String): String = (o[key] as JsonPrimitive).content

    private fun optText(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun engine(o: JsonObject) = EngineRecord(
        text(o, "id"),
        text(o, "name"),
        text(o, "managementAddress"),
        (o["fabrics"] as JsonArray).map { f ->
            val fo = f as JsonObject
            FabricSummary(text(fo, "fabricId"), text(fo, "blueprint"), text(fo, "state"))
        },
        optText(o, "origin"),
    )

    private fun remote(o: JsonObject) = RemoteRouterRecord(
        text(o, "address"),
        (o["engines"] as JsonArray).map { engine(it as JsonObject) },
        optText(o, "lastRefresh")?.let { Instant.parse(it) },
        optText(o, "lastError"),
    )

    override fun save(data: RegistryData) {
        Files.createDirectories(file.toAbsolutePath().parent)
        val json = buildJsonObject {
            put("format", "1")
            put("engines", JsonArray(data.engines.map { engine(it) }))
            put("remotes", JsonArray(data.remotes.map { remote(it) }))
        }
        val tmp = Files.createTempFile(file.toAbsolutePath().parent, "registry", ".tmp")
        Files.writeString(tmp, Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), json) + "\n")
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun engine(e: EngineRecord): JsonObject = buildJsonObject {
        put("id", e.id)
        put("name", e.name)
        put("managementAddress", e.managementAddress)
        put(
            "fabrics",
            JsonArray(
                e.fabrics.map { f ->
                    buildJsonObject {
                        put("fabricId", f.fabricId)
                        put("blueprint", f.blueprint)
                        put("state", f.state)
                    }
                },
            ),
        )
        e.origin?.let { put("origin", it) }
    }

    private fun remote(r: RemoteRouterRecord): JsonObject = buildJsonObject {
        put("address", r.address)
        put("engines", JsonArray(r.engines.map { engine(it) }))
        r.lastRefresh?.let { put("lastRefresh", it.toString()) }
        r.lastError?.let { put("lastError", it) }
    }
}
