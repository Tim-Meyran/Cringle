// SPDX-License-Identifier: Apache-2.0

package cringle.repository

import cringle.packaging.PackageFormatException
import cringle.packaging.PackageHash
import cringle.packaging.PackageInfo
import cringle.packaging.PackageKind
import cringle.packaging.PackageNames
import cringle.packaging.PackageReader
import cringle.packaging.PackageSource
import cringle.packaging.PackageValidator
import cringle.packaging.PluginPackage
import cringle.packaging.ResolutionException
import cringle.packaging.Resolver
import cringle.packaging.Version
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant

/** The central trust status of a plugin; applies to all its versions and to every engine alike. */
public enum class PluginTrust { TRUSTED, UNTRUSTED }

/** Why a repository call failed. */
public enum class RepositoryError { INVALID, ALREADY_EXISTS, NOT_FOUND, TOO_LARGE, HASH_MISMATCH }

/** Thrown by [PackageRepository]; the message is meant to be shown to the user as is. */
public class RepositoryException(public val error: RepositoryError, message: String) : RuntimeException(message)

/** One published version of a package. [trust] is set for plugins only. */
public data class PackageEntry(
    val kind: PackageKind,
    val name: String,
    val version: String,
    val sha256: String,
    val sizeBytes: Long,
    val dependencies: Map<String, String>,
    val publishedAt: Instant,
    val trust: PluginTrust?,
)

/**
 * The content of one repository: packages on disk (`packages/<name>/<version>.cringle`) and an index with hashes,
 * dependencies and the trust status of plugins (`index.json`).
 *
 * Package names are unique across projects and plugins (the resolver addresses packages by name only). A version is
 * immutable once published: publishing it again is rejected, even with identical content. Every package is validated
 * with the `packaging` module before it is stored; its dependencies must already be available in this repository.
 * New plugins are untrusted until [setTrust] says otherwise.
 */
