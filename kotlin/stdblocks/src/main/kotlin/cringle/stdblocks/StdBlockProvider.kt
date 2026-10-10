// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import cringle.contract.Block
import cringle.contract.BlockDefinition
import cringle.contract.BlockProvider
import cringle.contract.BuiltinDriverTypes
import cringle.contract.DriverSet
import cringle.contract.FilesystemDriver
import cringle.contract.LoggingDriver
import cringle.stdblocks.file.FileDelete
import cringle.stdblocks.file.FileExists
import cringle.stdblocks.file.FileList
import cringle.stdblocks.file.FileRead
import cringle.stdblocks.file.FileWatch
import cringle.stdblocks.file.FileWrite
import cringle.stdblocks.file.TempDir
import cringle.stdblocks.flow.Constant
import cringle.stdblocks.flow.FLOW_ENTRIES
import cringle.stdblocks.flow.Log
import cringle.stdblocks.logic.LOGIC_ENTRIES
import cringle.stdblocks.math.MATH_ENTRIES
import cringle.stdblocks.text.TEXT_ENTRIES
import cringle.stdblocks.time.TimerTrigger

private val FILES = listOf(BuiltinDriverTypes.FILESYSTEM_FABRIC.id)

/** The blocks of the themes `time`, `flow` and `file`; those of `text`, `math` and `logic` are in their own files. */
private val CORE_ENTRIES: List<Entry> = listOf(
    entry("time.timer-trigger", listOf(outPort("tick", INT)), "TimerTriggerConfig") { TimerTrigger() },
    entry("flow.constant", listOf(inPort("trigger", INT), outPort("out")), "ConstantConfig") { Constant() },
    entry("flow.log", listOf(inPort("in")), "LogConfig", listOf(BuiltinDriverTypes.LOGGING.id)) { Log(it[LoggingDriver::class]) },
    entry("file.temp-dir", listOf(inPort("create", INT), outPort("path")), "TempDirConfig", FILES) { TempDir(it[FilesystemDriver::class]) },
    entry("file.write", listOf(inPort("path"), inPort("in"), outPort("done")), "FileWriteConfig", FILES) { FileWrite(it[FilesystemDriver::class]) },
    entry("file.read", listOf(inPort("path"), outPort("text")), "FileReadConfig", FILES) { FileRead(it[FilesystemDriver::class]) },
    entry("file.list", listOf(inPort("path"), outPort("name")), null, FILES) { FileList(it[FilesystemDriver::class]) },
    entry("file.delete", listOf(inPort("path"), outPort("deleted")), null, FILES) { FileDelete(it[FilesystemDriver::class]) },
    entry("file.exists", listOf(inPort("path"), outPort("yes"), outPort("no")), null, FILES) { FileExists(it[FilesystemDriver::class]) },
    entry("file.watch", listOf(outPort("created"), outPort("removed")), "FileWatchConfig", FILES) { FileWatch(it[FilesystemDriver::class]) },
)

private val ENTRIES: List<Entry> = CORE_ENTRIES + FLOW_ENTRIES + TEXT_ENTRIES + MATH_ENTRIES + LOGIC_ENTRIES

/**
 * The blocks of the plugin `cringle-std` (`docs/standard-blocks.md`). Names are `<theme>.<name>` in lower case. Values have fixed types: counters and
 * numbers of ticks are `Int`, texts are `String`, numbers are `Double`, conditions are `Boolean`; converter blocks join them.
 */
public class StdBlockProvider : BlockProvider {
    override val definitions: List<BlockDefinition> = ENTRIES.map { it.definition }

    override fun createBlock(definitionName: String, drivers: DriverSet): Block =
        (ENTRIES.firstOrNull { it.definition.name == definitionName } ?: throw IllegalArgumentException("this provider has no definition named '$definitionName'")).create(drivers)

    public companion object {
        /** The name of the plugin. */
        public const val PLUGIN_NAME: String = "cringle-std"

        /**
         * The version of the plugin. A version of a package never changes in a repository: raise it whenever a block changes.
         * The schema of the configurations is `/cringle/stdblocks/schema.json` on the class path.
         */
        public const val VERSION: String = "1.2.0"

        /** The namespace of the configuration schemas. */
        public const val NAMESPACE: String = "cringle.stdblocks"
    }
}
