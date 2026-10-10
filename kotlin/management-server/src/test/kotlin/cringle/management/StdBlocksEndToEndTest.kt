// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.engine.v1.QueryLogsRequest
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.Endpoint
import cringle.packaging.FabricConfig
import cringle.packaging.PackageReader
import cringle.packaging.TetherDef
import cringle.contract.TetherType
import cringle.testkit.TestProjectBuilder
import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The standard blocks in a real engine (#349): the package that the management server publishes is loaded from the repository, a timer ticks three times,
 * the counter becomes text and the text lands in the log of the fabric.
 */
@Tag("integration")
class StdBlocksEndToEndTest : ServiceTestBase() {
    @Test
    fun aTimerTicksThroughTheConverterIntoTheLog() {
        val installer = StdBlocksInstaller(core)
        assertTrue(runBlocking { installer.install() })
        val file = dir.resolve("std-package.cringle")
        Files.write(file, installer.packageBytes())
        val plugin = PackageReader.readPlugin(file)

        fun config(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) -> k to if (v is String) JsonPrimitive(v) else JsonPrimitive(v as Number) })
        val project = TestProjectBuilder("std-app", "1.0.0")
            .dependency("cringle-std", "^1.0.0")
            .blueprint(
                Blueprint(
                    "app",
                    listOf(
                        BlueprintBlock("t", "cringle-std/time.timer-trigger", config("intervalMs" to 30, "count" to 3)),
                        BlueprintBlock("s", "cringle-std/text.to-string"),
                        BlueprintBlock("l", "cringle-std/flow.log", config("prefix" to "std-tick: ")),
                    ),
                    listOf(
                        TetherDef(TetherType.MESSAGE, Endpoint("t", "tick"), Endpoint("s", "in")),
                        TetherDef(TetherType.MESSAGE, Endpoint("s", "out"), Endpoint("l", "in")),
                    ),
                ),
            )
            .fabric(FabricConfig("app", 1, listOf("std"), emptyMap()))
            .build(Files.createDirectories(dir.resolve("std-build")), listOf(plugin))
        runBlocking { core.repository().let { publishProject(it, project.file) } }
        runBlocking { engine("e-std", "std") }
        runBlocking { core.deploy("std-app", "1.0.0", true, false, true) }

        val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos()
        while (true) {
            val messages = runBlocking { core.queryLogs(null, null, QueryLogsRequest.getDefaultInstance()) }.entries.map { it.entry.message }
            if (messages.any { it.contains("std-tick: 3") }) {
                assertTrue(messages.any { it.contains("std-tick: 1") } && messages.any { it.contains("std-tick: 2") }, messages.toString())
                break
            }
            check(System.nanoTime() < deadline) { "timeout, log: $messages / ${diagnostics()}" }
            Thread.sleep(300)
        }
    }

    private suspend fun publishProject(repository: cringle.repository.v1.RepositoryServiceGrpcKt.RepositoryServiceCoroutineStub, file: java.nio.file.Path) {
        val data = Files.readAllBytes(file)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        repository.publishPackage(
            kotlinx.coroutines.flow.flowOf(
                cringle.repository.v1.PublishRequest.newBuilder().setHeader(cringle.repository.v1.PublishHeader.newBuilder().setExpectedSha256(hash)).build(),
                cringle.repository.v1.PublishRequest.newBuilder().setChunk(com.google.protobuf.ByteString.copyFrom(data)).build(),
            ),
        )
    }
}
