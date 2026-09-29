// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import java.util.TreeMap

/** What the resolver needs to know about one version of a package. */
public data class PackageInfo(
    val name: String,
    val version: Version,
    /** Dependency name to version range (opaque strings from the manifest). */
    val dependencies: Map<String, String>,
    /** SHA-256 of the package file (see [PackageHash]). */
    val hash: String,
)

/** Where the resolver finds packages. Implemented by the repository client later; tests use in-memory sources. */
public interface PackageSource {
    /** All available versions of [name]; empty if the package does not exist. */
    public fun versions(name: String): List<Version>

    /** Information about one version; only called for versions returned by [versions]. */
    public fun info(name: String, version: Version): PackageInfo
}

/** Kinds of resolution failure. */
public enum class ResolutionFailure {
    /** A required package is not available at all. */
    UNKNOWN_PACKAGE,

    /** Versions exist, but none satisfies all constraints. */
    NO_SATISFYING_VERSION,

    /** The resolved dependency graph contains a cycle. */
    CYCLE,

    /** A dependency range could not be parsed. */
    INVALID_RANGE,
}

/** Thrown when resolution fails; [message] is meant to be shown to the user as is. */
public class ResolutionException(public val failure: ResolutionFailure, message: String) : RuntimeException(message)

/** The outcome of a resolution: exactly one version per package name, sorted by name. */
public class Resolution(public val roots: Map<String, String>, packages: Map<String, PackageInfo>) {
    /** The resolved packages by name. */
    public val packages: Map<String, PackageInfo> = java.util.Collections.unmodifiableMap(TreeMap(packages))

    /** Converts to a lock file model. */
    public fun toLock(): LockFile = LockFile(
        roots,
        packages.mapValues { (_, info) ->
            LockedPackage(
                info.version.toString(),
                info.hash,
                info.dependencies.keys.associateWith { packages.getValue(it).version.toString() },
            )
        },
    )
}

/**
 * Resolves root dependencies to one version per package. The result does not depend on the order of the roots or of
 * any package's dependencies: packages are processed alphabetically and versions are tried from the highest down,
 * with backtracking.
 */
public object Resolver {
    private class Constraint(val range: VersionRange, val requiredBy: String)

    private class Failure(val depth: Int, val exception: ResolutionException)

    /** Resolves [roots] (name to range) against [source]. */
    public fun resolve(roots: Map<String, String>, source: PackageSource): Resolution {
        val initial = TreeMap<String, List<Constraint>>()
        for ((name, range) in roots) initial[name] = listOf(Constraint(parseRange(name, range, "the root"), "the root"))
        val search = Search(source)
        val chosen = search.solve(TreeMap(), initial) ?: throw search.best!!.exception
        checkCycles(chosen)
        return Resolution(TreeMap(roots), chosen)
    }

    private fun parseRange(name: String, range: String, owner: String): VersionRange = try {
        VersionRange.parse(range)
    } catch (e: IllegalArgumentException) {
        throw ResolutionException(ResolutionFailure.INVALID_RANGE, "invalid range '$range' for '$name' required by $owner: ${e.message}")
    }

    private class Search(val source: PackageSource) {
        var best: Failure? = null
        private val versionCache = HashMap<String, List<Version>>()

        private fun versions(name: String) = versionCache.getOrPut(name) { source.versions(name).sortedDescending() }

        private fun fail(depth: Int, failure: ResolutionFailure, message: String) {
            if (best == null || depth > best!!.depth) best = Failure(depth, ResolutionException(failure, message))
        }

        fun solve(chosen: TreeMap<String, PackageInfo>, constraints: TreeMap<String, List<Constraint>>): Map<String, PackageInfo>? {
            val name = constraints.keys.firstOrNull { it !in chosen } ?: return HashMap(chosen)
            val all = constraints.getValue(name)
            val available = versions(name)
            if (available.isEmpty()) {
                fail(chosen.size, ResolutionFailure.UNKNOWN_PACKAGE, "package '$name' is not available (required by ${all.map { it.requiredBy }.distinct().sorted().joinToString(", ")})")
                return null
            }
            val candidates = available.filter { v -> all.all { it.range.matches(v) } }
            if (candidates.isEmpty()) {
                fail(
                    chosen.size,
                    ResolutionFailure.NO_SATISFYING_VERSION,
                    "no version of '$name' satisfies all constraints:\n" +
                        all.sortedBy { it.requiredBy }.joinToString("\n") { "  ${it.range} (required by ${it.requiredBy})" } +
                        "\navailable versions: ${available.sorted().joinToString(", ")}",
                )
                return null
            }
            for (version in candidates) {
                val info = source.info(name, version)
                val next = TreeMap(constraints)
                val owner = "$name@$version"
                var ok = true
                for ((dep, rangeText) in TreeMap(info.dependencies)) {
                    val range = parseRange(dep, rangeText, owner)
                    val already = chosen[dep]
                    if (already != null && !range.matches(already.version)) {
                        fail(
                            chosen.size + 1,
                            ResolutionFailure.NO_SATISFYING_VERSION,
                            "no version of '$dep' satisfies all constraints:\n" +
                                (constraints[dep].orEmpty() + Constraint(range, owner)).sortedBy { it.requiredBy }
                                    .joinToString("\n") { "  ${it.range} (required by ${it.requiredBy})" } +
                                "\nalready selected: ${already.version}",
                        )
                        ok = false
                        break
                    }
                    next[dep] = next[dep].orEmpty() + Constraint(range, owner)
                }
                if (!ok) continue
                val nextChosen = TreeMap(chosen)
                nextChosen[name] = info
                solve(nextChosen, next)?.let { return it }
            }
            return null
        }
    }

    private fun checkCycles(packages: Map<String, PackageInfo>) {
        val state = HashMap<String, Int>() // 1 = visiting, 2 = done
        val stack = ArrayList<String>()
        fun visit(name: String) {
            when (state[name]) {
                2 -> return
                1 -> {
                    val cycle = stack.subList(stack.indexOf(name), stack.size) + name
                    throw ResolutionException(ResolutionFailure.CYCLE, "dependency cycle: ${cycle.joinToString(" -> ")}")
                }
            }
            state[name] = 1
            stack += name
            for (dep in TreeMap(packages.getValue(name).dependencies).keys) visit(dep)
            stack.removeAt(stack.size - 1)
            state[name] = 2
        }
        for (name in TreeMap(packages).keys) visit(name)
    }
}
