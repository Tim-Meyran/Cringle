// SPDX-License-Identifier: Apache-2.0

package cringle.common

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What a trusted peer is. */
public enum class TrustKind { ROUTER, ENGINE, COMPONENT, SERVER }

/**
 * One trusted peer. [fingerprint] is the [PublicKeyFingerprint] of the peer's key. [origin] is `null` for trust that was
 * given directly and otherwise the fingerprint of the router that vouched for this peer.
 */
public data class TrustEntry(
    val fingerprint: String,
    val name: String,
    val kind: TrustKind,
    val address: String? = null,
    val origin: String? = null,
    val addedAt: Instant = Instant.now(),
)

/**
 * The file-based trust store of one component (`<home>/trust.json`): the peers whose public key fingerprint this
 * component accepts in a TLS handshake. It is read once when the store is opened and written through on every change
 * (atomically, readable by the owner only). Changes made to the file by another process are not noticed while this
 * store is open. Thread-safe.
 */
public class TrustStore(private val file: Path) {
    private val entries = LinkedHashMap<String, TrustEntry>()
    private val listeners = CopyOnWriteArrayList<(List<TrustEntry>) -> Unit>()

    init {
        if (Files.exists(file)) {
            val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
            for (element in root.getValue("entries").jsonArray) {
                val entry = decode(element.jsonObject)
                entries[entry.fingerprint] = entry
            }
        }
    }

    /** Trusts [entry]; an entry with the same fingerprint is replaced. */
    public fun add(entry: TrustEntry) {
        require(PublicKeyFingerprint.pattern.matches(entry.fingerprint)) { "not a public key fingerprint: '${entry.fingerprint}'" }
        require(entry.origin == null || PublicKeyFingerprint.pattern.matches(entry.origin)) { "origin is not a public key fingerprint" }
        require(entry.origin != entry.fingerprint) { "a peer cannot vouch for itself" }
        change { entries[entry.fingerprint] = entry }
    }

    /**
     * Removes the entry with [fingerprint] and every entry that came through it (`origin == fingerprint`).
     * Returns `false` if nothing was removed.
     */
    public fun remove(fingerprint: String): Boolean {
        val doomed = synchronized(this) {
            entries.values.filter { it.fingerprint == fingerprint || it.origin == fingerprint }.map { it.fingerprint }
        }
        if (doomed.isEmpty()) return false
        change { doomed.forEach { entries.remove(it) } }
        return true
    }

    /** All entries, in the order they were added. */
    public fun list(): List<TrustEntry> = synchronized(this) { entries.values.toList() }

    /** Whether a peer with this public key fingerprint is trusted. */
    public fun isTrusted(fingerprint: String): Boolean = synchronized(this) { fingerprint in entries }

    /** Calls [listener] with the new list after every change; close the result to stop. */
    public fun onChange(listener: (List<TrustEntry>) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun change(mutation: () -> Unit) {
        val snapshot = synchronized(this) {
            val before = LinkedHashMap(entries)
            mutation()
            try {
                save()
            } catch (e: Throwable) {
                entries.clear()
                entries.putAll(before)
                throw e
            }
            entries.values.toList()
        }
        listeners.forEach { it(snapshot) }
    }

    private fun save() {
        val root = buildJsonObject {
            put("version", 1)
            put("entries", JsonArray(entries.values.map(::encode)))
        }
        OwnerOnlyFiles.writeAtomically(file, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root) + "\n")
    }

    private fun encode(e: TrustEntry): JsonObject = buildJsonObject {
        put("fingerprint", e.fingerprint)
        put("name", e.name)
        put("kind", e.kind.name)
        put("address", e.address?.let { JsonPrimitive(it) } ?: JsonNull)
        put("origin", e.origin?.let { JsonPrimitive(it) } ?: JsonNull)
        put("addedAt", e.addedAt.toString())
    }

    private fun decode(o: JsonObject): TrustEntry = TrustEntry(
        fingerprint = o.getValue("fingerprint").jsonPrimitive.content,
        name = o.getValue("name").jsonPrimitive.content,
        kind = TrustKind.valueOf(o.getValue("kind").jsonPrimitive.content),
        address = o["address"]?.jsonPrimitive?.contentOrNull,
        origin = o["origin"]?.jsonPrimitive?.contentOrNull,
        addedAt = Instant.parse(o.getValue("addedAt").jsonPrimitive.content),
    )
}
