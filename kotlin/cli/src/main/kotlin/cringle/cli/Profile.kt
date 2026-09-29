// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The connection profile in `<home>/cli.json`: address of the ManagementServer and the user token that `login`
 * stored. A client certificate will be added with mTLS (issue #13).
 */
internal data class Profile(val server: String? = null, val token: String? = null) {
    fun save(file: Path) {
        val json = JsonObject(
            buildMap {
                put("server", server?.let { JsonPrimitive(it) } ?: JsonNull)
                put("token", token?.let { JsonPrimitive(it) } ?: JsonNull)
            },
        )
        Files.createDirectories(file.toAbsolutePath().parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), json) + "\n")
        try {
            // the token is a secret
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
        } catch (_: Exception) {
            // not a POSIX file system
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
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
            return Profile(text("server"), text("token"))
        }
    }
}
