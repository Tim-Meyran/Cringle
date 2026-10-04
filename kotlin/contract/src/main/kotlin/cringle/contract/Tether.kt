// SPDX-License-Identifier: Apache-2.0

package cringle.contract

import kotlinx.coroutines.flow.Flow

/**
 * The kinds of communication a tether can carry. The type of a tether is fixed when the blueprint is created
 * and never changes at runtime.
 */
public enum class TetherType {
    /** Synchronous request and response. The caller suspends until the response arrives. */
    REQUEST_RESPONSE,

    /** Asynchronous message, fire and forget. */
    MESSAGE,

    /** A stream of values. */
    STREAM,

    /** A raw byte stream. */
    BYTE_STREAM,

    /**
     * A raw byte stream carried over a real TCP connection on the loopback interface. The blueprint gives the port:
     * the receiving (IN) end listens on it, the sending (OUT) end connects with [Tether.openByteStream].
     */
    TCP,

    /** A raw byte stream carried over a serial connection to an external device. */
    SERIAL,
}

/**
 * The runtime handle of one end of a tether connection, obtained from [BlockPorts].
 *
 * Only the operations that belong to [type] are valid; calling any other operation throws
 * [IllegalStateException]. **[Zu bestätigen]** A single interface with all operations keeps the contract small;
 * the engine may offer more specific views later.
 *
 * Values are untyped ([Any]) here. They correspond to the schema of the port and are validated by the engine
 * at runtime. None of the operations blocks the calling thread: they suspend.
 * Delivery guarantees, backpressure and retries are configuration matters and not part of this contract.
 */
public interface Tether {
    /** The type of this tether. */
    public val type: TetherType

    /** Sends [message] and returns without waiting for the receiver. Valid for [TetherType.MESSAGE]. */
    public suspend fun send(message: Any)

    /** Sends [request] and suspends until the response arrives. Valid for [TetherType.REQUEST_RESPONSE]. */
    public suspend fun request(request: Any): Any

    /** Opens a stream of values. Valid for [TetherType.STREAM]. */
    public suspend fun openStream(): TetherStream

    /** Opens a raw byte stream. Valid for [TetherType.BYTE_STREAM] and [TetherType.TCP]. */
    public suspend fun openByteStream(): TetherByteStream
}

/** A bidirectional stream of values over a tether of type [TetherType.STREAM]. */
public interface TetherStream {
    /** The values arriving from the other end. The flow completes when the other end closes the stream. */
    public val incoming: Flow<Any>

    /** Sends [item] to the other end, suspending while the stream cannot take it. */
    public suspend fun send(item: Any)

    /** Closes this end of the stream. */
    public suspend fun close()
}

/** A bidirectional raw byte stream over a tether of type [TetherType.BYTE_STREAM]. */
public interface TetherByteStream {
    /** The bytes arriving from the other end, in chunks. The flow completes when the other end closes the stream. */
    public val incoming: Flow<ByteArray>

    /** Writes [bytes] to the other end, suspending while the stream cannot take them. */
    public suspend fun write(bytes: ByteArray)

    /** Closes this end of the stream. */
    public suspend fun close()
}
