// SPDX-License-Identifier: Apache-2.0

package cringle.schema

import cringle.contract.SchemaRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SchemaParserTest {
    private val ordersDocument = """
        {
          "namespace": "acme.orders",
          "types": {
            "Status": { "enum": ["NEW", "PAID", "SHIPPED"] },
            "Item":   { "record": { "sku": "cringle.std/String", "quantity": "cringle.std/Int" } },
            "Order":  { "record": {
              "id": "cringle.std/String",
              "status": "Status",
              "items": { "list": "Item" },
              "labels": { "map": "cringle.std/String" },
              "comment": { "optional": "cringle.std/String" }
            } }
          }
        }
    """.trimIndent()

    private fun parse(text: String) = SchemaParser.parse(text, "test.json")

    /** Wraps [record] as the single field `f` of type `A` in namespace `a`. */
    private fun withField(expression: String) =
        """{"namespace":"a","types":{"A":{"record":{"f":$expression}}}}"""

    private fun assertInvalid(text: String, path: String, fragment: String) {
        val error = assertThrows<SchemaParseException> { parse(text) }
        assertEquals(path, error.path, "path of: ${error.message}")
        assertTrue(fragment in error.message.orEmpty(), "expected '$fragment' in: ${error.message}")
        assertTrue(error.message.orEmpty().startsWith(path), "message starts with the path: ${error.message}")
    }

    @Test
    fun parsesTheExampleDocument() {
        val document = parse(ordersDocument)

        assertEquals("acme.orders", document.namespace)
        assertEquals("test.json", document.origin)
        assertEquals(listOf("Status", "Item", "Order"), document.types.keys.toList())
        assertEquals(listOf("NEW", "PAID", "SHIPPED"), (document.types.getValue("Status") as TypeDef.Enum).values)

        val order = document.types.getValue("Order") as TypeDef.Record
        assertEquals(listOf("id", "status", "items", "labels", "comment"), order.fields.keys.toList())
        // unqualified names are qualified with the namespace of the document
        assertEquals(TypeExpr.Ref(SchemaRef("acme.orders", "Status")), order.fields.getValue("status"))
        assertEquals(TypeExpr.ListOf(TypeExpr.Ref(SchemaRef("acme.orders", "Item"))), order.fields.getValue("items"))
        assertEquals(TypeExpr.MapOf(TypeExpr.Ref(StandardSchemas.STRING)), order.fields.getValue("labels"))
        assertEquals(TypeExpr.Optional(TypeExpr.Ref(StandardSchemas.STRING)), order.fields.getValue("comment"))
    }

    @Test
    fun emptyTypesAndEmptyRecordsAreAllowed() {
        assertEquals(emptyMap<String, TypeDef>(), parse("""{"namespace":"a","types":{}}""").types)
        val record = parse("""{"namespace":"a","types":{"A":{"record":{}}}}""").types.getValue("A") as TypeDef.Record
        assertTrue(record.fields.isEmpty())
    }

    @Test
    fun rejectsMalformedJsonAndWrongRoot() {
        assertInvalid("{", "$", "malformed JSON")
        assertInvalid("[]", "$", "must be a JSON object")
        assertInvalid("", "$", "malformed JSON")
    }

    @Test
    fun rejectsMissingAndUnknownTopLevelKeys() {
        assertInvalid("""{"types":{}}""", "$", "missing key 'namespace'")
        assertInvalid("""{"namespace":"a"}""", "$", "missing key 'types'")
        assertInvalid("""{"namespace":"a","types":{},"extra":1}""", "$.extra", "unknown key 'extra'")
        assertInvalid("""{"namespace":"a","types":[]}""", "$.types", "must be an object")
    }

    @Test
    fun rejectsBadNamespaces() {
        assertInvalid("""{"namespace":"Acme","types":{}}""", "$.namespace", "invalid namespace 'Acme'")
        assertInvalid("""{"namespace":"a..b","types":{}}""", "$.namespace", "invalid namespace")
        assertInvalid("""{"namespace":"1a","types":{}}""", "$.namespace", "invalid namespace")
        assertInvalid("""{"namespace":5,"types":{}}""", "$.namespace", "must be a string")
        assertInvalid("""{"namespace":"cringle.std","types":{}}""", "$.namespace", "reserved")
    }

    @Test
    fun rejectsBadTypeNamesFieldNamesAndEnumValues() {
        assertInvalid("""{"namespace":"a","types":{"order":{"record":{}}}}""", "$.types.order", "invalid type name 'order'")
        assertInvalid("""{"namespace":"a","types":{"A":{"record":{"Bad":"B"}}}}""", "$.types.A.record.Bad", "invalid field name 'Bad'")
        assertInvalid("""{"namespace":"a","types":{"A":{"enum":["x"]}}}""", "$.types.A.enum[0]", "invalid enum value 'x'")
        assertInvalid("""{"namespace":"a","types":{"A":{"enum":[1]}}}""", "$.types.A.enum[0]", "must be a string")
    }

    @Test
    fun rejectsBadTypeDefinitions() {
        assertInvalid("""{"namespace":"a","types":{"A":{}}}""", "$.types.A", "needs either 'enum' or 'record'")
        assertInvalid("""{"namespace":"a","types":{"A":{"enum":["X"],"record":{}}}}""", "$.types.A", "not both")
        assertInvalid("""{"namespace":"a","types":{"A":{"enu":["X"]}}}""", "$.types.A.enu", "unknown key 'enu'")
        assertInvalid("""{"namespace":"a","types":{"A":"enum"}}""", "$.types.A", "must be an object")
        assertInvalid("""{"namespace":"a","types":{"A":{"enum":[]}}}""", "$.types.A.enum", "must not be empty")
        assertInvalid("""{"namespace":"a","types":{"A":{"enum":"X"}}}""", "$.types.A.enum", "must be an array")
        assertInvalid("""{"namespace":"a","types":{"A":{"enum":["X","Y","X"]}}}""", "$.types.A.enum[2]", "duplicate enum value 'X'")
        assertInvalid("""{"namespace":"a","types":{"A":{"record":[]}}}""", "$.types.A.record", "must be an object")
    }

    @Test
    fun rejectsBadTypeExpressions() {
        assertInvalid(withField("""{"list":{"optional":"X"}}"""), "$.types.A.record.f.list", "only allowed as the outermost")
        assertInvalid(withField("""{"map":{"optional":"X"}}"""), "$.types.A.record.f.map", "only allowed as the outermost")
        assertInvalid(withField("""{"optional":{"optional":"X"}}"""), "$.types.A.record.f.optional", "only allowed as the outermost")
        assertInvalid(withField("""{"list":"X","map":"Y"}"""), "$.types.A.record.f", "exactly one key")
        assertInvalid(withField("""{}"""), "$.types.A.record.f", "exactly one key")
        assertInvalid(withField("""{"set":"X"}"""), "$.types.A.record.f.set", "unknown type expression 'set'")
        assertInvalid(withField("5"), "$.types.A.record.f", "must be a string or an object")
        assertInvalid(withField("""["X"]"""), "$.types.A.record.f", "must be a string or an object")
    }

    @Test
    fun rejectsBadReferences() {
        assertInvalid(withField("\"lower\""), "$.types.A.record.f", "invalid type reference 'lower'")
        assertInvalid(withField("\"a/b\""), "$.types.A.record.f", "invalid type reference 'a/b'")
        assertInvalid(withField("\"Big/Name\""), "$.types.A.record.f", "invalid type reference 'Big/Name'")
        assertInvalid(withField("\"\""), "$.types.A.record.f", "invalid type reference ''")
    }

    @Test
    fun acceptsOptionalAtTheTopOfAFieldWithWrappedContent() {
        val document = parse(withField("""{"optional":{"list":{"map":"cringle.std/Int"}}}"""))
        val field = (document.types.getValue("A") as TypeDef.Record).fields.getValue("f")
        assertEquals(
            TypeExpr.Optional(TypeExpr.ListOf(TypeExpr.MapOf(TypeExpr.Ref(StandardSchemas.INT)))),
            field,
        )
    }

    @Test
    fun rejectsDuplicateKeys() {
        assertInvalid(
            """{"namespace":"a","types":{"A":{"enum":["X"]},"A":{"enum":["Y"]}}}""",
            "$.types",
            "duplicate key 'A'",
        )
        assertInvalid(
            """{"namespace":"a","types":{"A":{"record":{"f":"B","f":"C"}}}}""",
            "$.types.A.record",
            "duplicate key 'f'",
        )
        assertInvalid("""{"namespace":"a","namespace":"b","types":{}}""", "$", "duplicate key 'namespace'")
        // the same key in different objects is fine
        parse("""{"namespace":"a","types":{"A":{"record":{"f":"B"}},"B":{"record":{"f":"A"}}}}""")
    }

    @Test
    fun duplicateKeyDetectionHandlesEscapesAndArrays() {
        assertEquals("$" to "a", JsonDuplicateKeys.find("""{"a":1,"a":2}"""))
        assertEquals("$.k[1]" to "x", JsonDuplicateKeys.find("""{"k":[{"x":1},{"x":1,"x":2}]}"""))
        assertEquals("$" to "a\"b", JsonDuplicateKeys.find("""{"a\"b":1,"a\"b":2}"""))
        assertEquals("$" to "A", JsonDuplicateKeys.find("""{"A":1,"A":2}"""))
        assertEquals(null, JsonDuplicateKeys.find("""{"a":"a","b":["a","a"],"c":{"a":1}}"""))
    }

    @Test
    fun modelObjectsAreImmutableCopies() {
        val fields = linkedMapOf<String, TypeExpr>("f" to TypeExpr.Ref(StandardSchemas.STRING))
        val record = TypeDef.Record(fields)
        fields.clear()
        assertEquals(setOf("f"), record.fields.keys)

        val values = mutableListOf("A")
        val enumeration = TypeDef.Enum(values)
        values += "B"
        assertEquals(listOf("A"), enumeration.values)
        assertEquals(TypeDef.Enum(listOf("A")), enumeration)
    }
}
