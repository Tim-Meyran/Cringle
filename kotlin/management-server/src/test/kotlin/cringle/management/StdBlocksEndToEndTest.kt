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

    @Test
    fun aGenericPipelineRunsThroughAnyBlocks() {
        // an Int from the timer and a String from the constant both go into a port of the type Any, are delayed and written as JSON
        deployStd(
            "std-any",
            Blueprint(
                "app",
                listOf(
                    block("t", "time.timer-trigger", config("intervalMs" to 10, "count" to 1)),
                    block("c", "flow.constant", config("value" to "hello")),
                    block("m", "flow.merge"),
                    block("d", "flow.delay", config("delayMs" to 50)),
                    block("l", "flow.log-any", config("prefix" to "any: ")),
                ),
                listOf(
                    tether("t", "tick", "m", "a"),
                    tether("t", "tick", "c", "trigger"),
                    tether("c", "out", "m", "b"),
                    tether("m", "out", "d", "in"),
                    tether("d", "out", "l", "in"),
                ),
            ),
        )
        awaitLog("any: 1", "any: \"hello\"")
    }

    @Test
    fun aJsonDocumentIsParsedAndAFieldIsLogged() {
        // a text with a JSON document is parsed, the name is taken from it and written as a JSON text; the Base64 of the name is logged as well
        deployStd(
            "std-json",
            Blueprint(
                "app",
                listOf(
                    block("t", "time.timer-trigger", config("intervalMs" to 10, "count" to 1)),
                    block("c", "flow.constant", config("value" to "{\"user\": {\"name\": \"Ada\"}}")),
                    block("p", "data.json-parse"),
                    block("g", "data.json-get", config("path" to "user.name")),
                    block("s", "data.as-string"),
                    block("e", "data.base64-encode"),
                    block("l", "flow.log", config("prefix" to "b64: ")),
                ),
                listOf(
                    tether("t", "tick", "c", "trigger"),
                    tether("c", "out", "p", "in"),
                    tether("p", "out", "g", "in"),
                    tether("g", "found", "s", "in"),
                    tether("s", "out", "e", "in"),
                    tether("e", "out", "l", "in"),
                ),
            ),
        )
        awaitLog("b64: QWRh")
    }

    @Test
    fun aTriggerReleasesHeldValuesIntoTheLog() {
        // two constants fill the inputs of the trigger block at the start; a second timer, later, triggers it: the values leave on the matching outputs and
        // the new trigger signal `done` starts the next step of the flow
        fun indexed(from: String, port: String, fromIndex: Int?, to: String, input: String, toIndex: Int?) =
            TetherDef(TetherType.MESSAGE, Endpoint(from, port, fromIndex), Endpoint(to, input, toIndex))
        deployStd(
            "std-trigger",
            Blueprint(
                "app",
                listOf(
                    block("t1", "time.timer-trigger", config("intervalMs" to 10, "count" to 1)),
                    block("t2", "time.timer-trigger", config("intervalMs" to 10, "initialDelayMs" to 1500, "count" to 1)),
                    block("c1", "flow.constant", config("value" to "x")),
                    block("c2", "flow.constant", config("value" to "y")),
                    BlueprintBlock("trig", "cringle-std/flow.trigger", varArgCounts = mapOf("in" to 2, "request" to 2, "out" to 2)),
                    block("next", "flow.constant", config("value" to "after")),
                    block("l1", "flow.log-any", config("prefix" to "a: ")),
                    block("l2", "flow.log-any", config("prefix" to "b: ")),
                    block("l3", "flow.log-any", config("prefix" to "next: ")),
                ),
                listOf(
                    tether("t1", "tick", "c1", "trigger"),
                    tether("t1", "tick", "c2", "trigger"),
                    indexed("c1", "out", null, "trig", "in", 0),
                    indexed("c2", "out", null, "trig", "in", 1),
                    tether("t2", "tick", "trig", "trigger"),
                    indexed("trig", "out", 0, "l1", "in", null),
                    indexed("trig", "out", 1, "l2", "in", null),
                    tether("trig", "done", "next", "trigger"),
                    tether("next", "out", "l3", "in"),
                ),
            ),
        )
        awaitLog("a: \"x\"", "b: \"y\"", "next: \"after\"")
    }

    @Test
    fun aTriggerPullsTheValuesOfInputBlocks() {
        // the trigger asks a text input and a number input for their values; the answers leave on the matching outputs, `done` starts the next step
        fun indexed(from: String, port: String, fromIndex: Int?, to: String, input: String, toIndex: Int?) =
            TetherDef(if (port == "request" || input == "get") TetherType.REQUEST_RESPONSE else TetherType.MESSAGE, Endpoint(from, port, fromIndex), Endpoint(to, input, toIndex))
        deployStd(
            "std-pull",
            Blueprint(
                "app",
                listOf(
                    block("t", "time.timer-trigger", config("intervalMs" to 10, "count" to 1)),
                    block("name", "input.text", config("value" to "Ada")),
                    block("age", "input.number", config("value" to 36)),
                    BlueprintBlock("trig", "cringle-std/flow.trigger", varArgCounts = mapOf("in" to 2, "request" to 2, "out" to 2)),
                    block("l1", "flow.log-any", config("prefix" to "name: ")),
                    block("l2", "flow.log-any", config("prefix" to "age: ")),
                    block("next", "flow.constant", config("value" to "go on")),
                    block("l3", "flow.log-any", config("prefix" to "next: ")),
                ),
                listOf(
                    indexed("trig", "request", 0, "name", "get", null),
                    indexed("trig", "request", 1, "age", "get", null),
                    tether("t", "tick", "trig", "trigger"),
                    indexed("trig", "out", 0, "l1", "in", null),
                    indexed("trig", "out", 1, "l2", "in", null),
                    tether("trig", "done", "next", "trigger"),
                    tether("next", "out", "l3", "in"),
                ),
            ),
        )
        awaitLog("name: \"Ada\"", "age: 36", "next: \"go on\"")
    }

    @Test
    fun aValueIsWrittenToTheDwhAndReadBack() {
        // the real data warehouse driver of the engine: a text is written with a key and tags, a second timer, later, reads the entries of the same block into the log
        deployStd(
            "std-dwh",
            Blueprint(
                "app",
                listOf(
                    block("t1", "time.timer-trigger", config("intervalMs" to 10, "count" to 1)),
                    block("t2", "time.timer-trigger", config("intervalMs" to 10, "initialDelayMs" to 1500, "count" to 1)),
                    block("c", "flow.constant", config("value" to "stored")),
                    block("w", "dwh.store", JsonObject(mapOf("key" to JsonPrimitive("greeting"), "tags" to JsonObject(mapOf("room" to JsonPrimitive("a1")))))),
                    block("l1", "log.write", config("level" to "WARN", "prefix" to "wrote: ")),
                    block("l2", "log.write", config("prefix" to "dwh: ")),
                ),
                listOf(
                    tether("t1", "tick", "c", "trigger"),
                    tether("c", "out", "w", "in"),
                    tether("w", "out", "l1", "in"),
                    tether("t2", "tick", "w", "read"),
                    tether("w", "entries", "l2", "in"),
                ),
            ),
        )
        awaitLog("wrote: stored", "dwh: [{\"key\":\"greeting\",\"value\":\"stored\",\"timestamp\":\"", "\"tags\":{\"room\":\"a1\"}}]")
    }

    @Test
    fun aTypedTriggerFeedsTypedBlocks() {
        // flow.trigger-string pulls a text input; its typed output goes straight into text.upper (a String port) and on into the log: with the generic trigger this
        // connection is refused ("Any is not assignable to String")
        fun pull(from: String, to: String) = TetherDef(TetherType.REQUEST_RESPONSE, Endpoint(from, "request", 0), Endpoint(to, "get"))
        deployStd(
            "std-typed",
            Blueprint(
                "app",
                listOf(
                    block("t", "time.timer-trigger", config("intervalMs" to 10, "count" to 1)),
                    block("name", "input.text", config("value" to "ada")),
                    BlueprintBlock("trig", "cringle-std/flow.trigger-string", varArgCounts = mapOf("in" to 1, "request" to 1, "out" to 1)),
                    block("up", "text.upper"),
                    block("l", "flow.log", config("prefix" to "typed: ")),
                ),
                listOf(
                    pull("trig", "name"),
                    tether("t", "tick", "trig", "trigger"),
                    TetherDef(TetherType.MESSAGE, Endpoint("trig", "out", 0), Endpoint("up", "in")),
                    tether("up", "out", "l", "in"),
                ),
            ),
        )
        awaitLog("typed: ADA")
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
