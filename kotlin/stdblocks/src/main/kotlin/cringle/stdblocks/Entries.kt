// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.BlockDefinition
import cringle.contract.DriverSet
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.Tether
import cringle.contract.TetherEvent
import cringle.contract.TetherType

internal val INT = SchemaRef("cringle.std", "Int")
internal val DOUBLE = SchemaRef("cringle.std", "Double")
internal val STRING = SchemaRef("cringle.std", "String")
internal val BOOLEAN = SchemaRef("cringle.std", "Boolean")
internal val ANY = SchemaRef("cringle.std", "Any")

private val MESSAGE = setOf(TetherType.MESSAGE)

internal fun inPort(name: String, schema: SchemaRef = STRING) = PortDefinition(name, PortDirection.IN, MESSAGE, schema)

internal fun outPort(name: String, schema: SchemaRef = STRING) = PortDefinition(name, PortDirection.OUT, MESSAGE, schema)

/** A block of the library: its definition and how to make it. */
internal class Entry(val definition: BlockDefinition, val create: (DriverSet) -> Block, val createTyped: ((DriverSet, Map<String, SchemaRef>) -> Block)? = null)

/** [configuration] is the name of the type in the namespace `cringle.stdblocks`, or `null` for a block without configuration. */
internal fun entry(
    name: String,
    ports: List<PortDefinition>,
    configuration: String? = null,
    drivers: List<String> = emptyList(),
    exclusiveResources: List<cringle.contract.ExclusiveResource> = emptyList(),
    create: (DriverSet) -> Block,
) = Entry(
    BlockDefinition(name, ports.map { it.schema }.distinct(), ports, drivers, configuration?.let { SchemaRef(StdBlockProvider.NAMESPACE, it) }, exclusiveResources),
    create,
)

/** What a handler sends: the value goes out on the port of that name. */
internal fun interface Emit {
    suspend fun send(port: String, value: Any)
}

internal fun Any.asDouble(): Double = (this as Number).toDouble()

internal fun Any.asLong(): Long = (this as Number).toLong()

/**
 * Whether the block [name] is a **pure function** of its inputs, so that it can also be asked for its result (a `PULL` tether): a block of the themes `text`, `math`,
 * `logic` and `data` without state and without a trigger. Such a block supports `MESSAGE` and `PULL` on every port.
 */
internal fun isPure(name: String): Boolean {
    val theme = name.substringBefore('.')
    if (theme !in setOf("text", "math", "logic", "data")) return false
    return name !in setOf("math.counter", "math.accumulator", "math.constant", "math.random") && !name.startsWith("logic.if") && !name.startsWith("logic.filter")
}

/** [ports] with `PULL` among the tether types of every port. */
internal fun withPull(ports: List<PortDefinition>): List<PortDefinition> =
    ports.map { PortDefinition(it.name, it.direction, it.tetherTypes + TetherType.PULL, it.schema, it.varArg, it.typeParam, it.responseSchema) }

/** An entry for a block that reacts to messages at [inputs] with a handler made from its configuration (state of the block lives in the closure). */
internal fun handlerEntry(
    name: String,
    ports: List<PortDefinition>,
    configuration: String? = null,
    make: (Map<String, Any?>) -> suspend (String, Any, Emit) -> Unit,
): Entry {
    val inputs = ports.filter { it.direction == PortDirection.IN }.map { it.name }
    // a pure block with one input can be asked for its result: it asks its input, applies the function and answers
    val pull = isPure(name) && inputs.size == 1
    return entry(name, if (pull) withPull(ports) else ports, configuration) {
        Multi(inputs, ports.filter { it.direction == PortDirection.OUT }.map { it.name }, make, pull)
    }
}

/** An entry for a block with two inputs `a` and `b`: [make] gives the function of their latest values, once both have one. */
internal fun latest2Entry(
    name: String,
    inType: SchemaRef,
    outputs: List<PortDefinition>,
    configuration: String? = null,
    make: (Map<String, Any?>) -> suspend (Any, Any, Emit) -> Unit,
): Entry {
    val ports = listOf(inPort("a", inType), inPort("b", inType)) + outputs
    val pull = isPure(name)
    return entry(name, if (pull) withPull(ports) else ports, configuration) { Latest2(outputs.map { it.name }, make, pull) }
}

/** Messages at one of the [inputs] go to the handler, which sends on any of the [outputs]. */
internal class Multi(
    private val inputs: List<String>,
    private val outputs: List<String>,
    private val make: (Map<String, Any?>) -> suspend (String, Any, Emit) -> Unit,
    /** The block has one input and can be asked for its result at an output (a `PULL` tether). */
    private val pull: Boolean = false,
) : Block {
    private lateinit var handler: suspend (String, Any, Emit) -> Unit
    private val tethers = HashMap<String, Tether>()
    private val emit = Emit { port, value -> tethers.getValue(port).send(value) }
    private lateinit var input: Tether

    override suspend fun init(context: BlockContext) {
        handler = make(context.config)
        for (name in outputs) tethers[name] = context.ports.port(name)
        if (pull) input = context.ports.port(inputs.single())
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event is TetherEvent.Message && event.port.name in inputs) handler(event.port.name, event.value, emit)
        if (pull && event is TetherEvent.Request && event.port.name in outputs) {
            // the function is applied to what the input answers (the context goes upstream), and the value of the asked output is the answer
            val wanted = event.port.name
            var result: Any? = null
            handler(inputs.single(), input.request(event.value), Emit { port, value -> if (port == wanted) result = value })
            event.respond(result ?: throw IllegalStateException("the block has no value at '$wanted' for this input"))
        }
    }
}

/** Two inputs, `a` and `b`: every message at one of them calls the handler with the latest values of both, as soon as both have a value. */
internal class Latest2(
    private val outputs: List<String>,
    private val make: (Map<String, Any?>) -> suspend (Any, Any, Emit) -> Unit,
    /** The block can be asked for its result at an output (a `PULL` tether): it asks both inputs. */
    private val pull: Boolean = false,
) : Block {
    private lateinit var handler: suspend (Any, Any, Emit) -> Unit
    private val tethers = HashMap<String, Tether>()
    private val emit = Emit { port, value -> tethers.getValue(port).send(value) }
    private var a: Any? = null
    private var b: Any? = null
    private lateinit var inputA: Tether
    private lateinit var inputB: Tether

    override suspend fun init(context: BlockContext) {
        handler = make(context.config)
        for (name in outputs) tethers[name] = context.ports.port(name)
        if (pull) {
            inputA = context.ports.port("a")
            inputB = context.ports.port("b")
        }
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (pull && event is TetherEvent.Request && event.port.name in outputs) {
            val wanted = event.port.name
            var result: Any? = null
            handler(inputA.request(event.value), inputB.request(event.value), Emit { port, value -> if (port == wanted) result = value })
            event.respond(result ?: throw IllegalStateException("the block has no value at '$wanted' for these inputs"))
            return
        }
        if (event !is TetherEvent.Message) return
        when (event.port.name) {
            "a" -> a = event.value
            "b" -> b = event.value
            else -> return
        }
        val x = a
        val y = b
        if (x != null && y != null) handler(x, y, emit)
    }
}
