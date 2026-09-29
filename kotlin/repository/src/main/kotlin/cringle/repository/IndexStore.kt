// SPDX-License-Identifier: Apache-2.0

package cringle.repository

import cringle.packaging.PackageKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal data class IndexRecord(
    val kind: PackageKind,
    val name: String,
    val version: String,
    val sha256: String,
    val sizeBytes: Long,
    val dependencies: Map<String, String>,
    val publishedAt: Instant,
)

internal data class IndexData(val records: List<IndexRecord> = emptyList(), val trust: Map<String, PluginTrust> = emptyMap())

/** Thrown when the index exists but cannot be read; the repository refuses to start instead of losing content. */
public class RepositoryIndexException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal class IndexStore(private val file: Path) {
    fun load(): IndexData {
        if (!Files.exists(file)) return IndexData()
        try {
            val root = Json.parseToJsonElement(Files.readString(file)) as JsonObject
            if ((root["format"] as? JsonPrimitive)?.contentOrNull != "1") throw RepositoryIndexException("$file: unsupported index format")
            val records = (root["packages"] as JsonArray).map {
                val o = it as JsonObject
                fun text(k: String) = (o[k] as JsonPrimitive).content
                IndexRecord(
                    PackageKind.valueOf(text("kind")), text("name"), text("version"), text("sha256"), text("sizeBytes").toLong(),
                    (o["dependencies"] as JsonObject).mapValues { (_, v) -> (v as JsonPrimitive).content },
                    Instant.parse(text("publishedAt")),
                )
            }
            val trust = (root["trust"] as JsonObject).mapValues { (_, v) -> PluginTrust.valueOf((v as JsonPrimitive).content) }
            return IndexData(records, trust)
        } catch (e: RepositoryIndexException) {
            throw e
        } catch (e: Exception) {
            throw RepositoryIndexException("$file: cannot read repository index: ${e.message}", e)
        }
    }

    fun save(data: IndexData) {
        val json = buildJsonObject {
            put("format", "1")
            putJsonArray("packages") {
                for (r in data.records) add(
                    buildJsonObject {
                        put("kind", r.kind.name)
                        put("name", r.name)
                        put("version", r.version)
                        put("sha256", r.sha256)
                        put("sizeBytes", r.sizeBytes.toString())
                        put("dependencies", JsonObject(r.dependencies.toSortedMap().mapValues { JsonPrimitive(it.value) }))
                        put("publishedAt", r.publishedAt.toString())
                    },
                )
            }
            put("trust", JsonObject(data.trust.toSortedMap().mapValues { JsonPrimitive(it.value.name) }))
        }
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json) + "\n")
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
