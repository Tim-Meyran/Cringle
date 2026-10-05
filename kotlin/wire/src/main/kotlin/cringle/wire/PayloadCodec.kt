// SPDX-License-Identifier: Apache-2.0
package cringle.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * Encodes and decodes the payload of typed frames. The wire envelope is encoding-agnostic; this interface is the
 * extension point for different payload encodings (JSON, CBOR, protobuf). The first implementation is [JsonPayloadCodec].
 * See `spec/wire.md` §8.
 */
public interface PayloadCodec {
    /** Encodes [value] to the payload bytes of a typed frame. */
    public fun encodeTyped(value: JsonElement): ByteArray

    /** Decodes the payload bytes of a typed frame back to a value. */
    public fun decodeTyped(bytes: ByteArray): JsonElement
}

/**
 * The first [PayloadCodec] implementation. Uses the canonical form per `spec/schema.md` §6 (RFC 8785 with the integer
 * extension: integers that fit into a signed 64-bit number are written exactly, even beyond 2^53).
 */
public class JsonPayloadCodec : PayloadCodec {
    override fun encodeTyped(value: JsonElement): ByteArray =
        CanonicalJson.encode(value).toByteArray(Charsets.UTF_8)

    override fun decodeTyped(bytes: ByteArray): JsonElement =
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
}

/**
 * The canonical form of a value: RFC 8785 (JSON Canonicalization Scheme), with one extension: an integer that
 * fits into a signed 64-bit number is written exactly, even beyond 2^53 where RFC 8785 would round it to a
 * double. Everything else follows the RFC: UTF-8, no insignificant whitespace, object keys sorted by UTF-16
 * code units, numbers as ECMAScript prints them.
 *
 * Valid `Bytes` and `Timestamp` values are already in one canonical spelling, so canonicalization needs no
 * schema.
 *
 * This is a verbatim copy of `cringle.schema.CanonicalJson`; the wire module cannot depend on the schema module,
 * so the algorithm is duplicated here. See `spec/schema.md` §6.
 */
internal object CanonicalJson {
    private val integerLiteral = Regex("-?(0|[1-9][0-9]*)")

    /**
     * Returns the canonical form of [value].
     *
     * @throws IllegalArgumentException if a number does not fit into a finite double.
     */
    fun encode(value: JsonElement): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: JsonElement) {
        when (value) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                value.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    appendString(out, key)
                    out.append(':')
                    append(out, value.getValue(key))
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                value.forEachIndexed { index, element ->
                    if (index > 0) out.append(',')
                    append(out, element)
                }
                out.append(']')
            }
            is JsonPrimitive -> when {
                value.isString -> appendString(out, value.content)
                value.content == "true" || value.content == "false" -> out.append(value.content)
                else -> out.append(number(value.content))
            }
        }
    }

    private fun number(literal: String): String {
        if (integerLiteral.matches(literal)) {
            literal.toLongOrNull()?.let { return it.toString() }
        }
        val number = literal.toDouble()
        require(number.isFinite()) { "Number out of range for the canonical form: $literal" }
        return ecmaScript(number)
    }

    /** Formats a finite double like ECMAScript's `Number::toString` (RFC 8785, section 3.2.2.3). */
    private fun ecmaScript(number: Double): String {
        if (number == 0.0) return "0"
        val sign = if (number < 0) "-" else ""
        // Double.toString yields the shortest decimal that round-trips (JDK 19 and later).
        val decimal = BigDecimal(java.lang.Double.toString(Math.abs(number))).stripTrailingZeros()
        val digits = decimal.unscaledValue().toString()
        val k = digits.length
        val n = k - decimal.scale()
        val text = when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val exponent = n - 1
                val mantissa = if (k == 1) digits else digits.substring(0, 1) + "." + digits.substring(1)
                mantissa + "e" + (if (exponent < 0) "-" else "+") + Math.abs(exponent)
            }
        }
        return sign + text
    }

    private fun appendString(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\t' -> out.append("\\t")
                c == '\n' -> out.append("\\n")
                c == '\u000C' -> out.append("\\f")
                c == '\r' -> out.append("\\r")
                c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
