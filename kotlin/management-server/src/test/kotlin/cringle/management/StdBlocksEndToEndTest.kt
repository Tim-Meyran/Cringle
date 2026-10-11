// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.contract.TetherType
import cringle.engine.v1.QueryLogsRequest
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.Endpoint
import cringle.packaging.FabricConfig
import cringle.packaging.PackageReader
import cringle.packaging.TetherDef
import cringle.testkit.TestProjectBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The standard blocks in a real engine (#349, #351): the package that the management server publishes is loaded from the repository and its blocks run
 * in a fabric.
 */
@Tag("integration")
class StdBlocksEndToEndTest : ServiceTestBase() {
    private fun config(vararg pairs: Pair<String, Any>) =
        JsonObject(pairs.associate { (k, v) -> k to if (v is String) JsonPrimitive(v) else if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v as Number) })

    private fun block(id: String, name: String, config: JsonObject = JsonObject(emptyMap())) = BlueprintBlock(id, "cringle-std/$name", config)

    private fun tether(from: String, port: String, to: String, input: String) = TetherDef(TetherType.MESSAGE, Endpoint(from, port), Endpoint(to, input))

    /** Publishes the standard blocks and a project `name` that holds [blueprint], starts an engine and deploys it. */
    private fun deployStd(name: String, blueprint: Blueprint) {
        val installer = StdBlocksInstaller(core)
        runBlocking { installer.install() }
        val file = dir.resolve("std-package.cringle")
        Files.write(file, installer.packageBytes())
        val project = TestProjectBuilder(name, "1.0.0")
            .dependency("cringle-std", "^1.0.0")
            .blueprint(blueprint)
            .fabric(FabricConfig(blueprint.name, 1, listOf("std"), emptyMap()))
            .build(Files.createDirectories(dir.resolve("std-build")), listOf(PackageReader.readPlugin(file)))
        runBlocking { publishProject(project.file) }
        runBlocking { engine("e-std", "std") }
        runBlocking { core.deploy(name, "1.0.0", true, false, true) }
    }

    private fun awaitLog(vararg expected: String) {
        val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos()
        while (true) {
            val messages = runBlocking { core.queryLogs(null, null, QueryLogsRequest.getDefaultInstance()) }.entries.map { it.entry.message }
            if (expected.all { e -> messages.any { it.contains(e) } }) return
            check(System.nanoTime() < deadline) { "timeout, wanted ${expected.toList()}, log: $messages / ${diagnostics()}" }
            Thread.sleep(300)
        }
    }

    @Test
    fun aTimerTicksThroughTheConverterIntoTheLog() {
        deployStd(
            "std-app",
            Blueprint(
                "app",
                listOf(
                    block("t", "time.timer-trigger", config("intervalMs" to 30, "count" to 3)),
                    block("s", "text.to-string"),
                    block("l", "flow.log", config("prefix" to "std-tick: ")),
                ),
                listOf(tether("t", "tick", "s", "in"), tether("s", "out", "l", "in")),
            ),
        )
        awaitLog("std-tick: 1", "std-tick: 2", "std-tick: 3")
    }

    @Test
    fun aFileTravelsFromTempDirThroughWriteAndReadToTheLog() {
        // a timer asks for a folder, the path goes to the writer; a second timer, later, triggers the text; the file is read back into the log
        deployStd(
            "std-files",
            Blueprint(
                "app",
                listOf(
                    block("t1", "time.timer-trigger", config("intervalMs" to 10, "count" to 1)),
                    block("t2", "time.timer-trigger", config("intervalMs" to 10, "initialDelayMs" to 1500, "count" to 1)),
                    block("d", "file.temp-dir", config("prefix" to "job")),
                    block("c", "flow.constant", config("value" to "hello file")),
                    block("w", "file.write", config("name" to "note.txt")),
                    block("r", "file.read"),
                    block("l", "flow.log", config("prefix" to "file: ")),
                ),
                listOf(
                    tether("t1", "tick", "d", "create"),
                    tether("d", "path", "w", "path"),
                    tether("t2", "tick", "c", "trigger"),
                    tether("c", "out", "w", "in"),
                    tether("w", "done", "r", "path"),
                    tether("r", "text", "l", "in"),
                ),
            ),
        )
        awaitLog("file: hello file")
    }

    @Test
    fun aCalculationRunsThroughTheStandardBlocks() {
        // 2.5 + 4.0 is compared with 6.5; the sum and the verdict go to the log as text
        deployStd(
            "std-calc",
            Blueprint(
                "app",
                listOf(
                    block("t", "time.timer-trigger", config("intervalMs" to 10, "count" to 1)),
                    block("c1", "math.constant", config("value" to 2.5)),
                    block("c2", "math.constant", config("value" to 4.0)),
                    block("c3", "math.constant", config("value" to 6.5)),
                    block("add", "math.add"),
                    block("cmp", "logic.compare"),
                    block("verdict", "text.boolean-to-string"),
                    block("sum", "text.double-to-string"),
                    block("l1", "flow.log", config("prefix" to "calc: ")),
                    block("l2", "flow.log", config("prefix" to "sum: ")),
                ),
                listOf(
                    // an output port can start several tethers: the tick goes to three blocks, the sum to two
                    tether("t", "tick", "c1", "trigger"),
                    tether("t", "tick", "c2", "trigger"),
                    tether("t", "tick", "c3", "trigger"),
                    tether("c1", "out", "add", "a"),
                    tether("c2", "out", "add", "b"),
                    tether("add", "out", "cmp", "a"),
                    tether("add", "out", "sum", "in"),
                    tether("c3", "out", "cmp", "b"),
                    tether("cmp", "out", "verdict", "in"),
                    tether("verdict", "out", "l1", "in"),
                    tether("sum", "out", "l2", "in"),
                ),
            ),
        )
        awaitLog("calc: true", "sum: 6.5")
    }

    private suspend fun publishProject(file: Path) {
        val data = Files.readAllBytes(file)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        core.repository().publishPackage(
            kotlinx.coroutines.flow.flowOf(
                cringle.repository.v1.PublishRequest.newBuilder().setHeader(cringle.repository.v1.PublishHeader.newBuilder().setExpectedSha256(hash)).build(),
                cringle.repository.v1.PublishRequest.newBuilder().setChunk(com.google.protobuf.ByteString.copyFrom(data)).build(),
            ),
        )
    }
}
