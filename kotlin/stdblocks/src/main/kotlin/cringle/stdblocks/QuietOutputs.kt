// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.BlockPorts
import cringle.contract.Tether
import cringle.contract.TetherByteStream
import cringle.contract.TetherStream
import cringle.contract.TetherType

/**
 * Makes an output that nothing is connected to harmless for every block of the library: a message that a block sends on a port without a tether goes nowhere
 * instead of failing the block (and with it the fabric). The engine refuses such a send with an [IllegalStateException] "... is not connected in the blueprint"; a
 * block of the library has outputs that are optional parts of a flow (`out` of `dwh.write`, `error` of `text.parse`), and the blueprint author should not have to
 * connect them to something. Requests, streams and every other failure are passed on as they are.
 */
internal class QuietOutputs(private val block: Block) : Block {
    override suspend fun init(context: BlockContext) = block.init(QuietContext(context))

    override suspend fun start() = block.start()

    override suspend fun stop() = block.stop()

    override suspend fun destroy() = block.destroy()

    override suspend fun onTetherEvent(event: cringle.contract.TetherEvent) = block.onTetherEvent(event)
}

private class QuietContext(private val context: BlockContext) : BlockContext by context {
    override val ports: BlockPorts = object : BlockPorts {
        override fun port(name: String): Tether = QuietTether(context.ports.port(name))

        override fun varArgPort(name: String): List<Tether> = context.ports.varArgPort(name).map(::QuietTether)
    }
}

private class QuietTether(private val tether: Tether) : Tether {
    override val type: TetherType get() = tether.type

    override suspend fun send(message: Any) {
        try {
            tether.send(message)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("is not connected") != true) throw e
        }
    }

    override suspend fun request(request: Any): Any = tether.request(request)

    override suspend fun openStream(): TetherStream = tether.openStream()

    override suspend fun openByteStream(): TetherByteStream = tether.openByteStream()
}
