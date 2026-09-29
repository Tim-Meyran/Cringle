// SPDX-License-Identifier: Apache-2.0

package cringle.schema

import cringle.contract.SchemaRef

/**
 * The standard types of the reserved namespace `cringle.std`. Primitives are ordinary named types, so ports can
 * refer to them like to any other schema. Every [SchemaRegistry] contains them.
 */
public object StandardSchemas {
    /** The reserved namespace of the standard types. */
    public const val NAMESPACE: String = "cringle.std"

    /** A string. */
    public val STRING: SchemaRef = SchemaRef(NAMESPACE, "String")

    /** `true` or `false`. */
    public val BOOLEAN: SchemaRef = SchemaRef(NAMESPACE, "Boolean")

    /** A signed 64-bit integer. */
    public val INT: SchemaRef = SchemaRef(NAMESPACE, "Int")

    /** A finite binary64 number. */
    public val DOUBLE: SchemaRef = SchemaRef(NAMESPACE, "Double")

    /** Bytes, written as base64. */
    public val BYTES: SchemaRef = SchemaRef(NAMESPACE, "Bytes")

    /** A point in time in UTC with millisecond precision. */
    public val TIMESTAMP: SchemaRef = SchemaRef(NAMESPACE, "Timestamp")

    /** A record without fields, for messages that carry no data. */
    public val EMPTY: SchemaRef = SchemaRef(NAMESPACE, "Empty")

    /** An error: `code` and `message`, optionally `details` (a map of strings). */
    public val ERROR: SchemaRef = SchemaRef(NAMESPACE, "Error")

    /** The document that describes the standard types. */
    public val document: SchemaDocument = SchemaDocument(
        origin = "built-in",
        namespace = NAMESPACE,
        types = linkedMapOf(
            "String" to TypeDef.Primitive(PrimitiveKind.STRING),
            "Boolean" to TypeDef.Primitive(PrimitiveKind.BOOLEAN),
            "Int" to TypeDef.Primitive(PrimitiveKind.INT),
            "Double" to TypeDef.Primitive(PrimitiveKind.DOUBLE),
            "Bytes" to TypeDef.Primitive(PrimitiveKind.BYTES),
            "Timestamp" to TypeDef.Primitive(PrimitiveKind.TIMESTAMP),
            "Empty" to TypeDef.Record(emptyMap()),
            "Error" to TypeDef.Record(
                linkedMapOf(
                    "code" to TypeExpr.Ref(STRING),
                    "message" to TypeExpr.Ref(STRING),
                    "details" to TypeExpr.Optional(TypeExpr.MapOf(TypeExpr.Ref(STRING))),
                ),
            ),
        ),
    )
}
