// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.TreeMap

/** One entry of a lock file: the fixed [version], the [hash] of the package and its dependencies as resolved versions. */
public data class LockedPackage(val version: String, val hash: String, val dependencies: Map<String, String>)

/**
 * A lock file: the roots it was resolved for and one locked version per package. Encoding is deterministic, so the
 * same resolution always produces the same bytes.
 */
public data class LockFile(val roots: Map<String, String>, val packages: Map<String, LockedPackage>) {
    /** Returns everything that is inconsistent: dependencies that are not locked or locked at another version, bad hashes and versions. */
    public fun problems(): List<PackageProblem> {
        val problems = ArrayList<PackageProblem>()
        for ((name, p) in TreeMap(packages)) {
            val path = "$.packages.$name"
            if (!PackageNames.version.matches(p.version)) problems += PackageProblem("$path.version", "invalid version '${p.version}'")
            if (!hashPattern.matches(p.hash)) problems += PackageProblem("$path.hash", "must be 64 lowercase hex characters")
            for ((dep, v) in TreeMap(p.dependencies)) {
                val locked = packages[dep]
                when {
                    locked == null -> problems += PackageProblem("$path.dependencies.$dep", "'$dep' is not locked")
                    locked.version != v -> problems += PackageProblem("$path.dependencies.$dep", "locked at ${locked.version}, but $name expects $v")
                }
            }
        }
        for (name in TreeMap(roots).keys) {
            if (name !in packages) problems += PackageProblem("$.roots.$name", "root '$name' is not locked")
        }
        return problems
    }

    /** Writes the lock file: keys sorted, four-space indentation, `\n` line ends, trailing newline. */
    public fun encode(): String {
        fun strings(m: Map<String, String>) = JsonObject(TreeMap(m).mapValues { JsonPrimitive(it.value) })
        val json = JsonObject(
            linkedMapOf(
                "format" to JsonPrimitive(FORMAT),
                "roots" to strings(roots),
                "packages" to JsonObject(
                    TreeMap(packages).mapValues { (_, p) ->
                        JsonObject(
                            linkedMapOf(
                                "version" to JsonPrimitive(p.version),
                                "hash" to JsonPrimitive(p.hash),
                                "dependencies" to strings(p.dependencies),
                            ),
                        )
                    },
                ),
            ),
        )
        return pretty.encodeToString(JsonElement.serializer(), json).replace("\r\n", "\n") + "\n"
    }

    public companion object {
        /** Lock file format number. */
        public const val FORMAT: Int = 1
        private val hashPattern = Regex("[0-9a-f]{64}")
        private val pretty = Json { prettyPrint = true }

        /** Parses a lock file strictly; throws [PackageFormatException]. Consistency is checked by [problems]. */
        public fun parse(text: String): LockFile {
            val file = "lock file"
            val o = JsonReading.parseObject(text, file)
            JsonReading.keys(o, file, setOf("format", "roots", "packages"))
            val format = JsonReading.optInt(o, "format", file) ?: throw PackageFormatException(file, "missing key 'format'")
            if (format != FORMAT) throw PackageFormatException("$file $.format", "unsupported format $format")
            val roots = JsonReading.stringMap(o, "roots", "$")
            val packagesJson = o["packages"]?.let { JsonReading.obj(it, "$.packages") } ?: JsonObject(emptyMap())
            val packages = packagesJson.mapValues { (name, e) ->
                val path = "$.packages.$name"
                val p = JsonReading.obj(e, path)
                JsonReading.keys(p, path, setOf("version", "hash", "dependencies"))
                LockedPackage(
                    JsonReading.string(p, "version", path),
                    JsonReading.string(p, "hash", path),
                    JsonReading.stringMap(p, "dependencies", path),
                )
            }
            return LockFile(roots, packages)
        }
    }
}
