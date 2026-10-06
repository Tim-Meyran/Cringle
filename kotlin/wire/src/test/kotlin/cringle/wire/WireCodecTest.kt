// SPDX-License-Identifier: Apache-2.0

package cringle.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WireCodecTest {
    private val typedCodec = WireCodec(TetherMode.TYPED)
    private val bytesCodec = WireCodec(TetherMode.BYTES)

    @Test
    fun everyFrameTypeRoundtrips() {
        val cid = 0x0123456789ABCDEFUL
        val ns = "cringle.demo"
        val frames: List<WireFrame> = listOf(
            Message(cid, ns, Json.parseToJsonElement("""{"hello":"world"}""")),
            Request(cid, ns, Json.parseToJsonElement("""{"q":42}""")),
            Response(cid, ns, Json.parseToJsonElement("""{"a":42}""")),
            StreamOpen(cid),
            StreamItem(cid, ns, Json.parseToJsonElement("""{"i":1}""")),
            StreamClose(cid),
            Bytes(cid, byteArrayOf(1, 2, 3, 4, 5)),
            Error(cid, Json.parseToJsonElement("""{"code":"X","message":"y"}""")),
        )
        for (original in frames) {
            val codec = if (original is Bytes) bytesCodec else typedCodec
            val encoded = codec.encode(original)
            val nsBytes = original.schemaNamespace?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val payloadBytes: ByteArray = when (original) {
                is Message -> JsonPayloadCodec().encodeTyped(original.value)
                is Request -> JsonPayloadCodec().encodeTyped(original.value)
                is Response -> JsonPayloadCodec().encodeTyped(original.value)
                is StreamItem -> JsonPayloadCodec().encodeTyped(original.value)
                is Error -> JsonPayloadCodec().encodeTyped(original.value)
                is StreamOpen -> ByteArray(0)
                is StreamClose -> ByteArray(0)
                is Bytes -> original.payload
            }
            assertEquals(4 + 1 + 1 + 8 + 2 + nsBytes.size + payloadBytes.size, encoded.size, "encoded size for ${original.frameType}")
            val decoded = codec.newDecoder().feed(encoded).single()
            assertEquals(original.frameType, decoded.frameType)
            assertEquals(original.correlationOrStreamId, decoded.correlationOrStreamId)
            assertEquals(original.schemaNamespace, decoded.schemaNamespace)
            if (original is Bytes) {
                assertArrayEquals(original.payload, (decoded as Bytes).payload)
            } else if (original is StreamOpen || original is StreamClose) {
                // no value to compare
            } else {
                // Typed frame: compare the canonical JSON of the value
                val origValue: JsonElement = when (original) {
                    is Message -> original.value
                    is Request -> original.value
                    is Response -> original.value
                    is StreamItem -> original.value
                    is Error -> original.value
                    else -> error("unexpected frame type")
                }
                val decValue: JsonElement = when (decoded) {
                    is Message -> decoded.value
                    is Request -> decoded.value
                    is Response -> decoded.value
                    is StreamItem -> decoded.value
                    is Error -> decoded.value
                    else -> error("unexpected decoded frame type")
                }
                val pc = JsonPayloadCodec()
                assertEquals(pc.encodeTyped(origValue).toString(Charsets.UTF_8), pc.encodeTyped(decValue).toString(Charsets.UTF_8))
            }
        }
    }

    @Test
    fun partialFrameIsBufferedUntilComplete() {
        val frame = Message(0xAAUL, "cringle.demo", Json.parseToJsonElement("""{"x":1}"""))
        val encoded = typedCodec.encode(frame)
        val decoder = typedCodec.newDecoder()
        // Feed only the first 10 bytes (4 length + 6 of 12 fixed-header bytes)
        val first = decoder.feed(encoded.copyOfRange(0, 10))
        assertTrue(first.isEmpty(), "partial frame should not decode")
        // Feed the remaining bytes
        val rest = decoder.feed(encoded.copyOfRange(10, encoded.size))
        assertEquals(1, rest.size)
        assertEquals(frame.frameType, rest[0].frameType)
    }

    @Test
    fun truncatedFrameRaisesWireFormatException() {
        // Build a frame whose declared total length is less than 12 (the fixed header size).
        // Layout: 4 bytes length prefix (= 5) + 5 bytes of header (version, frameType, partial correlationOrStreamId).
        val buf = ByteArray(9)
        writeUInt32BE(buf, 0, 5)
        buf[4] = 1 // wireFormatVersion
        buf[5] = 0x01 // frameType = MESSAGE
        // correlationOrStreamId and schemaNamespaceByteLen are zero (not fully written).
        val ex = assertThrows<WireFormatException> { typedCodec.newDecoder().feed(buf) }
        val msg = ex.message ?: ""
        assertTrue(
            msg.contains("too short") || msg.contains("12"),
            "message should mention 'too short' or '12': $msg",
        )
    }

    @Test
    fun streamOpenWithSchemaNamespaceRaisesWireFormatException() {
        val cid = 0x0123456789ABCDEFUL
        // Spec-conformant STREAM_OPEN: schemaNamespaceByteLen = 0, no payload.
        val goodTotalLength = 1 + 1 + 8 + 2
        val good = ByteArray(4 + goodTotalLength)
        writeUInt32BE(good, 0, goodTotalLength)
        good[4] = 1 // wireFormatVersion
        good[5] = 0x04 // frameType = STREAM_OPEN
        writeUInt64BE(good, 6, cid)
        writeUInt16BE(good, 14, 0) // schemaNamespaceByteLen = 0
        val decoded = typedCodec.newDecoder().feed(good).single()
        assertEquals(FrameType.STREAM_OPEN, decoded.frameType)
        assertEquals(cid, decoded.correlationOrStreamId)
        assertTrue(decoded is StreamOpen, "decoded frame should be StreamOpen")

        // STREAM_OPEN with a non-empty schema namespace must be rejected.
        val ns = "abcde"
        val nsBytes = ns.toByteArray(Charsets.UTF_8)
        val badTotalLength = 1 + 1 + 8 + 2 + nsBytes.size
        val bad = ByteArray(4 + badTotalLength)
        writeUInt32BE(bad, 0, badTotalLength)
        bad[4] = 1
        bad[5] = 0x04
        writeUInt64BE(bad, 6, cid)
        writeUInt16BE(bad, 14, nsBytes.size)
        nsBytes.copyInto(bad, 16)
        assertThrows<WireFormatException> { typedCodec.newDecoder().feed(bad) }
    }

    @Test
    fun streamCloseWithSchemaNamespaceRaisesWireFormatException() {
        val cid = 0x0123456789ABCDEFUL
        // Spec-conformant STREAM_CLOSE: schemaNamespaceByteLen = 0, no payload.
        val goodTotalLength = 1 + 1 + 8 + 2
        val good = ByteArray(4 + goodTotalLength)
        writeUInt32BE(good, 0, goodTotalLength)
        good[4] = 1
        good[5] = 0x06 // frameType = STREAM_CLOSE
        writeUInt64BE(good, 6, cid)
        writeUInt16BE(good, 14, 0)
        val decoded = typedCodec.newDecoder().feed(good).single()
        assertEquals(FrameType.STREAM_CLOSE, decoded.frameType)
        assertEquals(cid, decoded.correlationOrStreamId)
        assertTrue(decoded is StreamClose, "decoded frame should be StreamClose")

        // STREAM_CLOSE with a non-empty schema namespace must be rejected.
        val ns = "abcde"
        val nsBytes = ns.toByteArray(Charsets.UTF_8)
        val badTotalLength = 1 + 1 + 8 + 2 + nsBytes.size
        val bad = ByteArray(4 + badTotalLength)
        writeUInt32BE(bad, 0, badTotalLength)
        bad[4] = 1
        bad[5] = 0x06
        writeUInt64BE(bad, 6, cid)
        writeUInt16BE(bad, 14, nsBytes.size)
        nsBytes.copyInto(bad, 16)
        assertThrows<WireFormatException> { typedCodec.newDecoder().feed(bad) }
    }

    @Test
    fun errorFrameWithWrongNamespaceRaisesWireFormatException() {
        val ns = "wrong.ns"
        val nsBytes = ns.toByteArray(Charsets.UTF_8)
        val payload = JsonPayloadCodec().encodeTyped(Json.parseToJsonElement("""{"code":"X","message":"y"}"""))
        val totalLength = 1 + 1 + 8 + 2 + nsBytes.size + payload.size
        val buf = ByteArray(4 + totalLength)
        writeUInt32BE(buf, 0, totalLength)
        buf[4] = 1 // wireFormatVersion
        buf[5] = 0x08 // frameType = ERROR
        writeUInt64BE(buf, 6, 0UL)
        writeUInt16BE(buf, 14, nsBytes.size)
        nsBytes.copyInto(buf, 16)
        payload.copyInto(buf, 16 + nsBytes.size)
        val ex = assertThrows<WireFormatException> { typedCodec.newDecoder().feed(buf) }
        val msg = ex.message ?: ""
        assertTrue(msg.contains(ns), "message should contain the wrong namespace '$ns': $msg")
    }

    @Test
    fun oversizeFrameRaisesWireFormatExceptionBeforeAllocation() {
        val smallCodec = WireCodec(TetherMode.TYPED, maxFrameLength = 64)
        val bigPayload = Json.parseToJsonElement("""{"x":"${"a".repeat(200)}"}""")
        val encoded = smallCodec.encode(Message(0UL, "cringle.demo", bigPayload))
        val decoder = smallCodec.newDecoder()
        val ex = assertThrows<WireFormatException> {
            decoder.feed(encoded.copyOfRange(0, 4))
        }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("64"), "message should contain limit 64: $msg")
    }

    @Test
    fun twoConsecutiveFramesDecodeIntact() {
        val f1 = Message(1UL, "cringle.demo", Json.parseToJsonElement("""{"a":1}"""))
        val f2 = Request(2UL, "cringle.demo", Json.parseToJsonElement("""{"b":2}"""))
        val combined = typedCodec.encode(f1) + typedCodec.encode(f2)
        val decoded = typedCodec.newDecoder().feed(combined)
        assertEquals(2, decoded.size)
        assertEquals(f1.frameType, decoded[0].frameType)
        assertEquals(f1.correlationOrStreamId, decoded[0].correlationOrStreamId)
        assertEquals(f2.frameType, decoded[1].frameType)
        assertEquals(f2.correlationOrStreamId, decoded[1].correlationOrStreamId)
    }

    @Test
    fun unknownFrameTypeAndVersionRaiseWireFormatException() {
        val frame = Message(0UL, "cringle.demo", Json.parseToJsonElement("""{"x":1}"""))
        val encoded = typedCodec.encode(frame)
        // Flip frameType byte (index 5) to 0x09
        val badType = encoded.copyOf().also { it[5] = 0x09.toByte() }
        val ex1 = assertThrows<WireFormatException> { typedCodec.newDecoder().feed(badType) }
        assertTrue((ex1.message ?: "").contains("9"), "message should contain 9: ${ex1.message}")
        // Flip wireFormatVersion byte (index 4) to 2
        val badVer = encoded.copyOf().also { it[4] = 2.toByte() }
        val ex2 = assertThrows<WireFormatException> { typedCodec.newDecoder().feed(badVer) }
        assertTrue((ex2.message ?: "").contains("2"), "message should contain 2: ${ex2.message}")
    }

    @Test
    fun typedFramePayloadsRoundtripInCanonicalForm() {
        // Key sorting
        val f1 = Message(0UL, "cringle.demo", Json.parseToJsonElement("""{"b":2,"a":1}"""))
        val d1 = typedCodec.newDecoder().feed(typedCodec.encode(f1)).single() as Message
        assertEquals("""{"a":1,"b":2}""", JsonPayloadCodec().encodeTyped(d1.value).toString(Charsets.UTF_8))
        // Large integer preserved
        val f2 = Message(0UL, "cringle.demo", Json.parseToJsonElement("""{"n":9007199254740993}"""))
        val d2 = typedCodec.newDecoder().feed(typedCodec.encode(f2)).single() as Message
        val n = (d2.value as kotlinx.serialization.json.JsonObject)["n"]!!
        assertEquals("9007199254740993", n.toString())
        // Stable encoding
        val f3a = Message(0UL, "cringle.demo", Json.parseToJsonElement("""{"a":1.0}"""))
        val f3b = Message(0UL, "cringle.demo", Json.parseToJsonElement("""{"a":1}"""))
        assertArrayEquals(typedCodec.encode(f3a), typedCodec.encode(f3b))
    }

    @Test
    fun modeMismatchRaisesWireFormatException() {
        // TYPED codec rejects BYTES frame on encode
        val ex1 = assertThrows<WireFormatException> {
            typedCodec.encode(Bytes(0UL, byteArrayOf(1, 2, 3)))
        }
        assertTrue((ex1.message ?: "").contains("BYTES"), "message should contain BYTES: ${ex1.message}")
        // BYTES codec rejects typed frame on encode
        val ex2 = assertThrows<WireFormatException> {
            bytesCodec.encode(Message(0UL, "cringle.demo", Json.parseToJsonElement("""{"a":1}""")))
        }
        assertTrue((ex2.message ?: "").contains("MESSAGE"), "message should contain MESSAGE: ${ex2.message}")
        // TYPED decoder rejects BYTES frame
        val bytesEncoded = bytesCodec.encode(Bytes(0UL, byteArrayOf(1, 2, 3)))
        assertThrows<WireFormatException> { typedCodec.newDecoder().feed(bytesEncoded) }
    }
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
