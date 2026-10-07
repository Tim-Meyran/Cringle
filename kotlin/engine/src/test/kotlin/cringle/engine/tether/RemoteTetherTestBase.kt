// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.BlockDefinition
import cringle.contract.BlockProvider
import cringle.contract.DriverSet
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.Tether
import cringle.contract.TetherEvent
import cringle.contract.TetherType
import cringle.engine.Engine
import cringle.engine.EngineArgs
import cringle.engine.fabric.BlockResolver
import cringle.engine.fabric.DriverFactory
import cringle.engine.fabric.FabricException
import cringle.engine.fabric.FabricLogger
import cringle.engine.fabric.FabricPaths
import cringle.engine.fabric.FabricRuntime
import cringle.engine.fabric.FabricSpec
import cringle.engine.fabric.PluginTrust
import cringle.engine.fabric.ResolvedBlock
import cringle.engine.fabric.WatchdogConfig
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.DeliveryPolicy
import cringle.packaging.Endpoint
import cringle.packaging.RemoteEndpoint
import cringle.packaging.TetherDef
import cringle.schema.SchemaRegistry
import cringle.testkit.TestDriverSet
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * Two engines with their own generated identity and a fabric with one block on each (#146, #147): what the tests of
 * tethers between engines share. The fabrics name each other in their blueprints, as the blueprints of a real
 * deployment do. Everything runs in this process over loopback.
 */
abstract class RemoteTetherTestBase {
    @TempDir
    lateinit var dir: Path

    protected val string = SchemaRef("cringle.std", "String")
    protected val int = SchemaRef("cringle.std", "Int")
    private val engines = CopyOnWriteArrayList<Engine>()
    private val fabrics = CopyOnWriteArrayList<FabricRuntime>()

    @AfterEach
    fun stopAll() {
        fabrics.forEach { runCatching { it.close() } }
        engines.forEach { runCatching { it.stop() } }
    }

    protected fun engine(
        id: String,
        home: Path = dir.resolve("home-$id"),
        env: Map<String, String> = emptyMap(),
        heartbeat: java.time.Duration = java.time.Duration.ofSeconds(5),
        options: RemoteTetherOptions = RemoteTetherOptions(),
    ): Engine {
        Files.createDirectories(home)
        return Engine.create(EngineArgs(id, null, home, 0), mapOf("CRINGLE_HOME" to home.toString()) + env, heartbeat, options).start().also { engines += it }
    }

    protected fun senderDef(schema: SchemaRef = string, types: Set<TetherType> = setOf(TetherType.MESSAGE)) =
        BlockDefinition("src", emptyList(), listOf(PortDefinition("out", PortDirection.OUT, types, schema)), emptyList())

    protected fun receiverDef(schema: SchemaRef = string, types: Set<TetherType> = setOf(TetherType.MESSAGE)) =
        BlockDefinition("dst", emptyList(), listOf(PortDefinition("in", PortDirection.IN, types, schema)), emptyList())

    protected class TestBlock(private val handler: suspend (TetherEvent) -> Unit) : Block {
        lateinit var context: BlockContext

        override suspend fun init(context: BlockContext) {
            this.context = context
        }

        override suspend fun onTetherEvent(event: TetherEvent) = handler(event)
    }

    /** A fabric with one block on [engine]. */
    protected inner class Node(
        val engine: Engine,
        val fabricId: String,
        definition: BlockDefinition,
        tether: TetherDef,
        capacity: Int = 64,
        requestTimeout: java.time.Duration = java.time.Duration.ofSeconds(30),
        handler: suspend (TetherEvent) -> Unit = {},
    ) {
        val blockId: String = if (definition.name == "src") "s" else "d"
        val block = TestBlock(handler)
        val log = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<Throwable>()
        private val provider = object : BlockProvider {
            override val definitions = listOf(definition)
            override fun createBlock(definitionName: String, drivers: DriverSet): Block = block
        }
        val fabric = FabricRuntime(
            FabricSpec(
                id = fabricId,
                blueprint = Blueprint(fabricId, listOf(BlueprintBlock(blockId, "p/${definition.name}")), listOf(tether)),
                resolver = BlockResolver { ref -> ResolvedBlock(provider, definition, PluginTrust.TRUSTED).takeIf { ref == "p/${definition.name}" } },
                drivers = DriverFactory { _, _ -> TestDriverSet() },
                paths = FabricPaths(dir.resolve("fabric-${engine.config.id}"), fabricId),
                tethers = TetherConfig(SchemaRegistry(), capacity, requestTimeout, remote = engine.remoteTethers.portsFor(fabricId)),
                schemas = SchemaRegistry(),
                logger = FabricLogger { _, message -> log += message },
                watchdog = WatchdogConfig(enabled = false),
            ),
        ).also { fabrics += it }

        fun out(): Tether = block.context.ports.port("out")

        fun stop() = runBlocking { fabric.stop() }

        fun start() = runBlocking { fabric.start() }
    }

    /** The tether of the sending fabric `fa` on [a]: its local `from` is `s.out`, the receiving end is `d.in` of `fb` on [b]. */
    protected fun senderTether(b: Engine, fabric: String = "fb", block: String = "d", port: String = "in", fingerprint: String = b.identity.publicKeyFingerprint, type: TetherType = TetherType.MESSAGE) =
        TetherDef(type, Endpoint("s", "out"), null, remote = RemoteEndpoint("127.0.0.1:${b.tetherPort}", fingerprint, fabric, block, port))

    /** The tether of the receiving fabric `fb` on [b]: its local `to` is `d.in`, the sender is `s.out` of `fa` on [a]. */
    protected fun receiverTether(a: Engine, delivery: DeliveryPolicy = DeliveryPolicy.DROP, fingerprint: String = a.identity.publicKeyFingerprint, type: TetherType = TetherType.MESSAGE) =
        TetherDef(
            type, null, Endpoint("d", "in"), delivery = delivery,
            remote = RemoteEndpoint("127.0.0.1:${a.tetherPort}", fingerprint, "fa", "s", "out"),
        )
}
