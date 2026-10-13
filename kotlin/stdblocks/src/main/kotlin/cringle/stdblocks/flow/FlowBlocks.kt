// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.flow

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.LogLevel
import cringle.contract.LoggingDriver
import cringle.contract.Tether
import cringle.contract.TetherEvent
import cringle.stdblocks.ANY
import cringle.stdblocks.BOOLEAN
import cringle.stdblocks.DOUBLE
import cringle.stdblocks.STRING
import cringle.stdblocks.Entry
import cringle.stdblocks.INT
import cringle.stdblocks.entry
import cringle.stdblocks.handlerEntry
import cringle.stdblocks.inPort
import cringle.stdblocks.long
import cringle.stdblocks.outPort
import cringle.stdblocks.requireLong
import cringle.stdblocks.toJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sends every value at `in` on `out` after `delayMs`, in the order it came; values that still wait when the block stops are dropped. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class Delay(dispatcher: CoroutineDispatcher = Dispatchers.Default) : Block {
    // one thread at a time, so timers with the same delay fire in the order they were started
    private val dispatcher: CoroutineDispatcher = if (dispatcher === Dispatchers.Default) Dispatchers.Default.limitedParallelism(1) else dispatcher
    private var delayMs = 0L
    private lateinit var out: Tether
    private var scope: CoroutineScope? = null

    override suspend fun init(context: BlockContext) {
        delayMs = context.config.requireLong("delayMs", 0)
        out = context.ports.port("out")
    }

    override suspend fun start() {
        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event !is TetherEvent.Message || event.port.name != "in") return
        val value = event.value
        scope?.launch {
            delay(delayMs)
            out.send(value)
        }
    }

    override suspend fun stop() {
        scope?.coroutineContext?.get(Job)?.cancel()
        scope = null
    }
}

/** Sends the last value at `in` on `out` once `quietMs` have passed without a new one. */
internal class Debounce(private val dispatcher: CoroutineDispatcher = Dispatchers.Default) : Block {
    private var quietMs = 0L
    private lateinit var out: Tether
    private var scope: CoroutineScope? = null
    private var pending: Job? = null

    override suspend fun init(context: BlockContext) {
        quietMs = context.config.requireLong("quietMs", 1)
        out = context.ports.port("out")
    }

    override suspend fun start() {
        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event !is TetherEvent.Message || event.port.name != "in") return
        val value = event.value
        pending?.cancel()
        pending = scope?.launch {
            delay(quietMs)
            out.send(value)
        }
    }

    override suspend fun stop() {
        scope?.coroutineContext?.get(Job)?.cancel()
        scope = null
        pending = null
    }
}

/** `flow.trigger` for values of [type]: the slot ports `in`, `request` and `out` have that type, `trigger` takes any signal, `done` is the next trigger signal. */
private fun trigger(name: String, type: cringle.contract.SchemaRef, neutral: Any? = null) = entry(
    name,
    listOf(
        inPort("trigger", ANY),
        cringle.contract.PortDefinition("in", cringle.contract.PortDirection.IN, setOf(cringle.contract.TetherType.MESSAGE), type, varArg = true),
        cringle.contract.PortDefinition("out", cringle.contract.PortDirection.OUT, setOf(cringle.contract.TetherType.MESSAGE), type, varArg = true),
        cringle.contract.PortDefinition("request", cringle.contract.PortDirection.OUT, setOf(cringle.contract.TetherType.REQUEST_RESPONSE), type, varArg = true),
        outPort("done", INT),
    ),
) { Trigger(if (neutral == null) { value -> value } else { _ -> neutral }) }

/** The value the trigger sends when it pulls a value of type [type]: a neutral one for the basic types, otherwise the signal itself passes. */
private fun neutralFor(type: cringle.contract.SchemaRef?): (Any) -> Any = when (type) {
    STRING -> { _ -> "" }
    DOUBLE -> { _ -> 0.0 }
    INT -> { _ -> 0L }
    BOOLEAN -> { _ -> false }
    else -> { value -> value }
}

