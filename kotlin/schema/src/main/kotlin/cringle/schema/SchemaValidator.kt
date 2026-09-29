// SPDX-License-Identifier: Apache-2.0

package cringle.schema

import cringle.contract.SchemaRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Base64

/**
 * One reason why a value is not valid.
 *
 * @property path where in the value, for example `$.items[2].sku`.
 * @property message what is wrong.
 */
public data class ValidationError(public val path: String, public val message: String)

/** Validates JSON values against the types of a [SchemaRegistry]. The rules are in `spec/schema.md`. */
public class SchemaValidator(private val registry: SchemaRegistry) {
    /**
     * Validates [value] against [type] and returns every problem found. An empty list means the value is valid.
     * An unknown [type] yields one error and never throws.
     */
    public fun validate(value: JsonElement, type: SchemaRef): List<ValidationError> {
        val definition = registry.resolve(type) ?: return listOf(ValidationError("$", "unknown type '$type'"))
        val errors = ArrayList<ValidationError>()
        validateDefinition(value, definition, "$", errors)
        return errors
    }

    private fun validateExpression(value: JsonElement, expression: TypeExpr, path: String, errors: MutableList<ValidationError>) {
        when (expression) {
            is TypeExpr.Ref -> {
                val definition = registry.resolve(expression.ref)
                if (definition == null) {
                    errors += ValidationError(path, "unknown type '${expression.ref}'")
                } else {
                    validateDefinition(value, definition, path, errors)
                }
            }
            is TypeExpr.ListOf -> {
                if (value !is JsonArray) {
                    errors += kindError(path, "an array", value)
                    return
                }
                value.forEachIndexed { index, element -> validateExpression(element, expression.element, "$path[$index]", errors) }
            }
            is TypeExpr.MapOf -> {
                if (value !is JsonObject) {
                    errors += kindError(path, "an object", value)
                    return
                }
                for ((key, element) in value) validateExpression(element, expression.value, child(path, key), errors)
            }
            is TypeExpr.Optional -> validateExpression(value, expression.inner, path, errors)
        }
    }

    private fun validateDefinition(value: JsonElement, definition: TypeDef, path: String, errors: MutableList<ValidationError>) {
        if (value is JsonNull) {
            errors += ValidationError(path, "null is not a valid value")
            return
        }
        when (definition) {
            is TypeDef.Primitive -> validatePrimitive(value, definition.kind, path, errors)
            is TypeDef.Enum -> {
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (text == null) {
                    errors += kindError(path, "a string (an enum value)", value)
                } else if (text !in definition.values) {
                    errors += ValidationError(path, "unknown enum value '$text', expected one of ${definition.values.joinToString()}")
                }
            }
            is TypeDef.Record -> validateRecord(value, definition, path, errors)
        }
    }

    private fun validateRecord(value: JsonElement, record: TypeDef.Record, path: String, errors: MutableList<ValidationError>) {
        if (value !is JsonObject) {
            errors += kindError(path, "an object", value)
            return
        }
        for ((name, expression) in record.fields) {
            val field = value[name]
            when {
                field == null ->
                    if (expression !is TypeExpr.Optional) errors += ValidationError(path, "missing required field '$name'")
                field is JsonNull -> errors += ValidationError(child(path, name), "null is not a valid value")
                else -> validateExpression(field, expression, child(path, name), errors)
            }
        }
        for (name in value.keys) {
            if (name !in record.fields) errors += ValidationError(child(path, name), "unknown field '$name'")
        }
    }

    private fun validatePrimitive(value: JsonElement, kind: PrimitiveKind, path: String, errors: MutableList<ValidationError>) {
        val primitive = value as? JsonPrimitive
        when (kind) {
            PrimitiveKind.STRING ->
                if (primitive == null || !primitive.isString) errors += kindError(path, "a string", value)
            PrimitiveKind.BOOLEAN ->
                if (primitive == null || primitive.isString || (primitive.content != "true" && primitive.content != "false")) {
                    errors += kindError(path, "a boolean", value)
                }
            PrimitiveKind.INT -> validateInt(value, primitive, path, errors)
            PrimitiveKind.DOUBLE -> validateDouble(value, primitive, path, errors)
            PrimitiveKind.BYTES -> {
                if (primitive == null || !primitive.isString) {
                    errors += kindError(path, "a base64 string", value)
                } else if (!isCanonicalBase64(primitive.content)) {
                    errors += ValidationError(path, "not valid base64 (standard alphabet, with padding)")
                }
            }
            PrimitiveKind.TIMESTAMP -> {
                if (primitive == null || !primitive.isString) {
                    errors += kindError(path, "a timestamp string", value)
                } else if (!isTimestamp(primitive.content)) {
                    errors += ValidationError(path, "not a valid timestamp, expected YYYY-MM-DDThh:mm:ss.mmmZ (UTC, three fractional digits)")
                }
            }
        }
    }

    private fun validateInt(value: JsonElement, primitive: JsonPrimitive?, path: String, errors: MutableList<ValidationError>) {
        if (primitive == null || primitive.isString || !numberPattern.matches(primitive.content)) {
            errors += kindError(path, "an integer", value)
        } else if (!integerPattern.matches(primitive.content)) {
            errors += ValidationError(path, "must be an integer without fraction or exponent, got ${primitive.content}")
        } else if (primitive.content.toLongOrNull() == null) {
            errors += ValidationError(path, "out of the signed 64-bit range: ${primitive.content}")
        }
    }

    private fun validateDouble(value: JsonElement, primitive: JsonPrimitive?, path: String, errors: MutableList<ValidationError>) {
        if (primitive == null || primitive.isString || !numberPattern.matches(primitive.content)) {
            errors += kindError(path, "a number", value)
        } else if (!primitive.content.toDouble().isFinite()) {
            errors += ValidationError(path, "not a finite number: ${primitive.content}")
        }
    }

    private fun isCanonicalBase64(text: String): Boolean = try {
        Base64.getEncoder().encodeToString(Base64.getDecoder().decode(text)) == text
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun isTimestamp(text: String): Boolean {
        if (!text.endsWith("Z")) return false
        return try {
            LocalDateTime.parse(text.dropLast(1), timestampFormat)
            true
        } catch (e: DateTimeParseException) {
            false
        }
    }

    private fun kindError(path: String, expected: String, actual: JsonElement): ValidationError =
        ValidationError(path, "expected $expected, got ${describe(actual)}")

    private fun describe(value: JsonElement): String = when {
        value is JsonNull -> "null"
        value is JsonObject -> "an object"
        value is JsonArray -> "an array"
        value is JsonPrimitive && value.isString -> "a string"
        value is JsonPrimitive && (value.content == "true" || value.content == "false") -> "a boolean"
        else -> "a number"
    }

    private fun child(path: String, key: String): String =
        if (simpleKey.matches(key)) "$path.$key" else "$path[\"${key.replace("\\", "\\\\").replace("\"", "\\\"")}\"]"

    private companion object {
        val numberPattern = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        val integerPattern = Regex("-?(0|[1-9][0-9]*)")
        val simpleKey = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val timestampFormat: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS").withResolverStyle(ResolverStyle.STRICT)
    }
}
