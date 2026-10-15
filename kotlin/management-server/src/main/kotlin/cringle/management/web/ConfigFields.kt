// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.BlockDefinition
import cringle.schema.PrimitiveKind
import cringle.schema.SchemaRegistry
import cringle.schema.TypeDef
import cringle.schema.TypeExpr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The fields of the configuration of a block, as the form of the blueprint editor needs them: the record type of its `configSchema` turned into a flat list.
 * A field is a text, a whole number, a number, a boolean or an enum; everything else (a list, a map, a record, `Any`, `Bytes`, `Timestamp`) is `json`,
 * which the form shows as a small text area that has to hold valid JSON.
 */
internal object ConfigFields {
    /** The fields of [definition]; empty for a block without a configuration type, or when the type is no record or does not resolve. */
    fun of(definition: BlockDefinition, registry: SchemaRegistry): JsonArray {
        val ref = definition.configSchema ?: return JsonArray(emptyList())
        val record = registry.resolve(ref) as? TypeDef.Record ?: return JsonArray(emptyList())
        return JsonArray(record.fields.map { (name, expression) -> field(name, expression, registry) })
    }

    private fun field(name: String, expression: TypeExpr, registry: SchemaRegistry): JsonObject {
        val optional = expression is TypeExpr.Optional
        val inner = (expression as? TypeExpr.Optional)?.inner ?: expression
        var kind = "json"
        var values: List<String> = emptyList()
        var constrained: TypeDef.Constrained? = null
        // a list of texts is one name per line (the names of the slots of a block, for example)
        val listElement = (inner as? TypeExpr.ListOf)?.element as? TypeExpr.Ref
        if (listElement != null) {
            when (val element = registry.resolve(listElement.ref)) {
                is TypeDef.Primitive -> if (element.kind == PrimitiveKind.STRING) kind = "stringlist"
                is TypeDef.Constrained -> if (element.base == PrimitiveKind.STRING) {
                    kind = "stringlist"
                    constrained = element
                }
                else -> {}
            }
        }
        if (inner is TypeExpr.Ref) {
            when (val definition = registry.resolve(inner.ref)) {
                is TypeDef.Constrained -> {
                    constrained = definition
                    kind = when (definition.base) {
                        PrimitiveKind.STRING -> "string"
                        PrimitiveKind.INT -> "int"
                        else -> "double"
                    }
                }
                is TypeDef.Primitive -> kind = when (definition.kind) {
                    PrimitiveKind.STRING -> "string"
                    PrimitiveKind.INT -> "int"
                    PrimitiveKind.DOUBLE -> "double"
                    PrimitiveKind.BOOLEAN -> "boolean"
                    else -> "json"
                }
                is TypeDef.Enum -> {
                    kind = "enum"
                    values = definition.values
                }
                else -> kind = "json"
            }
        }
        return buildJsonObject {
            put("name", name)
            put("kind", kind)
            put("optional", optional)
            if (values.isNotEmpty()) put("values", JsonArray(values.map { JsonPrimitive(it) }))
            constrained?.let { c ->
                c.pattern?.let { put("pattern", it) }
                c.minLength?.let { put("minLength", it) }
                c.maxLength?.let { put("maxLength", it) }
                c.minimum?.let { put("minimum", it) }
                c.maximum?.let { put("maximum", it) }
                c.description?.let { put("description", it) }
            }
        }
    }
}
