// SPDX-License-Identifier: Apache-2.0

package cringle.schema

import cringle.contract.SchemaRef
import java.util.Collections

/**
 * A type expression: what the type of a record field is. Every reference is fully qualified; the parser
 * qualifies unqualified names with the namespace of their document.
 */
public sealed interface TypeExpr {
    /** A reference to a named type. */
    public data class Ref(public val ref: SchemaRef) : TypeExpr

    /** A list of [element] values. */
    public data class ListOf(public val element: TypeExpr) : TypeExpr

    /** A map from string keys to [value]s. */
    public data class MapOf(public val value: TypeExpr) : TypeExpr

    /** A record field that may be absent. Only allowed as the outermost expression of a record field. */
    public data class Optional(public val inner: TypeExpr) : TypeExpr
}

/** The primitive types of the standard schema `cringle.std`. Primitives cannot be defined in documents. */
public enum class PrimitiveKind {
    /** A string. */
    STRING,

    /** `true` or `false`. */
    BOOLEAN,

    /** A signed 64-bit integer. */
    INT,

    /** A finite IEEE 754 binary64 number. */
    DOUBLE,

    /** Bytes, written as base64 (RFC 4648 section 4, with padding). */
    BYTES,

    /** A point in time, written as `YYYY-MM-DDThh:mm:ss.mmmZ` (UTC, exactly three fractional digits). */
    TIMESTAMP,
}

/** The definition of a named type. */
public sealed interface TypeDef {
    /** A primitive type. */
    public data class Primitive(public val kind: PrimitiveKind) : TypeDef

    /**
     * An enumeration.
     *
     * @property values the declared values, in declaration order, without duplicates. Immutable.
     */
    public class Enum(values: List<String>) : TypeDef {
        public val values: List<String> = Collections.unmodifiableList(ArrayList(values))

        override fun equals(other: Any?): Boolean = other is Enum && values == other.values

        override fun hashCode(): Int = values.hashCode()

        override fun toString(): String = "Enum($values)"
    }

    /**
     * A record.
     *
     * @property fields the fields by name, in declaration order. Immutable.
     */
    public class Record(fields: Map<String, TypeExpr>) : TypeDef {
        public val fields: Map<String, TypeExpr> = Collections.unmodifiableMap(LinkedHashMap(fields))

        override fun equals(other: Any?): Boolean = other is Record && fields == other.fields

        override fun hashCode(): Int = fields.hashCode()

        override fun toString(): String = "Record($fields)"
    }
}

/**
 * A parsed schema document: one namespace with its named types.
 *
 * @property origin where the document came from (a file name, a package, ...), used in error messages.
 * @property namespace the namespace ID of all types of this document.
 * @property types the named types in declaration order. Immutable.
 */
public class SchemaDocument(
    public val origin: String,
    public val namespace: String,
    types: Map<String, TypeDef>,
) {
    public val types: Map<String, TypeDef> = Collections.unmodifiableMap(LinkedHashMap(types))

    override fun equals(other: Any?): Boolean =
        other is SchemaDocument && origin == other.origin && namespace == other.namespace && types == other.types

    override fun hashCode(): Int = listOf(origin, namespace, types).hashCode()

    override fun toString(): String = "SchemaDocument(origin=$origin, namespace=$namespace, types=${types.keys})"
}
