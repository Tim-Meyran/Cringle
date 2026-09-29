// SPDX-License-Identifier: Apache-2.0

package cringle.schema

import cringle.contract.SchemaRef
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The worked examples of `spec/schema.md`, checked against the implementation. */
class SpecExamplesTest {
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

    private val registry = SchemaRegistry().also { it.add(SchemaParser.parse(ordersDocument, "spec")) }
    private val order = SchemaRef("acme.orders", "Order")

    @Test
    fun example82ValidValueAndItsCanonicalForm() {
        val value = Json.parseToJsonElement(
            """{ "labels": {"vip": "yes"}, "id": "o-1", "status": "NEW",
                 "items": [ {"sku": "a", "quantity": 1}, {"quantity": 9007199254740993, "sku": "b"} ] }""",
        )
        assertEquals(emptyList<ValidationError>(), SchemaValidator(registry).validate(value, order))
        assertEquals(
            """{"id":"o-1","items":[{"quantity":1,"sku":"a"},{"quantity":9007199254740993,"sku":"b"}],"labels":{"vip":"yes"},"status":"NEW"}""",
            CanonicalJson.encode(value),
        )
    }

    @Test
    fun example82InvalidValueAndItsErrors() {
        val value = Json.parseToJsonElement(
            """{ "id": "o-1", "status": "PAYED",
                 "items": [ {"sku": "a", "quantity": 1}, {"sku": "b", "quantity": 2}, {"sku": 7, "quantity": "3"} ],
                 "labels": {"vip": 1} }""",
        )
        val errors = SchemaValidator(registry).validate(value, order)
        assertEquals(listOf("$.status", "$.items[2].sku", "$.items[2].quantity", "$.labels.vip"), errors.map { it.path })
    }

    @Test
    fun example82FurtherExamples() {
        val validator = SchemaValidator(registry)
        fun check(json: String, type: SchemaRef) = validator.validate(Json.parseToJsonElement(json), type)
        assertEquals(listOf("$"), check("""{"id":"o"}""", order).map { it.path }.distinct())
        assertEquals(
            listOf("$.comment"),
            check("""{"id":"o","status":"NEW","items":[],"labels":{},"comment":null}""", order).map { it.path },
        )
        assertEquals(emptyList<ValidationError>(), check("""{"code":"E1","message":"boom"}""", StandardSchemas.ERROR))
        assertEquals(1, check("\"2026-09-29T10:15:30Z\"", StandardSchemas.TIMESTAMP).size)
        assertEquals(0, check("\"2026-09-29T10:15:30.123Z\"", StandardSchemas.TIMESTAMP).size)
    }

    @Test
    fun example83FixingTheDocumentOneErrorAtATime() {
        fun pathOf(document: String) = assertThrows<SchemaParseException> { SchemaParser.parse(document, "spec") }.path

        val task = """"Task": { "record": { "tags": { "list": { "optional": "cringle.std/String" } } } }"""
        fun document(namespace: String, typeName: String, level: String) =
            """{"namespace":"$namespace","types":{"$typeName":{"record":{}},"Level":{"enum":$level},$task}}"""

        assertEquals("$.namespace", pathOf(document("Acme", "order", """["LOW","HIGH","LOW"]""")))
        assertEquals("$.types.order", pathOf(document("acme", "order", """["LOW","HIGH","LOW"]""")))
        assertEquals("$.types.Level.enum[2]", pathOf(document("acme", "Order", """["LOW","HIGH","LOW"]""")))
        assertEquals("$.types.Task.record.tags.list", pathOf(document("acme", "Order", """["LOW","HIGH"]""")))
    }
}
