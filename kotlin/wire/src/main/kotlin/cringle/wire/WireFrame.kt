// SPDX-License-Identifier: Apache-2.0

package cringle.wire

import kotlinx.serialization.json.JsonElement

/**
 * A decoded wire frame. The codec produces one of these from a byte stream and consumes one to produce bytes.
 * See `spec/wire.md` §4.
 */
public sealed interface WireFrame {
    /** The frame type. */
    public val frameType: FrameType

    /** The correlation ID (REQUEST/RESPONSE) or stream ID (STREAM_OPEN/STREAM_ITEM/STREAM_CLOSE). */
    public val correlationOrStreamId: ULong

    /** The schema namespace, or null if the frame has no schema (BYTES, STREAM_CLOSE). */
    public val schemaNamespace: String?
}

/** An asynchronous message. */
public class Message(
    override val correlationOrStreamId: ULong,
    override val schemaNamespace: String,
    public val value: JsonElement,
) : WireFrame {
    init { require(schemaNamespace.isNotEmpty()) { "Message schemaNamespace must not be empty" } }
    override val frameType: FrameType get() = FrameType.MESSAGE
}

/** A request. */
public class Request(
    override val correlationOrStreamId: ULong,
    override val schemaNamespace: String,
    public val value: JsonElement,
) : WireFrame {
    init { require(schemaNamespace.isNotEmpty()) { "Request schemaNamespace must not be empty" } }
    override val frameType: FrameType get() = FrameType.REQUEST
}

/** A response to a request. */
public class Response(
    override val correlationOrStreamId: ULong,
    override val schemaNamespace: String,
    public val value: JsonElement,
) : WireFrame {
    init { require(schemaNamespace.isNotEmpty()) { "Response schemaNamespace must not be empty" } }
    override val frameType: FrameType get() = FrameType.RESPONSE
}

/** Opens a stream. */
public class StreamOpen(
    override val correlationOrStreamId: ULong,
) : WireFrame {
    override val frameType: FrameType get() = FrameType.STREAM_OPEN
    override val schemaNamespace: String? get() = null
}

/** A stream item. */
public class StreamItem(
    override val correlationOrStreamId: ULong,
    override val schemaNamespace: String,
    public val value: JsonElement,
) : WireFrame {
    init { require(schemaNamespace.isNotEmpty()) { "StreamItem schemaNamespace must not be empty" } }
    override val frameType: FrameType get() = FrameType.STREAM_ITEM
}

/** Closes a stream. */
public class StreamClose(
    override val correlationOrStreamId: ULong,
) : WireFrame {
    override val frameType: FrameType get() = FrameType.STREAM_CLOSE
    override val schemaNamespace: String? get() = null
}

/** Raw bytes. */
public class Bytes(
    override val correlationOrStreamId: ULong,
    public val payload: ByteArray,
) : WireFrame {
    override val frameType: FrameType get() = FrameType.BYTES
    override val schemaNamespace: String? get() = null

    override fun equals(other: Any?): Boolean =
        other is Bytes && correlationOrStreamId == other.correlationOrStreamId && payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = correlationOrStreamId.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String = "Bytes(correlationOrStreamId=$correlationOrStreamId, payload.size=${payload.size})"
}

/** An error. Carries a value of type `cringle.std/Error`. */
public class Error(
    override val correlationOrStreamId: ULong,
    public val value: JsonElement,
) : WireFrame {
    override val frameType: FrameType get() = FrameType.ERROR
    override val schemaNamespace: String get() = "cringle.std"
}
