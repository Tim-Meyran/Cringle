// SPDX-License-Identifier: Apache-2.0

package cringle.schema

import cringle.contract.SchemaRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StandardSchemasTest {
    @Test
    fun everyRegistryContainsTheEightStandardTypes() {
        val registry = SchemaRegistry()
        val expected = mapOf(
            StandardSchemas.STRING to PrimitiveKind.STRING,
            StandardSchemas.BOOLEAN to PrimitiveKind.BOOLEAN,
            StandardSchemas.INT to PrimitiveKind.INT,
            StandardSchemas.DOUBLE to PrimitiveKind.DOUBLE,
            StandardSchemas.BYTES to PrimitiveKind.BYTES,
            StandardSchemas.TIMESTAMP to PrimitiveKind.TIMESTAMP,
        )
        for ((ref, kind) in expected) assertEquals(TypeDef.Primitive(kind), registry.resolve(ref), "type $ref")
        assertEquals(TypeDef.Record(emptyMap()), registry.resolve(StandardSchemas.EMPTY))
        val error = registry.resolve(StandardSchemas.ERROR) as TypeDef.Record
        assertEquals(listOf("code", "message", "details"), error.fields.keys.toList())
        assertEquals(TypeExpr.Optional(TypeExpr.MapOf(TypeExpr.Ref(StandardSchemas.STRING))), error.fields.getValue("details"))
        assertEquals(8, StandardSchemas.document.types.size)
        assertEquals("cringle.std/String", StandardSchemas.STRING.toString())
        assertEquals(emptyList<SchemaProblem>(), registry.problems())
    }

    @Test
    fun theStandardNamespaceIsReservedForDocuments() {
        val error = org.junit.jupiter.api.assertThrows<SchemaParseException> {
            SchemaParser.parse("""{"namespace":"cringle.std","types":{"Mine":{"record":{}}}}""", "x")
        }
        assertEquals("$.namespace", error.path)
    }

    @Test
    fun assignabilityIsNominal() {
        val registry = SchemaRegistry()
        registry.add(SchemaParser.parse("""{"namespace":"a","types":{"A":{"record":{"x":"cringle.std/Int"}},"B":{"record":{"x":"cringle.std/Int"}}}}""", "d"))
        val a = SchemaRef("a", "A")
        val b = SchemaRef("a", "B")

        assertTrue(isAssignable(a, a, registry))
        assertTrue(isAssignable(StandardSchemas.STRING, StandardSchemas.STRING, registry))
        // structurally identical, but different namespace IDs
        assertFalse(isAssignable(a, b, registry))
        assertFalse(isAssignable(StandardSchemas.INT, StandardSchemas.DOUBLE, registry))
    }

    @Test
    fun anUnresolvableReferenceIsNeverAssignable() {
        val registry = SchemaRegistry()
        val ghost = SchemaRef("nope", "Ghost")
        assertFalse(isAssignable(ghost, ghost, registry))
        assertFalse(isAssignable(ghost, StandardSchemas.STRING, registry))
        assertFalse(isAssignable(StandardSchemas.STRING, ghost, registry))
        assertNotNull(registry.resolve(StandardSchemas.STRING))
    }
}
