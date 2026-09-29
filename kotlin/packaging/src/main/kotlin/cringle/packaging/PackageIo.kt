// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Rules for entry names inside package ZIPs. */
public object EntryNames {
    /**
     * Returns a problem description if [name] is not a safe relative entry name, otherwise `null`. Unsafe are
     * empty names, absolute names, backslashes, drive letters, NUL, and `.`, `..` or empty path segments.
     */
    public fun problem(name: String): String? {
        val bare = name.removeSuffix("/")
        return when {
            bare.isEmpty() -> "empty entry name"
            bare.startsWith("/") -> "absolute entry name"
            '\\' in bare -> "backslash in entry name"
            ':' in bare -> "':' in entry name"
            '\u0000' in bare -> "NUL in entry name"
            bare.split('/').any { it.isEmpty() || it == "." || it == ".." } -> "'.', '..' or empty path segment in entry name"
            else -> null
        }
    }
}

/** Thrown when a package's SHA-256 hash is not the expected one. */
public class PackageHashMismatchException(public val expected: String, public val actual: String) :
    RuntimeException("package hash mismatch: expected $expected, actual $actual")

/** SHA-256 over the bytes of a package file, written as 64 lowercase hex characters. */
public object PackageHash {
    /** Computes the hash of [file]. */
    public fun sha256(file: Path): String = Files.newInputStream(file).use { sha256(it) }

    /** Computes the hash of [bytes]. */
    public fun sha256(bytes: ByteArray): String = sha256(bytes.inputStream())

    /** Throws [PackageHashMismatchException] unless the hash of [file] equals [expected] (hex, case-insensitive). */
    public fun verify(file: Path, expected: String) {
        val actual = sha256(file)
        if (!MessageDigest.isEqual(actual.toByteArray(), expected.lowercase().toByteArray())) {
            throw PackageHashMismatchException(expected, actual)
        }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Writes packages. Output is deterministic: entries are sorted by name, timestamps are fixed and no extra fields
 * are written, so the same content always produces the same bytes and therefore the same hash.
 */
public object PackageWriter {
    private val fixedTime = LocalDateTime.of(1980, 1, 1, 0, 0)

    /**
     * Writes a project package to [out]. [schemas] maps entry name (`schemas/x.json`) to text; [binaries] maps entry
     * name (`binaries/...`) to content.
     */
    public fun writeProject(
        manifest: ProjectManifest,
        blueprints: List<Blueprint>,
        schemas: Map<String, String>,
        binaries: Map<String, ByteArray>,
        out: OutputStream,
    ) {
        val expected = blueprints.map { "blueprints/${it.name}.json" }
        require(manifest.blueprints == expected) { "manifest.blueprints ${manifest.blueprints} must equal $expected" }
        val entries = HashMap<String, ByteArray>()
        entries[PackageKind.PROJECT.manifestFile] = utf8(ManifestJson.encode(manifest))
        for (b in blueprints) entries["blueprints/${b.name}.json"] = utf8(ManifestJson.encode(b))
        addFiles(entries, schemas.mapValues { utf8(it.value) }, "schemas/")
        addFiles(entries, binaries, "binaries/")
        write(entries, out)
    }

    /** Writes a plugin package to [out]. [libs] maps entry names (`lib/x.jar`) to content. */
    public fun writePlugin(
        manifest: PluginManifest,
        schemas: Map<String, String>,
        libs: Map<String, ByteArray>,
        binaries: Map<String, ByteArray>,
        out: OutputStream,
    ) {
        val entries = HashMap<String, ByteArray>()
        entries[PackageKind.PLUGIN.manifestFile] = utf8(ManifestJson.encode(manifest))
        addFiles(entries, schemas.mapValues { utf8(it.value) }, "schemas/")
        addFiles(entries, libs, "lib/")
        addFiles(entries, binaries, "binaries/")
        write(entries, out)
    }

    private fun utf8(text: String) = text.toByteArray(StandardCharsets.UTF_8)

    private fun addFiles(target: MutableMap<String, ByteArray>, files: Map<String, ByteArray>, prefix: String) {
        for ((name, content) in files) {
            EntryNames.problem(name)?.let { throw IllegalArgumentException("$name: $it") }
            require(name.startsWith(prefix) && !name.endsWith("/")) { "$name must be a file below '$prefix'" }
            target[name] = content
        }
    }

    private fun write(entries: Map<String, ByteArray>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            for (name in entries.keys.sorted()) {
                val entry = ZipEntry(name)
                entry.setTimeLocal(fixedTime)
                zip.putNextEntry(entry)
                zip.write(entries.getValue(name))
                zip.closeEntry()
            }
        }
    }
}

/** Reads and structurally checks packages. Semantic checks (references, schemas) are done by the validators. */
public object PackageReader {
    private const val MAX_ENTRIES = 10_000
    private const val MAX_TEXT_BYTES = 8 * 1024 * 1024

    /** Returns which kind of package [file] is, judged by its manifest entry. */
    public fun kind(file: Path): PackageKind = ZipFile(file.toFile()).use { zip ->
        val kinds = PackageKind.entries.filter { zip.getEntry(it.manifestFile) != null }
        kinds.singleOrNull() ?: throw PackageFormatException(
            file.fileName.toString(),
            if (kinds.isEmpty()) "no manifest (cringle-project.json or cringle-plugin.json)" else "contains both manifests",
        )
    }

