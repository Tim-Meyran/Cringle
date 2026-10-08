// SPDX-License-Identifier: Apache-2.0

package cringle.engine.fabric

import cringle.contract.MigrationContext
import cringle.contract.MigrationException
import cringle.contract.MigrationScope
import cringle.contract.Processor
import cringle.packaging.ProcessorSet
import cringle.packaging.Version
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/** A migration failed; the message says which one and where the backup is. The fabric stays stopped in the state `MIGRATION_FAILED`. */
public class MigrationFailedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * One data folder that a [Processor] can migrate: the folder of one block (scope PLUGIN, [identity] is the plugin name) or the folder of the whole
 * instance (scope PROJECT, [identity] is the project name). [version] is the version that runs now; [create] instantiates the processor class [processors] names.
 */
public class MigrationUnit(
    public val scope: MigrationScope,
    public val blockId: String?,
    public val identity: String,
    public val version: String,
    public val folder: Path,
    public val processors: ProcessorSet,
    public val create: (String) -> Processor,
) {
    internal val key: String get() = if (scope == MigrationScope.PROJECT) "project" else "block.$blockId"
    internal val label: String get() = if (scope == MigrationScope.PROJECT) "project $identity" else "block '$blockId' (plugin $identity)"
}

/**
 * Migrates persisted data when the version of a plugin or project changes (Architecture 14.3). The version a folder was written by is recorded in
 * `<data base>/.cringle/versions.properties` (a dot name, so no block id can clash with it). When a unit runs with another version of the same plugin
 * or project, the folder is copied to `<folder>.backup-<from>`, the update or downgrade processor runs, and the new version is recorded. A first run,
 * a different plugin in the same block, or a version without a processor only records the version. A failing processor stops everything: the version
 * stays, the backup stays, nothing is retried by itself; the next start runs the processor again on the folder as it is, and keeps the first backup.
 */
public class DataMigrations(private val dataBase: Path, private val units: List<MigrationUnit>) {
    private val markers: Path = dataBase.resolve(".cringle").resolve("versions.properties")

    /** Runs what is due; throws [MigrationFailedException] at the first failure. [log] gets one line per event. */
    public fun run(log: (String) -> Unit) {
        val recorded = Properties().also { p -> if (Files.isRegularFile(markers)) Files.newBufferedReader(markers).use { p.load(it) } }
        for (unit in units) {
            val now = "${unit.identity}@${unit.version}"
            val before = recorded.getProperty(unit.key)
            if (before == now) continue
            val processor = before?.takeIf { it.substringBefore('@') == unit.identity }?.let { due(unit, it.substringAfter('@')) }
            if (processor != null) migrate(unit, before.substringAfter('@'), processor, log)
            recorded.setProperty(unit.key, now)
            Files.createDirectories(markers.parent)
            Files.newBufferedWriter(markers).use { recorded.store(it, "versions the data folders were written by") }
        }
    }

    /** The class name to run to get from [from] to the version of [unit], or `null` if there is none (also for an unreadable version). */
    private fun due(unit: MigrationUnit, from: String): Pair<String, MigrationScope>? {
        val old = runCatching { Version.parse(from) }.getOrNull() ?: return null
        val new = Version.parse(unit.version)
        val name = when {
            new > old -> unit.processors.update
            new < old -> unit.processors.downgrade
            else -> null
        }
        return name?.let { it to unit.scope }
    }

    private fun migrate(unit: MigrationUnit, from: String, due: Pair<String, MigrationScope>, log: (String) -> Unit) {
        val what = "migration of ${unit.label} from $from to ${unit.version}"
        val backup = backupOf(unit, from)
        try {
            if (Files.exists(backup)) log("$what: keeping the backup $backup")
            else if (Files.isDirectory(unit.folder)) {
                copy(unit.folder, backup, unit.scope == MigrationScope.PROJECT)
                log("$what: backup $backup")
            }
            Files.createDirectories(unit.folder)
            val processor = unit.create(due.first)
            log("$what: running ${due.first}")
            processor.migrate(context(unit, from, log))
            log("$what: done")
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            val reason = if (e is MigrationException) e.message else "${e::class.java.simpleName}: ${e.message}"
            throw MigrationFailedException("$what failed: $reason (backup: $backup)", e)
        }
    }

    private fun backupOf(unit: MigrationUnit, from: String): Path =
        unit.folder.resolveSibling("${unit.folder.fileName}.backup-$from")

    private fun context(unit: MigrationUnit, from: String, log: (String) -> Unit) = object : MigrationContext {
        override val from: String = from
        override val to: String = unit.version
        override val scope: MigrationScope = unit.scope
        override val blockId: String? = unit.blockId
        override val dataDirectory: Path = unit.folder
        override fun log(message: String) = log("migration of ${unit.label}: $message")
    }

    /** Copies [source] to [target]; for a whole instance the bookkeeping folder and the backups of single blocks are left out. */
    private fun copy(source: Path, target: Path, instance: Boolean) {
        Files.walk(source).use { paths ->
            for (p in paths) {
                val relative = source.relativize(p)
                if (instance && relative.nameCount > 0) {
                    val first = relative.getName(0).toString()
                    if (first == ".cringle" || ".backup-" in first) continue
                }
                val to = target.resolve(relative.toString())
                if (Files.isDirectory(p)) Files.createDirectories(to) else Files.copy(p, to, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }
}
