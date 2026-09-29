// SPDX-License-Identifier: Apache-2.0

package cringle.contract

/**
 * Refers to a port of a block.
 *
 * @property name the port name as declared in [PortDefinition.name].
 * @property index the position within a VarArg port, or `null` for a plain port.
 */
public data class PortRef(public val name: String, public val index: Int? = null) {
    init {
        require(name.isNotBlank()) { "PortRef name must not be blank" }
        require(index == null || index >= 0) { "PortRef index must not be negative: $index" }
    }
}

/**
 * Something that happened at a port of a block. The engine delivers it to [Block.onTetherEvent].
 * Values are untyped and correspond to the schema of the port.
 */
public sealed interface TetherEvent {
    /** The port the event arrived at. */
    public val port: PortRef

    /**
     * An asynchronous message arrived.
     *
     * @property port the port the message arrived at.
     * @property value the message payload, corresponding to the schema of the port.
     */
    public class Message(override val port: PortRef, public val value: Any) : TetherEvent

    /**
     * A request arrived. The block answers exactly once with [respond].
     *
     * @property port the port the request arrived at.
     * @property value the request payload, corresponding to the schema of the port.
     */
    public class Request(
        override val port: PortRef,
        public val value: Any,
        private val responder: suspend (Any) -> Unit,
    ) : TetherEvent {
        /** Sends [response] back to the caller. */
        public suspend fun respond(response: Any) {
            responder(response)
        }
    }

    /**
     * The other end opened a stream.
     *
     * @property port the port the stream arrived at.
     * @property stream the stream to read from and write to.
     */
    public class StreamOpened(override val port: PortRef, public val stream: TetherStream) : TetherEvent

    /**
     * The other end opened a raw byte stream.
     *
     * @property port the port the byte stream arrived at.
     * @property stream the byte stream to read from and write to.
     */
    public class ByteStreamOpened(override val port: PortRef, public val stream: TetherByteStream) : TetherEvent
}
