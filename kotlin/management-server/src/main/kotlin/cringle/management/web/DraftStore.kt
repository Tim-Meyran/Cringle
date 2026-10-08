// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.management.writeAtomically
import cringle.packaging.PackageNames
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A piece of work in progress: a schema document (`kind` `schema`) or a project (`project`), with a [version] for the package it becomes. */
public data class Draft(val kind: String, val name: String, val revision: Long, val version: String, val content: JsonElement)

/** The request is wrong (a name, a kind). */
public class DraftException(message: String) : RuntimeException(message)

/** The draft was changed since the caller read it. */
public class DraftConflictException(message: String) : RuntimeException(message)

/**
 * Drafts of the web layer, one JSON file `<root>/<kind>/<name>.json` each, written atomically. Each save raises the revision; a save that names the
 * revision it started from is refused if the draft moved on meanwhile (last writer does not silently win).
 */
public class DraftStore(private val root: Path) {
    private val lock = Any()

    private fun file(kind: String, name: String): Path {
        if (kind !in KINDS) throw DraftException("the kind of a draft is schema or project, not '$kind'")
        PackageNames.nameProblem(name)?.let { throw DraftException("draft name: $it") }
        // the name grammar has no separators or dots at the ends, but the check on the resolved path is cheap and does not depend on it
        val path = root.resolve(kind).resolve("$name.json").normalize()
        if (!path.startsWith(root.normalize().resolve(kind))) throw DraftException("invalid draft name '$name'")
        return path
    }

    /** The draft, or `null` if there is none. */
    public fun load(kind: String, name: String): Draft? = synchronized(lock) {
        val path = file(kind, name)
        if (!Files.exists(path)) return null
        val o = Json.parseToJsonElement(Files.readString(path)).jsonObject
        Draft(kind, name, o.getValue("revision").jsonPrimitive.content.toLong(), o.getValue("version").jsonPrimitive.content, o.getValue("content"))
    }

    /** The drafts of [kind], or of all kinds, sorted by kind and name. */
    public fun list(kind: String? = null): List<Draft> = synchronized(lock) {
        (if (kind == null) KINDS else listOf(kind)).flatMap { k ->
            val dir = root.resolve(k)
            if (!Files.isDirectory(dir)) {
                emptyList()
            } else {
                Files.list(dir).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.toList() }.sorted().mapNotNull { runCatching { load(k, it) }.getOrNull() }
            }
        }
    }

    /**
     * Saves [content] as [kind]/[name], creating the draft if needed. If [expectedRevision] is given and is not the current revision (0 for a draft
     * that does not exist), nothing is written. Returns the saved draft.
     */
    public fun save(kind: String, name: String, version: String, content: JsonElement, expectedRevision: Long? = null): Draft = synchronized(lock) {
        val path = file(kind, name)
        val current = load(kind, name)?.revision ?: 0L
        if (expectedRevision != null && expectedRevision != current) {
            throw DraftConflictException("$kind '$name' was changed meanwhile (revision $current, yours is $expectedRevision); reload it")
        }
        val next = Draft(kind, name, current + 1, version, content)
        val json = buildJsonObject {
            put("revision", next.revision)
            put("version", JsonPrimitive(version))
            put("content", content)
        }
        writeAtomically(path, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json))
        next
    }

    /** Deletes the draft; `false` if there was none. */
    public fun delete(kind: String, name: String): Boolean = synchronized(lock) { Files.deleteIfExists(file(kind, name)) }

    private companion object {
        val KINDS = listOf("schema", "project")
    }
}