public class PackageRepository(
    private val dir: Path,
    private val clock: Clock = Clock.systemUTC(),
    /** Packages larger than this are rejected. */
    public val maxPackageBytes: Long = 512L * 1024 * 1024,
) {
    private val lock = Any()
    private val index = IndexStore(dir.resolve("index.json"))
    private var data: IndexData = index.load()

    private fun packageFile(name: String, version: String): Path = dir.resolve("packages").resolve(name).resolve("$version.cringle")

    private fun entry(r: IndexRecord) = PackageEntry(
        r.kind, r.name, r.version, r.sha256, r.sizeBytes, r.dependencies, r.publishedAt,
        if (r.kind == PackageKind.PLUGIN) data.trust[r.name] ?: PluginTrust.UNTRUSTED else null,
    )

    /**
     * Validates and stores the package file [file]. If [expectedHash] is given it must match the file.
     * The file is copied; the caller keeps ownership of [file].
     */
    public fun publish(file: Path, expectedHash: String? = null): PackageEntry = synchronized(lock) {
        val size = Files.size(file)
        if (size > maxPackageBytes) throw RepositoryException(RepositoryError.TOO_LARGE, "package is larger than $maxPackageBytes bytes")
        val hash = PackageHash.sha256(file)
        if (expectedHash != null && !expectedHash.equals(hash, ignoreCase = true)) {
            throw RepositoryException(RepositoryError.HASH_MISMATCH, "received bytes have hash $hash, expected $expectedHash")
        }
        val kind: PackageKind
        val name: String
        val version: String
        val dependencies: Map<String, String>
        val problems: List<String>
        try {
            kind = PackageReader.kind(file)
            if (kind == PackageKind.PLUGIN) {
                val plugin = PackageReader.readPlugin(file)
                name = plugin.manifest.name
                version = plugin.manifest.version
                dependencies = plugin.manifest.dependencies
                checkIdentity(name, version, kind)
                problems = PackageValidator.validatePlugin(plugin, dependencyPlugins(dependencies)).map { "${it.path}: ${it.message}" }
            } else {
                val project = PackageReader.readProject(file)
                name = project.manifest.name
                version = project.manifest.version
                dependencies = project.manifest.dependencies
                checkIdentity(name, version, kind)
                problems = PackageValidator.validateProject(project, dependencyPlugins(dependencies)).map { "${it.path}: ${it.message}" }
            }
        } catch (e: PackageFormatException) {
            throw RepositoryException(RepositoryError.INVALID, "invalid package: ${e.message}")
        } catch (e: java.util.zip.ZipException) {
            throw RepositoryException(RepositoryError.INVALID, "invalid package: not a ZIP file (${e.message})")
        }
        if (problems.isNotEmpty()) {
            throw RepositoryException(RepositoryError.INVALID, "invalid package:\n" + problems.joinToString("\n") { "  $it" })
        }
        val target = packageFile(name, version)
        Files.createDirectories(target.parent)
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
        val record = IndexRecord(kind, name, version, hash, size, dependencies, clock.instant())
        try {
            val next = data.copy(records = data.records + record)
            index.save(next)
            data = next
        } catch (e: Exception) {
            Files.deleteIfExists(target)
            throw e
        }
        entry(record)
    }

    private fun checkIdentity(name: String, version: String, kind: PackageKind) {
        if (!PackageNames.name.matches(name)) throw RepositoryException(RepositoryError.INVALID, "invalid package name '$name'")
        if (!PackageNames.version.matches(version)) throw RepositoryException(RepositoryError.INVALID, "invalid version '$version'")
        val existing = data.records.filter { it.name == name }
        if (existing.any { it.kind != kind }) {
            throw RepositoryException(RepositoryError.ALREADY_EXISTS, "the name '$name' is already used by a ${existing.first().kind.jsonName}; names are unique across projects and plugins")
        }
        if (existing.any { it.version == version }) {
            throw RepositoryException(RepositoryError.ALREADY_EXISTS, "$name@$version is already published; published versions are immutable, publish a new version")
        }
    }

    /** Resolves [dependencies] against this repository and returns the plugins among them, read from disk. */
    private fun dependencyPlugins(dependencies: Map<String, String>): List<PluginPackage> {
        if (dependencies.isEmpty()) return emptyList()
        val resolution = try {
            Resolver.resolve(dependencies, source)
        } catch (e: ResolutionException) {
            throw RepositoryException(RepositoryError.INVALID, "dependencies cannot be resolved in this repository: ${e.message}")
        }
        return resolution.packages.values.mapNotNull { info ->
            val record = data.records.first { it.name == info.name && it.version == info.version.toString() }
            if (record.kind == PackageKind.PLUGIN) PackageReader.readPlugin(packageFile(record.name, record.version)) else null
        }
    }

    /** All versions of all packages, optionally only of [kind]. */
    public fun list(kind: PackageKind? = null): List<PackageEntry> = synchronized(lock) {
        data.records.filter { kind == null || it.kind == kind }.sortedWith(compareBy({ it.name }, { Version.parse(it.version) })).map(::entry)
    }

    /** The versions of [name], oldest first; empty if unknown. */
    public fun versions(name: String): List<PackageEntry> = list().filter { it.name == name }

    /** The metadata of one version. */
    public fun get(name: String, version: String): PackageEntry = synchronized(lock) {
        data.records.firstOrNull { it.name == name && it.version == version }?.let(::entry)
            ?: throw RepositoryException(RepositoryError.NOT_FOUND, "$name@$version is not in this repository")
    }

    /** The stored file of one version. */
    public fun file(name: String, version: String): Path {
        get(name, version)
        return packageFile(name, version)
    }

    /** Sets the trust status of plugin [name] (all versions) and persists it. Returns the plugin's versions. */
    public fun setTrust(name: String, trust: PluginTrust): List<PackageEntry> = synchronized(lock) {
        val records = data.records.filter { it.name == name }
        if (records.isEmpty()) throw RepositoryException(RepositoryError.NOT_FOUND, "plugin '$name' is not in this repository")
        if (records.first().kind != PackageKind.PLUGIN) throw RepositoryException(RepositoryError.INVALID, "'$name' is a project; only plugins have a trust status")
        val next = data.copy(trust = data.trust + (name to trust))
        index.save(next)
        data = next
        versions(name)
    }

    private val source = object : PackageSource {
        override fun versions(name: String): List<Version> = data.records.filter { it.name == name }.map { Version.parse(it.version) }

        override fun info(name: String, version: Version): PackageInfo {
            val r = data.records.first { it.name == name && it.version == version.toString() }
            return PackageInfo(name, version, r.dependencies, r.sha256)
        }
    }

    /** This repository as a [PackageSource] for the resolver. */
    public fun asSource(): PackageSource = object : PackageSource {
        override fun versions(name: String): List<Version> = synchronized(lock) { source.versions(name) }

        override fun info(name: String, version: Version): PackageInfo = synchronized(lock) { source.info(name, version) }
    }
}
