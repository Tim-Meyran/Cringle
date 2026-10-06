// SPDX-License-Identifier: Apache-2.0

package cringle.wire

/**
 * Encodes and decodes wire frames per `spec/wire.md`. One codec per tether; the codec is stateless except for the
 * configuration (mode, max frame length, payload codec). The decoder returned by [newDecoder] is stateful.
 *
 * @property mode the mode of the tether (typed or bytes). The codec rejects frames that do not match the mode.
 * @property maxFrameLength the maximum frame length in bytes (after the length prefix). Default 4 MiB.
 * @property payloadCodec the payload codec for typed frames. Default [JsonPayloadCodec].
 */
public class WireCodec(
    public val mode: TetherMode,
    public val maxFrameLength: Int = DEFAULT_MAX_FRAME_LENGTH,
    public val payloadCodec: PayloadCodec = JsonPayloadCodec(),
) {
    public companion object {
        /** Default maximum frame length: 4 MiB. See `spec/wire.md` §9. */
        public const val DEFAULT_MAX_FRAME_LENGTH: Int = 4 * 1024 * 1024

        /** The wire format version. See `spec/wire.md` §10. */
        public const val WIRE_FORMAT_VERSION: UByte = 1u
    }

    init {
        require(maxFrameLength >= 12) { "maxFrameLength must be at least 12 (the fixed header size)" }
    }

    /**
     * Encodes [frame] to bytes per `spec/wire.md` §3.
     * @throws WireFormatException if the frame does not match [mode].
     */
    public fun encode(frame: WireFrame): ByteArray {
        // Mode check.
        when (mode) {
            TetherMode.TYPED -> if (frame.frameType == FrameType.BYTES) {
                throw WireFormatException("typed tether cannot carry BYTES frame")
            }
            TetherMode.BYTES -> if (frame.frameType != FrameType.BYTES) {
                throw WireFormatException("bytes tether cannot carry typed frame ${frame.frameType}")
            }
        }

        // Encode the payload.
        val payload: ByteArray = when (frame) {
            is Message -> payloadCodec.encodeTyped(frame.value)
            is Request -> payloadCodec.encodeTyped(frame.value)
            is Response -> payloadCodec.encodeTyped(frame.value)
            is StreamItem -> payloadCodec.encodeTyped(frame.value)
            is Error -> payloadCodec.encodeTyped(frame.value)
            is StreamOpen -> ByteArray(0)
            is StreamClose -> ByteArray(0)
            is Bytes -> frame.payload
        }

        // Encode the schema namespace.
        val schemaNamespaceBytes: ByteArray =
            frame.schemaNamespace?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)

        val totalLength = 1 + 1 + 8 + 2 + schemaNamespaceBytes.size + payload.size
        val result = ByteArray(4 + totalLength)

        writeUInt32BE(result, 0, totalLength)
        result[4] = WIRE_FORMAT_VERSION.toByte()
        result[5] = frame.frameType.code.toByte()
        writeUInt64BE(result, 6, frame.correlationOrStreamId)
        writeUInt16BE(result, 14, schemaNamespaceBytes.size)
        schemaNamespaceBytes.copyInto(result, 16)
        payload.copyInto(result, 16 + schemaNamespaceBytes.size)

        return result
    }

    /** Creates a new streaming decoder. The decoder is stateful and not thread-safe. */
    public fun newDecoder(): WireDecoder = WireDecoder(this)

    // internal: decode a single frame from a byte range
    internal fun decodeFrame(buffer: ByteArray, start: Int, end: Int): WireFrame {
        val totalLength = end - start
        if (totalLength < 12) {
            throw WireFormatException("frame too short: $totalLength bytes")
        }

        val wireFormatVersion = buffer[start].toUByte()
        if (wireFormatVersion != WIRE_FORMAT_VERSION) {
            throw WireFormatException("unsupported wire format version $wireFormatVersion")
        }

        val frameTypeCode = buffer[start + 1].toUByte()
        val frameType = FrameType.fromCode(frameTypeCode)
            ?: throw WireFormatException("unknown frame type $frameTypeCode")

        val correlationOrStreamId = readUInt64BE(buffer, start + 2)
        val schemaNamespaceByteLen = readUInt16BE(buffer, start + 10)

        val schemaNamespace: String? = if (schemaNamespaceByteLen == 0) {
            null
        } else {
            String(buffer, start + 12, schemaNamespaceByteLen, Charsets.UTF_8)
        }

        // Mode check.
        when (mode) {
            TetherMode.TYPED -> if (frameType == FrameType.BYTES) {
                throw WireFormatException("typed tether cannot carry BYTES frame")
            }
            TetherMode.BYTES -> if (frameType != FrameType.BYTES) {
                throw WireFormatException("bytes tether cannot carry typed frame $frameType")
            }
        }

        // Schema namespace presence checks per spec §4/§5: STREAM_OPEN and STREAM_CLOSE carry no schema namespace.
        if ((frameType == FrameType.STREAM_OPEN || frameType == FrameType.STREAM_CLOSE) && schemaNamespaceByteLen != 0) {
            throw WireFormatException(
                "STREAM_OPEN/STREAM_CLOSE must have absent schema namespace, got '$schemaNamespace'",
            )
        }

        // Schema namespace check per spec §7: ERROR frame's schema namespace is always "cringle.std".
        if (frameType == FrameType.ERROR) {
            val expected = "cringle.std"
            if (schemaNamespaceByteLen != expected.length || schemaNamespace != expected) {
                throw WireFormatException(
                    "ERROR frame schema namespace must be '$expected', got '$schemaNamespace'",
                )
            }
        }

        val payloadStart = start + 12 + schemaNamespaceByteLen
        val payload = buffer.copyOfRange(payloadStart, end)

        return when (frameType) {
            FrameType.MESSAGE -> Message(correlationOrStreamId, schemaNamespace!!, payloadCodec.decodeTyped(payload))
            FrameType.REQUEST -> Request(correlationOrStreamId, schemaNamespace!!, payloadCodec.decodeTyped(payload))
            FrameType.RESPONSE -> Response(correlationOrStreamId, schemaNamespace!!, payloadCodec.decodeTyped(payload))
            FrameType.STREAM_OPEN -> StreamOpen(correlationOrStreamId)
            FrameType.STREAM_ITEM -> StreamItem(correlationOrStreamId, schemaNamespace!!, payloadCodec.decodeTyped(payload))
            FrameType.STREAM_CLOSE -> StreamClose(correlationOrStreamId)
            FrameType.BYTES -> Bytes(correlationOrStreamId, payload)
            FrameType.ERROR -> Error(correlationOrStreamId, payloadCodec.decodeTyped(payload))
        }
    }
}

