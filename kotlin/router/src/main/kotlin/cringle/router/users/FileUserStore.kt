// SPDX-License-Identifier: Apache-2.0

package cringle.router.users

import cringle.common.OwnerOnlyFiles
import cringle.contract.UserRole
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Thrown when the user file exists but cannot be read; the router refuses to start then instead of resetting users. */
public class UserStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Stores [UserData] as JSON in [file], written atomically and, where the system allows, readable by the owner only. */
public class FileUserStore(private val file: Path) : UserStore {
    override fun load(): UserData {
        if (!Files.exists(file)) return UserData()
        try {
            val root = Json.parseToJsonElement(Files.readString(file)) as JsonObject
            if ((root["format"] as? JsonPrimitive)?.contentOrNull != "1") throw UserStoreException("$file: unsupported user store format")
            fun JsonObject.text(k: String) = (this[k] as JsonPrimitive).content
            fun JsonObject.strings(k: String) = (this[k] as JsonArray).map { (it as JsonPrimitive).content }
            fun roles(o: JsonObject) = o.strings("roles").map { UserRole.valueOf(it) }.toSet()
            // "scoped" is optional: a file written before scopes existed has none
            fun scoped(o: JsonObject) = (o["scoped"] as? JsonArray)?.map {
                val a = it as JsonObject
                RoleAssignment(UserRole.valueOf(a.text("role")), Scope.parse(a.text("scope")))
            }?.toSet() ?: emptySet()
            return UserData(
                users = (root["users"] as JsonArray).map { val o = it as JsonObject; User(o.text("id"), o.text("name"), roles(o), o.strings("groups").toSet(), scoped(o)) },
                groups = (root["groups"] as JsonArray).map { val o = it as JsonObject; Group(o.text("name"), roles(o), scoped(o)) },
                tokens = (root["tokens"] as JsonArray).map {
                    val o = it as JsonObject
                    TokenRecord(
                        o.text("id"), o.text("userId"), o.text("label"), o.text("hash"), Instant.parse(o.text("createdAt")),
                        (o["expiresAt"] as? JsonPrimitive)?.contentOrNull?.let(Instant::parse), (o["revoked"] as JsonPrimitive).boolean,
                    )
                },
                bootstrapped = (root["bootstrapped"] as JsonPrimitive).boolean,
            )
        } catch (e: UserStoreException) {
            throw e
        } catch (e: Exception) {
            throw UserStoreException("$file: cannot read user store: ${e.message}", e)
        }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.writeScoped(assignments: Set<RoleAssignment>) {
        for (a in assignments.sortedWith(compareBy({ it.scope.encode() }, { it.role }))) add(buildJsonObject {
            put("role", a.role.name)
            put("scope", a.scope.encode())
        })
    }

    override fun save(data: UserData) {
        val json = buildJsonObject {
            put("format", "1")
            put("bootstrapped", data.bootstrapped)
            putJsonArray("users") {
                for (u in data.users) add(buildJsonObject {
                    put("id", u.id)
                    put("name", u.name)
                    putJsonArray("roles") { u.roles.sorted().forEach { add(JsonPrimitive(it.name)) } }
                    putJsonArray("groups") { u.groups.sorted().forEach { add(JsonPrimitive(it)) } }
                    if (u.scoped.isNotEmpty()) putJsonArray("scoped") { writeScoped(u.scoped) }
                })
            }
            putJsonArray("groups") {
                for (g in data.groups) add(buildJsonObject {
                    put("name", g.name)
                    putJsonArray("roles") { g.roles.sorted().forEach { add(JsonPrimitive(it.name)) } }
                    if (g.scoped.isNotEmpty()) putJsonArray("scoped") { writeScoped(g.scoped) }
                })
            }
            putJsonArray("tokens") {
                for (t in data.tokens) add(buildJsonObject {
                    put("id", t.id)
                    put("userId", t.userId)
                    put("label", t.label)
                    put("hash", t.hash)
                    put("createdAt", t.createdAt.toString())
                    if (t.expiresAt != null) put("expiresAt", t.expiresAt.toString()) else put("expiresAt", JsonNull)
                    put("revoked", t.revoked)
                })
            }
        }
        Files.createDirectories(file.parent)
        OwnerOnlyFiles.writeAtomically(file, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json) + "\n")
    }
}