/** `flow.trigger` with the type parameter `T` (bound `Any`): the ports `in`, `request` and `out` carry `T`, `trigger` takes any signal, `done` is the next trigger signal. */
private val GENERIC_TRIGGER = run {
    val message = setOf(cringle.contract.TetherType.MESSAGE)
    val ports = listOf(
        inPort("trigger", ANY),
        cringle.contract.PortDefinition("in", cringle.contract.PortDirection.IN, message, ANY, varArg = true, typeParam = "T"),
        cringle.contract.PortDefinition("out", cringle.contract.PortDirection.OUT, message, ANY, varArg = true, typeParam = "T"),
        cringle.contract.PortDefinition("request", cringle.contract.PortDirection.OUT, setOf(cringle.contract.TetherType.REQUEST_RESPONSE), ANY, varArg = true, typeParam = "T"),
        outPort("done", INT),
    )
    Entry(
        cringle.contract.BlockDefinition("flow.trigger", listOf(ANY, INT), ports, emptyList(), null, emptyList(), listOf(cringle.contract.TypeParameter("T"))),
        { Trigger(neutralFor(null)) },
        { _, types -> Trigger(neutralFor(types["T"])) },
    )
}

internal val FLOW_ENTRIES: List<Entry> = listOf(
    GENERIC_TRIGGER,
    trigger("flow.trigger-string", STRING, ""),
    trigger("flow.trigger-number", DOUBLE, 0.0),
    trigger("flow.trigger-int", INT, 0L),
    trigger("flow.trigger-boolean", BOOLEAN, false),
    entry("flow.delay", listOf(inPort("in", ANY), outPort("out", ANY)), "DelayConfig") { Delay() },
    entry("flow.debounce", listOf(inPort("in", ANY), outPort("out", ANY)), "DebounceConfig") { Debounce() },
    handlerEntry("flow.buffer", listOf(inPort("in", ANY), inPort("flush", INT), outPort("batch", ANY)), "BufferConfig") { config ->
        val size = config.long("size")?.also { require(it >= 1) { "the configuration 'size' must be at least 1" } }
        val values = ArrayList<Any>()
        suspend fun release(emit: cringle.stdblocks.Emit) {
            if (values.isNotEmpty()) {
                emit.send("batch", ArrayList(values))
                values.clear()
            }
        }
        return@handlerEntry { port, v, emit ->
            if (port == "flush") {
                release(emit)
            } else {
                values += v
                if (size != null && values.size >= size) release(emit)
            }
        }
    },
    handlerEntry("flow.merge", listOf(inPort("a", ANY), inPort("b", ANY), outPort("out", ANY))) { _ -> { _, v, emit -> emit.send("out", v) } },
    handlerEntry("flow.throttle", listOf(inPort("in", ANY), outPort("out", ANY), outPort("dropped", ANY)), "ThrottleConfig") { config ->
        val interval = config.requireLong("intervalMs", 1)
        var last: Long? = null
        return@handlerEntry { _, v, emit ->
            val now = System.nanoTime() / 1_000_000
            val previous = last
            if (previous == null || now - previous >= interval) {
                last = now
                emit.send("out", v)
            } else {
                emit.send("dropped", v)
            }
        }
    },
    handlerEntry("flow.once", listOf(inPort("in", ANY), inPort("reset", INT), outPort("out", ANY))) { _ ->
        var passed = false
        return@handlerEntry { port, v, emit ->
            if (port == "reset") {
                passed = false
            } else if (!passed) {
                passed = true
                emit.send("out", v)
            }
        }
    },
    entry("flow.log-any", listOf(inPort("in", ANY)), "LogConfig", listOf(cringle.contract.BuiltinDriverTypes.LOGGING.id)) { LogAny(it[LoggingDriver::class]) },
)

/** Like `flow.log`, for a value of any type: it is written as JSON text. */
internal class LogAny(private val logging: LoggingDriver) : Block {
    private var level = LogLevel.INFO
    private var prefix = ""

    override suspend fun init(context: BlockContext) {
        (context.config["level"] as? String)?.let { name ->
            level = LogLevel.entries.firstOrNull { it.name == name } ?: throw IllegalArgumentException("unknown log level '$name'")
        }
        prefix = context.config["prefix"] as? String ?: ""
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event is TetherEvent.Message && event.port.name == "in") logging.log(level, prefix + toJson(event.value))
    }
}
