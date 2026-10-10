// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import cringle.contract.Block
import cringle.contract.BlockDefinition
import cringle.contract.BlockProvider
import cringle.contract.BuiltinDriverTypes
import cringle.contract.DriverSet
import cringle.contract.FilesystemDriver
import cringle.contract.LoggingDriver
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import cringle.stdblocks.file.FileDelete
import cringle.stdblocks.file.FileExists
import cringle.stdblocks.file.FileList
import cringle.stdblocks.file.FileRead
import cringle.stdblocks.file.FileWatch
import cringle.stdblocks.file.FileWrite
import cringle.stdblocks.file.TempDir
import cringle.stdblocks.flow.Constant
import cringle.stdblocks.flow.Log
import cringle.stdblocks.text.ToString
import cringle.stdblocks.time.TimerTrigger

/**
 * The blocks of the plugin `cringle-std` (`docs/standard-blocks.md`). Names are `<theme>.<name>` in lower case. Values have fixed types: counters and
 * numbers of ticks are `Int`, texts are `String`; converter blocks join them.
 */
public class StdBlockProvider : BlockProvider {
    override val definitions: List<BlockDefinition> = DEFINITIONS

    override fun createBlock(definitionName: String, drivers: DriverSet): Block = when (definitionName) {
        "time.timer-trigger" -> TimerTrigger()
        "flow.constant" -> Constant()
        "text.to-string" -> ToString()
        "flow.log" -> Log(drivers[LoggingDriver::class])
        "file.temp-dir" -> TempDir(drivers[FilesystemDriver::class])
        "file.write" -> FileWrite(drivers[FilesystemDriver::class])
        "file.read" -> FileRead(drivers[FilesystemDriver::class])
        "file.list" -> FileList(drivers[FilesystemDriver::class])
        "file.delete" -> FileDelete(drivers[FilesystemDriver::class])
        "file.exists" -> FileExists(drivers[FilesystemDriver::class])
        "file.watch" -> FileWatch(drivers[FilesystemDriver::class])
        else -> throw IllegalArgumentException("this provider has no definition named '$definitionName'")
    }

    public companion object {
        /** The name of the plugin. */
        public const val PLUGIN_NAME: String = "cringle-std"

        /**
         * The version of the plugin. A version of a package never changes in a repository: raise it whenever a block changes.
         * The schema of the configurations is `/cringle/stdblocks/schema.json` on the class path.
         */
        public const val VERSION: String = "1.1.0"

        /** The namespace of the configuration schemas. */
        public const val NAMESPACE: String = "cringle.stdblocks"

        private val INT = SchemaRef("cringle.std", "Int")
        private val STRING = SchemaRef("cringle.std", "String")
        private val MESSAGE = setOf(TetherType.MESSAGE)
        private val FILES = listOf(BuiltinDriverTypes.FILESYSTEM_FABRIC.id)

        private fun inPort(name: String, schema: SchemaRef = STRING) = PortDefinition(name, PortDirection.IN, MESSAGE, schema)

        private fun outPort(name: String, schema: SchemaRef = STRING) = PortDefinition(name, PortDirection.OUT, MESSAGE, schema)

        private val DEFINITIONS: List<BlockDefinition> = listOf(
            BlockDefinition(
                "time.timer-trigger", listOf(INT), listOf(PortDefinition("tick", PortDirection.OUT, MESSAGE, INT)), emptyList(),
                SchemaRef(NAMESPACE, "TimerTriggerConfig"),
            ),
            BlockDefinition(
                "flow.constant", listOf(INT, STRING),
                listOf(PortDefinition("trigger", PortDirection.IN, MESSAGE, INT), PortDefinition("out", PortDirection.OUT, MESSAGE, STRING)), emptyList(),
                SchemaRef(NAMESPACE, "ConstantConfig"),
            ),
            BlockDefinition(
                "text.to-string", listOf(INT, STRING),
                listOf(PortDefinition("in", PortDirection.IN, MESSAGE, INT), PortDefinition("out", PortDirection.OUT, MESSAGE, STRING)), emptyList(),
            ),
            BlockDefinition(
                "flow.log", listOf(STRING), listOf(PortDefinition("in", PortDirection.IN, MESSAGE, STRING)), listOf(BuiltinDriverTypes.LOGGING.id),
                SchemaRef(NAMESPACE, "LogConfig"),
            ),
            BlockDefinition("file.temp-dir", listOf(INT, STRING), listOf(inPort("create", INT), outPort("path")), FILES, SchemaRef(NAMESPACE, "TempDirConfig")),
            BlockDefinition("file.write", listOf(STRING), listOf(inPort("path"), inPort("in"), outPort("done")), FILES, SchemaRef(NAMESPACE, "FileWriteConfig")),
            BlockDefinition("file.read", listOf(STRING), listOf(inPort("path"), outPort("text")), FILES, SchemaRef(NAMESPACE, "FileReadConfig")),
            BlockDefinition("file.list", listOf(STRING), listOf(inPort("path"), outPort("name")), FILES),
            BlockDefinition("file.delete", listOf(STRING), listOf(inPort("path"), outPort("deleted")), FILES),
            BlockDefinition("file.exists", listOf(STRING), listOf(inPort("path"), outPort("yes"), outPort("no")), FILES),
            BlockDefinition("file.watch", listOf(STRING), listOf(outPort("created"), outPort("removed")), FILES, SchemaRef(NAMESPACE, "FileWatchConfig")),
        )
    }
}