    /** Reads a project package. */
    public fun readProject(file: Path): ProjectPackage = open(file, PackageKind.PROJECT) { zip, names ->
        val manifest = ManifestJson.parseProject(text(zip, PackageKind.PROJECT.manifestFile))
        checkListed(names, "blueprints/", manifest.blueprints, ".json")
        val blueprints = manifest.blueprints.map { entry ->
            val blueprint = ManifestJson.parseBlueprint(text(zip, entry), entry)
            if (entry != "blueprints/${blueprint.name}.json") {
                throw PackageFormatException(entry, "blueprint name '${blueprint.name}' must match the file name")
            }
            blueprint
        }
        val schemas = readSchemas(zip, names, manifest.schemas)
        checkPrefixes(names, setOf("blueprints/", "schemas/", "binaries/"))
        ProjectPackage(manifest, blueprints, schemas, names)
    }

    /** Reads a plugin package. */
    public fun readPlugin(file: Path): PluginPackage = open(file, PackageKind.PLUGIN) { zip, names ->
        val manifest = ManifestJson.parsePlugin(text(zip, PackageKind.PLUGIN.manifestFile))
        checkListed(names, "lib/", manifest.libs, ".jar")
        val schemas = readSchemas(zip, names, manifest.schemas)
        checkPrefixes(names, setOf("schemas/", "lib/", "binaries/"))
        PluginPackage(manifest, schemas, names)
    }

    private fun <T> open(file: Path, kind: PackageKind, body: (ZipFile, List<String>) -> T): T {
        val label = file.fileName.toString()
        val zip = try {
            ZipFile(file.toFile())
        } catch (e: java.io.IOException) {
            throw PackageFormatException(label, "not a readable ZIP file: ${e.message}")
        }
        zip.use {
            val names = ArrayList<String>()
            for (entry in zip.entries()) {
                EntryNames.problem(entry.name)?.let { throw PackageFormatException(entry.name, it) }
                if (entry.isDirectory) continue
                if (names.size >= MAX_ENTRIES) throw PackageFormatException(label, "more than $MAX_ENTRIES entries")
                names += entry.name
            }
            names.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let {
                throw PackageFormatException(it.key, "duplicate entry")
            }
            val other = PackageKind.entries.first { it != kind }
            if (other.manifestFile in names) throw PackageFormatException(other.manifestFile, "unexpected manifest in a ${kind.jsonName} package")
            if (kind.manifestFile !in names) throw PackageFormatException(label, "missing ${kind.manifestFile}")
            return body(zip, names.sorted())
        }
    }

    private fun text(zip: ZipFile, name: String): String {
        val entry = zip.getEntry(name) ?: throw PackageFormatException(name, "listed in the manifest but missing from the package")
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buffer.write(chunk, 0, n)
                if (buffer.size() > MAX_TEXT_BYTES) throw PackageFormatException(name, "larger than $MAX_TEXT_BYTES bytes")
            }
            return buffer.toString(StandardCharsets.UTF_8)
        }
    }

    private fun readSchemas(zip: ZipFile, names: List<String>, listed: List<String>): Map<String, String> {
        checkListed(names, "schemas/", listed, ".json")
        return listed.associateWith { text(zip, it) }
    }

    private fun checkListed(names: List<String>, prefix: String, listed: List<String>, suffix: String) {
        listed.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let {
            throw PackageFormatException(it.key, "listed more than once in the manifest")
        }
        for (entry in listed) {
            if (!entry.startsWith(prefix) || !entry.endsWith(suffix) || entry.removePrefix(prefix).contains('/')) {
                throw PackageFormatException(entry, "must be a '$suffix' file directly below '$prefix'")
            }
            if (entry !in names) throw PackageFormatException(entry, "listed in the manifest but missing from the package")
        }
        // 'lib/' may only hold listed jars; 'blueprints/' and 'schemas/' only listed files
        names.firstOrNull { it.startsWith(prefix) && it !in listed }
            ?.let { throw PackageFormatException(it, "not listed in the manifest") }
    }

    private fun checkPrefixes(names: List<String>, allowed: Set<String>) {
        for (name in names) {
            if (name == PackageKind.PROJECT.manifestFile || name == PackageKind.PLUGIN.manifestFile) continue
            if (allowed.none { name.startsWith(it) }) throw PackageFormatException(name, "unexpected entry outside ${allowed.sorted()}")
        }
    }
}

/** Extracts a package ZIP with protection against zip-slip and zip bombs. */
public object SafeUnzip {
    /** Default cap on the total number of bytes extracted (2 GiB). */
    public const val DEFAULT_MAX_BYTES: Long = 2L * 1024 * 1024 * 1024

    /**
     * Extracts [zip] into [target] (created if needed). Every entry must have a safe name and stay inside [target];
     * existing files are never overwritten; extraction aborts once more than [maxBytes] have been written.
     *
     * @throws PackageFormatException for an unsafe or conflicting entry or when the size cap is exceeded
     */
    public fun extract(zip: Path, target: Path, maxBytes: Long = DEFAULT_MAX_BYTES) {
        Files.createDirectories(target)
        val root = target.toRealPath()
        var total = 0L
        ZipFile(zip.toFile()).use { z ->
            for (entry in z.entries()) {
                EntryNames.problem(entry.name)?.let { throw PackageFormatException(entry.name, it) }
                val destination = root.resolve(entry.name).normalize()
                if (!destination.startsWith(root)) throw PackageFormatException(entry.name, "entry escapes the target directory")
                if (entry.isDirectory) {
                    Files.createDirectories(destination)
                    continue
                }
                Files.createDirectories(destination.parent)
                if (Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw PackageFormatException(entry.name, "already exists in the target directory")
                }
                z.getInputStream(entry).use { input ->
                    Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW).use { output ->
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw PackageFormatException(entry.name, "extracted size exceeds $maxBytes bytes")
                            output.write(chunk, 0, n)
                        }
                    }
                }
            }
        }
    }
}
