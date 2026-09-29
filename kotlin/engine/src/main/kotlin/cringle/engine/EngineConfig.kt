// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Persistent engine configuration (`config.json` in the engine directory). */
public data class EngineConfig(val id: String, val name: String, val routerAddress: String? = null) {
    public companion object {
        /** File name inside the engine directory. */
        public const val FILE: String = "config.json"

        private val pretty = Json { prettyPrint = true }

        /**
         * Loads the config of [dir], or creates it from [id] and [name] (default: the id) if it does not exist yet.
         * An existing config is never changed by the arguments; a different `--id` in it is an error.
         */
        public fun loadOrCreate(dir: Path, id: String, name: String?): EngineConfig {
            val file = dir.resolve(FILE)
            if (Files.exists(file)) {
                val stored = read(file)
                if (stored.id != id) throw IllegalStateException("$file belongs to engine '${stored.id}', not '$id'")
                return stored
            }
            Files.createDirectories(dir)
            return EngineConfig(id, name ?: id).also { it.save(dir) }
        }

        private fun read(file: Path): EngineConfig {
            val o = Json.parseToJsonElement(Files.readString(file)) as? JsonObject
                ?: throw IllegalStateException("$file is not a JSON object")
            fun text(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            return EngineConfig(
                text("id") ?: throw IllegalStateException("$file: missing 'id'"),
                text("name") ?: throw IllegalStateException("$file: missing 'name'"),
                text("routerAddress"),
            )
        }
    }

    /** Writes the config atomically (temporary file, then move). */
    public fun save(dir: Path) {
        val json = buildJsonObject {
            put("id", id)
            put("name", name)
            routerAddress?.let { put("routerAddress", it) }
        }
        val tmp = Files.createTempFile(dir, "config", ".tmp")
        Files.writeString(tmp, pretty.encodeToString(JsonElement.serializer(), json) + "\n")
        Files.move(tmp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
