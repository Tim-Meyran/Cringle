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

private val MESSAGE = setOf(TetherType.MESSAGE)

internal fun inPort(name: String, schema: SchemaRef = STRING) = PortDefinition(name, PortDirection.IN, MESSAGE, schema)

internal fun outPort(name: String, schema: SchemaRef = STRING) = PortDefinition(name, PortDirection.OUT, MESSAGE, schema)

/** A block of the library: its definition and how to make it. */
internal class Entry(val definition: BlockDefinition, val create: (DriverSet) -> Block)

/** [configuration] is the name of the type in the namespace `cringle.stdblocks`, or `null` for a block without configuration. */
internal fun entry(name: String, ports: List<PortDefinition>, configuration: String? = null, drivers: List<String> = emptyList(), create: (DriverSet) -> Block) = Entry(
    BlockDefinition(name, ports.map { it.schema }.distinct(), ports, drivers, configuration?.let { SchemaRef(StdBlockProvider.NAMESPACE, it) }),
    create,
)

/** What a handler sends: the value goes out on the port of that name. */
internal fun interface Emit {
    suspend fun send(port: String, value: Any)
}

internal fun Any.asDouble(): Double = (this as Number).toDouble()

internal fun Any.asLong(): Long = (this as Number).toLong()

/** An entry for a block that reacts to messages at [inputs] with a handler made from its configuration (state of the block lives in the closure). */
internal fun handlerEntry(
    name: String,
    ports: List<PortDefinition>,
    configuration: String? = null,
    make: (Map<String, Any?>) -> suspend (String, Any, Emit) -> Unit,
) = entry(name, ports, configuration) {
    Multi(ports.filter { it.direction == PortDirection.IN }.map { it.name }, ports.filter { it.direction == PortDirection.OUT }.map { it.name }, make)
}

/** An entry for a block with two inputs `a` and `b`: [make] gives the function of their latest values, once both have one. */
internal fun latest2Entry(
    name: String,
    inType: SchemaRef,
    outputs: List<PortDefinition>,
    configuration: String? = null,
    make: (Map<String, Any?>) -> suspend (Any, Any, Emit) -> Unit,
) = entry(name, listOf(inPort("a", inType), inPort("b", inType)) + outputs, configuration) { Latest2(outputs.map { it.name }, make) }

/** Messages at one of the [inputs] go to the handler, which sends on any of the [outputs]. */
internal class Multi(
    private val inputs: List<String>,
    private val outputs: List<String>,
    private val make: (Map<String, Any?>) -> suspend (String, Any, Emit) -> Unit,
) : Block {
    private lateinit var handler: suspend (String, Any, Emit) -> Unit
    private val tethers = HashMap<String, Tether>()
    private val emit = Emit { port, value -> tethers.getValue(port).send(value) }

    override suspend fun init(context: BlockContext) {
        handler = make(context.config)
        for (name in outputs) tethers[name] = context.ports.port(name)
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event is TetherEvent.Message && event.port.name in inputs) handler(event.port.name, event.value, emit)
    }
}

/** Two inputs, `a` and `b`: every message at one of them calls the handler with the latest values of both, as soon as both have a value. */
internal class Latest2(private val outputs: List<String>, private val make: (Map<String, Any?>) -> suspend (Any, Any, Emit) -> Unit) : Block {
    private lateinit var handler: suspend (Any, Any, Emit) -> Unit
    private val tethers = HashMap<String, Tether>()
    private val emit = Emit { port, value -> tethers.getValue(port).send(value) }
    private var a: Any? = null
    private var b: Any? = null

    override suspend fun init(context: BlockContext) {
        handler = make(context.config)
        for (name in outputs) tethers[name] = context.ports.port(name)
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
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
