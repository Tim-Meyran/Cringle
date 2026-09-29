// SPDX-License-Identifier: Apache-2.0

package cringle.testkit

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.BlockDefinition
import cringle.contract.BlockProvider
import cringle.contract.DriverSet
import cringle.contract.DwhDriver
import cringle.contract.DwhEntry
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherEvent
import cringle.contract.TetherType

/** Sample block: reads strings from `in`, writes them upper-cased to `out` and logs them in the DWH. */
class ShoutBlock(private val dwh: DwhDriver) : Block {
    private lateinit var context: BlockContext
    var started = false

    override suspend fun init(context: BlockContext) {
        this.context = context
    }

    override suspend fun start() {
        started = true
    }

    override suspend fun stop() {
        started = false
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event is TetherEvent.Message && event.port.name == "in") {
            val text = (event.value as String).uppercase()
            dwh.write(DwhEntry("shout", text))
            context.ports.port("out").send(text)
        }
    }
}

/** Sample block: answers requests on `ask` with the number of requests so far, and copies streams. */
class CounterBlock : Block {
    private var count = 0

    override suspend fun onTetherEvent(event: TetherEvent) {
        when (event) {
            is TetherEvent.Request -> event.respond(++count)
            is TetherEvent.StreamOpened -> event.stream.incoming.collect { event.stream.send("echo:$it") }
            is TetherEvent.ByteStreamOpened -> event.stream.incoming.collect { event.stream.write(it.reversedArray()) }
            else -> Unit
        }
    }
}

/** Sample block: forwards each message from `in` to the VarArg port `fanout` slot given by the message. */
class FanoutBlock(private val context: () -> BlockContext) : Block {
    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event is TetherEvent.Message) {
            val (slot, text) = event.value as Pair<*, *>
            context().ports.varArgPort("fanout")[slot as Int].send(text!!)
        }
    }
}

val orderSchema = SchemaRef("cringle.std", "String")

object SampleProvider : BlockProvider {
    val shout = BlockDefinition(
        "shout",
        listOf(orderSchema),
        listOf(
            PortDefinition("in", PortDirection.IN, setOf(TetherType.MESSAGE), orderSchema),
            PortDefinition("out", PortDirection.OUT, setOf(TetherType.MESSAGE), orderSchema),
        ),
        listOf("dwh"),
    )
    val counter = BlockDefinition(
        "counter",
        emptyList(),
        listOf(PortDefinition("ask", PortDirection.IN, setOf(TetherType.REQUEST_RESPONSE, TetherType.STREAM), orderSchema)),
        emptyList(),
    )

    override val definitions: List<BlockDefinition> = listOf(shout, counter)

    override fun createBlock(definitionName: String, drivers: DriverSet): Block = when (definitionName) {
        "shout" -> ShoutBlock(drivers[DwhDriver::class])
        "counter" -> CounterBlock()
        else -> throw IllegalArgumentException("unknown block $definitionName")
    }
}

