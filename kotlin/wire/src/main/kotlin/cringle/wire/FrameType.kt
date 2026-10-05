// SPDX-License-Identifier: Apache-2.0
package cringle.wire

/**
 * The eight frame types of the wire format. The codes are fixed by `spec/wire.md` §4.
 *
 * @property code the single-byte code that identifies the frame type on the wire.
 */
public enum class FrameType(public val code: UByte) {
    /** Asynchronous message; payload: canonical JSON of the value. */
    MESSAGE(0x01u),

    /** A request; payload: canonical JSON of the value. */
    REQUEST(0x02u),

    /** A response to a request; payload: canonical JSON of the value. */
    RESPONSE(0x03u),

    /** Opens a stream; no payload. */
    STREAM_OPEN(0x04u),

    /** A stream item; payload: canonical JSON of the value. */
    STREAM_ITEM(0x05u),

    /** Closes a stream; no payload. */
    STREAM_CLOSE(0x06u),

    /** Raw bytes; payload: literal bytes (not base64). */
    BYTES(0x07u),

    /** An error; payload: canonical JSON of `cringle.std/Error`. */
    ERROR(0x08u);

    public companion object {
        /** Returns the frame type for [code], or null if [code] is not a known frame type. */
        public fun fromCode(code: UByte): FrameType? = values().firstOrNull { it.code == code }
    }
}
