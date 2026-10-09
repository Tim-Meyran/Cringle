// SPDX-License-Identifier: Apache-2.0

package cringle.contract

import java.nio.file.Path

/** Where a [Processor] works: the data of one block of a plugin, or the data of all blocks of a project's blueprint instance. */
public enum class MigrationScope {
    /** The processor of a plugin: [MigrationContext.dataDirectory] is the data folder of one block of that plugin, [MigrationContext.blockId] names it. */
    PLUGIN,

    /** The processor of a project: [MigrationContext.dataDirectory] is the folder of the whole instance (`<home>/data/<project>/<blueprint>/<n>`), with a folder per block below it. */
    PROJECT,
}

/** What a [Processor] gets for one migration. */
public interface MigrationContext {
    /** The version the data was written by. */
    public val from: String

    /** The version the data has to be brought to. */
    public val to: String

    /** Whether the processor belongs to a plugin or to a project. */
    public val scope: MigrationScope

    /** The block whose data is migrated (scope [MigrationScope.PLUGIN]); `null` for a project. */
    public val blockId: String?

    /** The folder to migrate (see [scope]); a copy of it was made before the processor runs. */
    public val dataDirectory: Path

    /** Writes a line to the log of the fabric. */
    public fun log(message: String)
}

/** A migration failed. The message says what; it ends up in the status `MIGRATION_FAILED` of the fabric. */
public class MigrationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Migrates persisted data when the version of a plugin or a project changes (Architecture 14.3). The engine creates it with its no-argument constructor,
 * makes a backup of the data folder and calls [migrate] once with the version the data was written by and the version it has to be brought to. A processor named
 * in `processors.update` runs when the version goes up, one in `processors.downgrade` when it goes down. If [migrate] throws, nothing is repaired
 * automatically: the fabric stays stopped until somebody has fixed it. Several versions at once (1 to 2 to 3) are the job of the processor; [SteppedProcessor]
 * does it for you.
 */
public fun interface Processor {
    /** Brings the data of [context] from [MigrationContext.from] to [MigrationContext.to]. */
    public fun migrate(context: MigrationContext)
}

/** One step of a [SteppedProcessor]. */
public fun interface MigrationStep {
    /** Does the step; throws [MigrationException] (or anything else) if it cannot. */
    public fun run(context: MigrationContext)
}

/**
 * A [Processor] made of steps, each tied to a version. Register them in the constructor with [step]: `step("2.0.0") { ... }` is "what is needed to get to
 * 2.0.0 from the version before". An update from `from` to `to` runs the steps with `from < version <= to` in ascending order; a downgrade (`to < from`)
 * runs those with `to < version <= from` in descending order (a step of a downgrade processor undoes that version). Versions are compared as
 * `major.minor.patch` numbers, a pre-release (`-rc1`) is before its release. A step that throws stops the chain; the exception says which step it was.
 */
public abstract class SteppedProcessor : Processor {
    private val steps = ArrayList<Pair<String, MigrationStep>>()

    /** Registers the step for [version]. */
    protected fun step(version: String, action: MigrationStep) {
        require(SemanticVersion.parseOrNull(version) != null) { "'$version' is not a version (major.minor.patch)" }
        require(steps.none { it.first == version }) { "there is already a step for $version" }
        steps += version to action
    }

    final override fun migrate(context: MigrationContext) {
        val from = SemanticVersion.parseOrNull(context.from) ?: throw MigrationException("'${context.from}' is not a version")
        val to = SemanticVersion.parseOrNull(context.to) ?: throw MigrationException("'${context.to}' is not a version")
        val chosen = if (from < to) {
            steps.map { SemanticVersion.parseOrNull(it.first)!! to it }.filter { it.first > from && it.first <= to }.sortedBy { it.first }
        } else {
            steps.map { SemanticVersion.parseOrNull(it.first)!! to it }.filter { it.first > to && it.first <= from }.sortedByDescending { it.first }
        }
        for ((_, entry) in chosen) {
            context.log("migration step ${entry.first} (${context.from} to ${context.to})")
            try {
                entry.second.run(context)
            } catch (e: MigrationException) {
                throw MigrationException("step ${entry.first}: ${e.message}", e)
            } catch (e: Exception) {
                throw MigrationException("step ${entry.first}: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }
    }
}

/** A version `major.minor.patch[-pre-release]`, ordered as semantic versioning says; no dependency on the packaging module. */
internal class SemanticVersion(private val numbers: List<Long>, private val pre: String?) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        for (i in 0 until 3) numbers[i].compareTo(other.numbers[i]).let { if (it != 0) return it }
        return when {
            pre == null && other.pre == null -> 0
            pre == null -> 1
            other.pre == null -> -1
            else -> comparePre(pre, other.pre)
        }
    }

    private fun comparePre(a: String, b: String): Int {
        val x = a.split('.')
        val y = b.split('.')
        for (i in 0 until minOf(x.size, y.size)) {
            val nx = x[i].toLongOrNull()
            val ny = y[i].toLongOrNull()
            val c = when {
                nx != null && ny != null -> nx.compareTo(ny)
                nx != null -> -1
                ny != null -> 1
                else -> x[i].compareTo(y[i])
            }
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }

    companion object {
        private val pattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z.-]+))?")

        fun parseOrNull(text: String): SemanticVersion? {
            val m = pattern.matchEntire(text) ?: return null
            return SemanticVersion(listOf(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLong()), m.groupValues[4].ifEmpty { null })
        }
    }
}