/**
 * A streaming decoder that accepts chunks of bytes and returns complete frames. Stateful and not thread-safe.
 * See `spec/wire.md` §3.
 */
public class WireDecoder internal constructor(private val codec: WireCodec) {
    private var buffer = ByteArray(4096)
    private var position = 0
    private var limit = 0

    /**
     * Feeds [chunk] to the decoder and returns any complete frames that can now be decoded.
     * Partial frames are buffered until enough bytes arrive.
     * @throws WireFormatException if a frame's declared length exceeds the codec's max frame length, or if a frame
     *   is malformed.
     */
    public fun feed(chunk: ByteArray): List<WireFrame> {
        // Append chunk to the internal buffer, growing if needed.
        val required = limit + chunk.size
        if (required > buffer.size) {
            var newSize = buffer.size
            while (newSize < required) newSize *= 2
            val newBuffer = ByteArray(newSize)
            buffer.copyInto(newBuffer, 0, position, limit)
            limit -= position
            position = 0
            buffer = newBuffer
        }
        chunk.copyInto(buffer, limit)
        limit += chunk.size

        val result = mutableListOf<WireFrame>()

        // Loop: try to decode a complete frame from the buffer.
        while (true) {
            if (limit - position < 4) break
            val totalLength = readUInt32BE(buffer, position)
            if (totalLength > codec.maxFrameLength) {
                throw WireFormatException("frame length $totalLength exceeds limit ${codec.maxFrameLength}")
            }
            if (limit - position < 4 + totalLength) break
            val frame = codec.decodeFrame(buffer, position + 4, position + 4 + totalLength)
            result.add(frame)
            position += 4 + totalLength
        }

        // Compact: move remaining bytes to the start of the buffer.
        if (position > 0) {
            val remaining = limit - position
            if (remaining > 0) {
                buffer.copyInto(buffer, 0, position, limit)
            }
            position = 0
            limit = remaining
        }

        return result
    }
}

private fun readUInt32BE(buffer: ByteArray, offset: Int): Int =
    ((buffer[offset].toInt() and 0xFF) shl 24) or
        ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
        ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
        (buffer[offset + 3].toInt() and 0xFF)

private fun readUInt16BE(buffer: ByteArray, offset: Int): Int =
    ((buffer[offset].toInt() and 0xFF) shl 8) or
        (buffer[offset + 1].toInt() and 0xFF)

private fun readUInt64BE(buffer: ByteArray, offset: Int): ULong {
    var result = 0UL
    for (i in 0 until 8) {
        result = (result shl 8) or buffer[offset + i].toUByte().toULong()
    }
    return result
}

private fun writeUInt32BE(buffer: ByteArray, offset: Int, value: Int) {
    buffer[offset] = ((value shr 24) and 0xFF).toByte()
    buffer[offset + 1] = ((value shr 16) and 0xFF).toByte()
    buffer[offset + 2] = ((value shr 8) and 0xFF).toByte()
    buffer[offset + 3] = (value and 0xFF).toByte()
}

private fun writeUInt16BE(buffer: ByteArray, offset: Int, value: Int) {
    buffer[offset] = ((value shr 8) and 0xFF).toByte()
    buffer[offset + 1] = (value and 0xFF).toByte()
}

private fun writeUInt64BE(buffer: ByteArray, offset: Int, value: ULong) {
    for (i in 0 until 8) {
        buffer[offset + i] = ((value shr ((7 - i) * 8)) and 0xFFu).toByte()
    }
}
