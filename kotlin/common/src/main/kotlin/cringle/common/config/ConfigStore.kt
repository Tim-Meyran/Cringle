// SPDX-License-Identifier: Apache-2.0

package cringle.common.config

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.Path

/** Thrown for a key or a value that the catalog refuses; the message says why. */
public class ConfigException(message: String) : IllegalArgumentException(message)

/** One setting as the store answers it: [value] is the one in effect, [isSet] says whether the file has it, otherwise it is the [ConfigKey.default]. */
public data class ConfigValue(val key: ConfigKey, val value: String, val isSet: Boolean)

/**
 * The key-value store of a machine (#314): the file `<home>/config/cringle.conf`, lines `key=value` (empty lines and `#` comments are kept). It is
 * owned by the daemon, which reads it when it starts and applies changes (#315); the installers and `cringle setup` write it too. Writes are
 * atomic (the file holds no secrets). A line that is not `key=value` or names a key that the catalog does not have is kept in the file and
 * reported in [problems]; it has no effect.
 */
public class ConfigStore(public val file: Path) {
    private val lock = Any()

    /** What the file has: the set keys with their values, and the lines that cannot be used. */
    private fun read(): Pair<LinkedHashMap<String, String>, List<String>> {
        val set = LinkedHashMap<String, String>()
        val problems = ArrayList<String>()
        if (!Files.isRegularFile(file)) return set to problems
        for ((index, raw) in Files.readAllLines(file).withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) {
                problems += "line ${index + 1}: not key=value"
                continue
            }
            val name = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            val key = ConfigCatalog.find(name)
            if (key == null) {
                problems += "line ${index + 1}: unknown key '$name'"
            } else if (key.problem(value) != null) {
                problems += "line ${index + 1}: $name ${key.problem(value)}"
            } else {
                set[name] = key.normalize(value)
            }
        }
        return set to problems
    }

    /** The lines of the file that cannot be used (unknown key, no `=`, a value the catalog refuses); empty if all is well or the file is missing. */
    public val problems: List<String> get() = synchronized(lock) { read().second }

    /** Every setting of the catalog, in its order, with the value in effect. */
    public fun all(): List<ConfigValue> = synchronized(lock) {
        val set = read().first
        ConfigCatalog.keys.map { ConfigValue(it, set[it.name] ?: it.default, it.name in set) }
    }

    /** The value in effect of [name]. */
    public fun get(name: String): String = synchronized(lock) { value(name) }

    private fun value(name: String): String {
        val key = ConfigCatalog.find(name) ?: throw ConfigException("unknown key '$name'")
        return read().first[name] ?: key.default
    }

    /** Whether the file has [name] (otherwise its default is in effect). */
    public fun isSet(name: String): Boolean = synchronized(lock) {
        ConfigCatalog.find(name) ?: throw ConfigException("unknown key '$name'")
        name in read().first
    }

    /** Sets [name] to [value] (written in its normal form, which is returned); throws [ConfigException] if the catalog refuses the key or the value. */
    public fun set(name: String, value: String): String = synchronized(lock) {
        val key = ConfigCatalog.find(name) ?: throw ConfigException("unknown key '$name'")
        key.problem(value.trim())?.let { throw ConfigException("$name $it") }
        val normal = key.normalize(value)
        write(name, normal)
        normal
    }

    /** Removes [name] from the file: its default is in effect again. Does nothing if it was not set. */
    public fun unset(name: String): Unit = synchronized(lock) {
        ConfigCatalog.find(name) ?: throw ConfigException("unknown key '$name'")
        write(name, null)
    }

    /** Sets several settings at once; nothing is written if one of them is refused. */
    public fun setAll(values: Map<String, String>): Unit = synchronized(lock) {
        val normal = LinkedHashMap<String, String>()
        for ((name, value) in values) {
            val key = ConfigCatalog.find(name) ?: throw ConfigException("unknown key '$name'")
            key.problem(value.trim())?.let { throw ConfigException("$name $it") }
            normal[name] = key.normalize(value)
        }
        var text = if (Files.isRegularFile(file)) Files.readString(file) else HEADER
        for ((name, value) in normal) text = replaceLine(text, name, value)
        writeAtomically(text)
    }

    /** Writes [text] to the file through a temporary file and a move. The file is not owner-only: it holds no secrets, and the daemon (a service user) and `cringle setup` (root) both write it. */
    private fun writeAtomically(text: String) {
        val directory = file.toAbsolutePath().parent
        Files.createDirectories(directory)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        try {
            Files.writeString(tmp, text)
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private fun write(name: String, value: String?) {
        val existing = if (Files.isRegularFile(file)) Files.readString(file) else null
        if (existing == null && value == null) return
        writeAtomically(replaceLine(existing ?: HEADER, name, value))
    }

    /** [text] with the line of [name] replaced by `name=value`, or removed if [value] is `null`; a missing line is appended. */
    private fun replaceLine(text: String, name: String, value: String?): String {
        val lines = text.lines().toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        val index = lines.indexOfFirst { line -> line.trim().let { !it.startsWith("#") && it.substringBefore('=').trim() == name && '=' in it } }
        when {
            index >= 0 && value != null -> lines[index] = "$name=$value"
            index >= 0 -> lines.removeAt(index)
            value != null -> lines += "$name=$value"
        }
        return lines.joinToString("\n") + "\n"
    }

    /**
     * The migration of the first start (#314): when the file does not exist, creates it from [environment] (the old `CRINGLE_*` variables) and [arguments]
     * (settings the daemon was started with, which win), once. Values the catalog refuses are left out. Returns whether the file was created.
     */
    public fun migrate(environment: Map<String, String>, arguments: Map<String, String> = emptyMap()): Boolean = synchronized(lock) {
        if (Files.exists(file)) return false
        val values = LinkedHashMap<String, String>()
        for ((variable, name) in ConfigCatalog.environmentNames) environment[variable]?.trim()?.takeIf { it.isNotEmpty() }?.let { values[name] = it }
        values.putAll(arguments)
        val valid = LinkedHashMap<String, String>()
        for ((name, value) in values) {
            val key = ConfigCatalog.find(name) ?: continue
            if (key.problem(value.trim()) == null) valid[name] = key.normalize(value)
        }
        var text = HEADER
        for ((name, value) in valid) text = replaceLine(text, name, value)
        java.nio.file.Files.createDirectories(file.parent)
        writeAtomically(text)
        true
    }

    private companion object {
        const val HEADER = "# Settings of the Cringle services of this machine. Change them with `cringle config` or the web interface, or here and restart the daemon.\n"
    }
}
