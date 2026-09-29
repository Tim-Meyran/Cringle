// SPDX-License-Identifier: Apache-2.0

package cringle.testkit

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.BlockId
import cringle.contract.BlockPorts
import cringle.contract.BlockProvider
import cringle.contract.DriverSet
import cringle.contract.PortRef
import cringle.contract.TetherEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Drives a [Block] without an engine. It enforces the lifecycle order (`init`, `start`, `stop`, `destroy`),
 * delivers tether events to the block, and exposes the block's ports as [InMemoryTether]s for assertions. All
 * operations are `suspend` functions, so use them inside `runTest`.
 */
public class BlockTestHarness(
    /** The block under test. */
    public val block: Block,
    /** Identity handed to the block. */
    public val blockId: BlockId = BlockId("block-under-test"),
    /** Configuration handed to the block. */
    public val config: Map<String, Any?> = emptyMap(),
    /** The block's ports. */
    public val ports: TestBlockPorts = TestBlockPorts(),
) {
    /** Lifecycle states of the block under test. */
    public enum class State {
        /** Not initialized yet. */
        CREATED,

        /** `init` has run. */
        INITIALIZED,

        /** `start` has run. */
        STARTED,

        /** `stop` has run. */
        STOPPED,

        /** `destroy` has run. */
        DESTROYED,
    }

    /** Current lifecycle state. */
    public var state: State = State.CREATED
        private set

    private val context = object : BlockContext {
        override val blockId: BlockId get() = this@BlockTestHarness.blockId
        override val config: Map<String, Any?> get() = this@BlockTestHarness.config
        override val ports: BlockPorts get() = this@BlockTestHarness.ports
    }

    private fun transition(operation: String, allowed: Set<State>, next: State) {
        check(state in allowed) { "$operation is not allowed in state $state (allowed: ${allowed.joinToString()})" }
        state = next
    }

    /** Calls `Block.init`. */
    public suspend fun init() {
        transition("init", setOf(State.CREATED), State.INITIALIZED)
        block.init(context)
    }

    /** Calls `Block.start`. */
    public suspend fun start() {
        transition("start", setOf(State.INITIALIZED), State.STARTED)
        block.start()
    }

    /** Calls `Block.stop`. */
    public suspend fun stop() {
        transition("stop", setOf(State.STARTED), State.STOPPED)
        block.stop()
    }

    /** Calls `Block.destroy`. */
    public suspend fun destroy() {
        transition("destroy", setOf(State.INITIALIZED, State.STOPPED), State.DESTROYED)
        block.destroy()
    }

    /** Runs `init` and `start`, then [body], then `stop` and `destroy` (also when [body] fails). */
    public suspend fun runLifecycle(body: suspend BlockTestHarness.() -> Unit) {
        init()
        start()
        try {
            body()
        } finally {
            stop()
            destroy()
        }
    }

    private fun requireStarted(what: String) {
        check(state == State.STARTED) { "$what can only be delivered to a started block, but the state is $state" }
    }

    /** Delivers a message to the block's port [port] (VarArg slot [index]). */
    public suspend fun sendMessage(port: String, value: Any, index: Int? = null) {
        requireStarted("a message")
        block.onTetherEvent(TetherEvent.Message(PortRef(port, index), value))
    }

    /**
     * Delivers a request and returns the block's response. Fails with [AssertionError] if the block does not
     * respond within [timeout] (virtual time inside `runTest`).
     */
    public suspend fun request(port: String, value: Any, index: Int? = null, timeout: Duration = 5.seconds): Any {
        requireStarted("a request")
        val response = CompletableDeferred<Any>()
        block.onTetherEvent(TetherEvent.Request(PortRef(port, index), value) { response.complete(it) })
        return try {
            withTimeout(timeout) { response.await() }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("block did not respond to the request on port '$port' within $timeout", e)
        }
    }

    /**
     * Opens a stream towards the block on [port] and runs [body] with the test's side of it (feed items with
     * `feed`, read `sent`). The block's event handler runs concurrently in a child coroutine; when [body] returns the
     * incoming side is finished and the handler is awaited, so assertions after the call see its complete effect.
     * Inside `runTest`, call `testScheduler.advanceUntilIdle()` in [body] to let the handler process fed items.
     */
    public suspend fun <T> withStream(port: String, index: Int? = null, body: suspend (InMemoryTetherStream) -> T): T {
        requireStarted("a stream")
        val stream = InMemoryTetherStream()
        return coroutineScope {
            val handler = launch { block.onTetherEvent(TetherEvent.StreamOpened(PortRef(port, index), stream)) }
            try {
                body(stream)
            } finally {
                stream.finish()
                handler.join()
            }
        }
    }

    /** Byte stream variant of [withStream]. */
    public suspend fun <T> withByteStream(port: String, index: Int? = null, body: suspend (InMemoryTetherByteStream) -> T): T {
        requireStarted("a byte stream")
        val stream = InMemoryTetherByteStream()
        return coroutineScope {
            val handler = launch { block.onTetherEvent(TetherEvent.ByteStreamOpened(PortRef(port, index), stream)) }
            try {
                body(stream)
            } finally {
                stream.finish()
                handler.join()
            }
        }
    }

    public companion object {
        /**
         * Creates the block [definitionName] through [provider] with [drivers], with ports built from its
         * definition (see [TestBlockPorts.forDefinition]).
         */
        public fun forProvider(
            provider: BlockProvider,
            definitionName: String,
            drivers: DriverSet = TestDriverSet(),
            config: Map<String, Any?> = emptyMap(),
            varArgSizes: Map<String, Int> = emptyMap(),
            blockId: BlockId = BlockId(definitionName),
        ): BlockTestHarness {
            val definition = provider.definitions.firstOrNull { it.name == definitionName }
                ?: throw IllegalArgumentException("Provider has no block definition '$definitionName'")
            return BlockTestHarness(
                provider.createBlock(definitionName, drivers),
                blockId,
                config,
                TestBlockPorts.forDefinition(definition, varArgSizes),
            )
        }
    }
}
