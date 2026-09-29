// SPDX-License-Identifier: Apache-2.0

package cringle.schema

import cringle.contract.SchemaRef
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Thrown when a schema document is invalid.
 *
 * @property path the JSON path of the problem, for example `$.types.Order.record.id`.
 */
public class SchemaParseException(public val path: String, problem: String) : RuntimeException("$path: $problem")

/**
 * Parses schema documents. **[Zu bestätigen]** The format is a proposal, see `spec/schema.md`.
 *
 * A document is a JSON object with exactly the keys `namespace` and `types`. Parsing stops at the first
 * problem and reports it with the JSON path of the offending element.
 */
public object SchemaParser {
    private val namespacePattern = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*")
    private val typeNamePattern = Regex("[A-Z][A-Za-z0-9]*")
    private val fieldNamePattern = Regex("[a-z][A-Za-z0-9]*")
    private val enumValuePattern = Regex("[A-Z][A-Z0-9_]*")
    private val wrapperKeys = setOf("list", "map", "optional")

    /**
     * Parses [text] into a [SchemaDocument].
     *
     * @param origin where the text came from, kept in the document and used in later error messages.
     * @throws SchemaParseException if the text is not a valid schema document.
     */
    public fun parse(text: String, origin: String): SchemaDocument {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw SchemaParseException("$", "malformed JSON: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        JsonDuplicateKeys.find(text)?.let { (path, key) -> throw SchemaParseException(path, "duplicate key '$key'") }

        val document = root as? JsonObject ?: throw SchemaParseException("$", "a schema document must be a JSON object")
        for (key in document.keys) {
            if (key != "namespace" && key != "types") throw SchemaParseException("$.$key", "unknown key '$key'")
        }
        val namespace = parseNamespace(document)
        val typesJson = document["types"] ?: throw SchemaParseException("$", "missing key 'types'")
        val typesObject = typesJson as? JsonObject ?: throw SchemaParseException("$.types", "'types' must be an object")

        val types = LinkedHashMap<String, TypeDef>()
        for ((name, definition) in typesObject) {
            val path = "$.types.$name"
            if (!typeNamePattern.matches(name)) {
                throw SchemaParseException(path, "invalid type name '$name': expected ${typeNamePattern.pattern}")
            }
            types[name] = parseDefinition(definition, path, namespace)
        }
        return SchemaDocument(origin, namespace, types)
    }

    private fun parseNamespace(document: JsonObject): String {
        val json = document["namespace"] ?: throw SchemaParseException("$", "missing key 'namespace'")
        val namespace = (json as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw SchemaParseException("$.namespace", "'namespace' must be a string")
        if (!namespacePattern.matches(namespace)) {
            throw SchemaParseException("$.namespace", "invalid namespace '$namespace': expected ${namespacePattern.pattern}")
        }
        if (namespace == StandardSchemas.NAMESPACE) {
            throw SchemaParseException("$.namespace", "the namespace '$namespace' is reserved for the standard types")
        }
        return namespace
    }

    private fun parseDefinition(json: JsonElement, path: String, namespace: String): TypeDef {
        val definition = json as? JsonObject
            ?: throw SchemaParseException(path, "a type definition must be an object with either 'enum' or 'record'")
        for (key in definition.keys) {
            if (key != "enum" && key != "record") throw SchemaParseException("$path.$key", "unknown key '$key'")
        }
        return when {
            "enum" in definition && "record" in definition ->
                throw SchemaParseException(path, "a type definition has either 'enum' or 'record', not both")
            "enum" in definition -> parseEnum(definition.getValue("enum"), "$path.enum")
            "record" in definition -> parseRecord(definition.getValue("record"), "$path.record", namespace)
            else -> throw SchemaParseException(path, "a type definition needs either 'enum' or 'record'")
        }
    }

    private fun parseEnum(json: JsonElement, path: String): TypeDef.Enum {
        val array = json as? JsonArray ?: throw SchemaParseException(path, "'enum' must be an array of strings")
        if (array.isEmpty()) throw SchemaParseException(path, "'enum' must not be empty")
        val values = LinkedHashSet<String>()
        array.forEachIndexed { index, element ->
            val valuePath = "$path[$index]"
            val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw SchemaParseException(valuePath, "an enum value must be a string")
            if (!enumValuePattern.matches(value)) {
                throw SchemaParseException(valuePath, "invalid enum value '$value': expected ${enumValuePattern.pattern}")
            }
            if (!values.add(value)) throw SchemaParseException(valuePath, "duplicate enum value '$value'")
        }
        return TypeDef.Enum(values.toList())
    }

    private fun parseRecord(json: JsonElement, path: String, namespace: String): TypeDef.Record {
        val record = json as? JsonObject
            ?: throw SchemaParseException(path, "'record' must be an object from field names to type expressions")
        val fields = LinkedHashMap<String, TypeExpr>()
        for ((name, expression) in record) {
            val fieldPath = "$path.$name"
            if (!fieldNamePattern.matches(name)) {
                throw SchemaParseException(fieldPath, "invalid field name '$name': expected ${fieldNamePattern.pattern}")
            }
            fields[name] = parseExpression(expression, fieldPath, namespace, optionalAllowed = true)
        }
        return TypeDef.Record(fields)
    }

    private fun parseExpression(json: JsonElement, path: String, namespace: String, optionalAllowed: Boolean): TypeExpr {
        if (json is JsonPrimitive) {
            if (!json.isString) throw SchemaParseException(path, "a type expression must be a string or an object")
            return TypeExpr.Ref(parseReference(json.content, path, namespace))
        }
        val wrapper = json as? JsonObject ?: throw SchemaParseException(path, "a type expression must be a string or an object")
        if (wrapper.size != 1) {
            throw SchemaParseException(path, "a type expression object has exactly one key of ${wrapperKeys.joinToString()}")
        }
        val (key, inner) = wrapper.entries.single()
        if (key !in wrapperKeys) {
            throw SchemaParseException("$path.$key", "unknown type expression '$key': expected one of ${wrapperKeys.joinToString()}")
        }
        val innerPath = "$path.$key"
        return when (key) {
            "list" -> TypeExpr.ListOf(parseExpression(inner, innerPath, namespace, optionalAllowed = false))
            "map" -> TypeExpr.MapOf(parseExpression(inner, innerPath, namespace, optionalAllowed = false))
            else -> {
                if (!optionalAllowed) {
                    throw SchemaParseException(path, "'optional' is only allowed as the outermost type of a record field")
                }
                TypeExpr.Optional(parseExpression(inner, innerPath, namespace, optionalAllowed = false))
            }
        }
    }

    private fun parseReference(text: String, path: String, namespace: String): SchemaRef {
        val slash = text.lastIndexOf('/')
        if (slash < 0) {
            if (!typeNamePattern.matches(text)) {
                throw SchemaParseException(path, "invalid type reference '$text': expected ${typeNamePattern.pattern} or namespace/Name")
            }
            return SchemaRef(namespace, text)
        }
        val referencedNamespace = text.substring(0, slash)
        val name = text.substring(slash + 1)
        if (!namespacePattern.matches(referencedNamespace) || !typeNamePattern.matches(name)) {
            throw SchemaParseException(path, "invalid type reference '$text': expected namespace/Name")
        }
        return SchemaRef(referencedNamespace, name)
    }
}

/** Finds the first duplicate key of any object in a syntactically valid JSON text. */
internal object JsonDuplicateKeys {
    private class Frame(val path: String, val isObject: Boolean) {
        val keys = HashSet<String>()
        var expectingKey = isObject
        var index = 0
        var valuePath = path
    }

    /** Returns the path of the object with the duplicate and the duplicated key, or `null`. */
    fun find(text: String): Pair<String, String>? {
        val stack = ArrayList<Frame>()
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> {
                    val (value, end) = readString(text, i)
                    val top = stack.lastOrNull()
                    if (top != null && top.isObject && top.expectingKey) {
                        if (!top.keys.add(value)) return top.path to value
                        top.expectingKey = false
                        top.valuePath = "${top.path}.$value"
                    }
                    i = end
                    continue
                }
                '{', '[' -> {
                    val parent = stack.lastOrNull()
                    val path = when {
                        parent == null -> "$"
                        parent.isObject -> parent.valuePath
                        else -> "${parent.path}[${parent.index}]"
                    }
                    stack += Frame(path, c == '{')
                }
                '}', ']' -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                ',' -> stack.lastOrNull()?.let { if (it.isObject) it.expectingKey = true else it.index++ }
            }
            i++
        }
        return null
    }

    /** Reads the JSON string starting at [start] (the opening quote) and returns its value and the end index. */
    private fun readString(text: String, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> return sb.toString() to i + 1
                c == '\\' -> {
                    when (val e = text[i + 1]) {
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            sb.append(text.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(e)
                    }
                    i += 2
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString() to i
    }
}
