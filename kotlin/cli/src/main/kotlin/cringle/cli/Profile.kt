// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import cringle.common.OwnerOnlyFiles
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The connection profile in `<home>/cli.json`: address of the ManagementServer, the user token that `login` stored and
 * the fingerprint of the key of the server, to which every connection is pinned. The file holds a secret and is written
 * with owner-only rights from the start.
 */
internal data class Profile(val server: String? = null, val token: String? = null, val fingerprint: String? = null) {
    fun save(file: Path) {
        val json = JsonObject(
            buildMap {
                put("server", server?.let { JsonPrimitive(it) } ?: JsonNull)
                put("token", token?.let { JsonPrimitive(it) } ?: JsonNull)
                put("fingerprint", fingerprint?.let { JsonPrimitive(it) } ?: JsonNull)
            },
        )
        // the token is a secret: the file never exists with other rights than the owner's
        OwnerOnlyFiles.writeAtomically(file, Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), json) + "\n")
    }

    companion object {
        fun load(file: Path): Profile {
            if (!Files.exists(file)) return Profile()
            val o = try {
                Json.parseToJsonElement(Files.readString(file)) as JsonObject
            } catch (e: Exception) {
                throw IllegalStateException("$file is not a valid profile: ${e.message}")
            }
            fun text(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            return Profile(text("server"), text("token"), text("fingerprint"))
        }
    }
}
