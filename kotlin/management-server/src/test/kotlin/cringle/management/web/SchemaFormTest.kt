// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.schema.SchemaParseException
import cringle.schema.SchemaParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SchemaFormTest {
    private val model = """
        {"namespace": "acme.orders", "types": [
          {"name": "Color", "kind": "enum", "values": "RED, GREEN", "fields": []},
          {"name": "Order", "kind": "record", "values": "", "fields": [
            {"name": "id", "type": "cringle.std/String", "wrap": ""},
            {"name": "lines", "type": "Line", "wrap": "list"},
            {"name": "note", "type": "cringle.std/String", "wrap": "optional"},
            {"name": "tags", "type": "cringle.std/String", "wrap": "map"},
            {"name": "color", "type": "Color", "wrap": ""}]},
          {"name": "Line", "kind": "record", "values": "", "fields": [{"name": "sku", "type": "cringle.std/String", "wrap": ""}]}]}
    """.trimIndent()

    @Test
    fun theFormBecomesAValidDocumentAndComesBack() {
        val document = SchemaForm.toDocument(model)
        SchemaParser.parse(Json.encodeToString(JsonObject.serializer(), document), "test")
        assertEquals("""{"list":"Line"}""", document["types"]!!.let { (it as JsonObject)["Order"] as JsonObject }.let { ((it["record"] as JsonObject)["lines"]).toString() })
        // back to the form and to the document again: nothing is lost
        assertEquals(document, SchemaForm.toDocument(SchemaForm.toModel(document).toString()))
    }

    @Test
    fun problemsAreFoundByTheSchemaParserWithTheirPath() {
        val bad = SchemaForm.toDocument("""{"namespace": "acme.orders", "types": [{"name": "order", "kind": "record", "fields": []}]}""")
        val e = assertThrows<SchemaParseException> { SchemaParser.parse(Json.encodeToString(JsonObject.serializer(), bad), "test") }
        assertEquals("$.types.order", e.path)
    }

    @Test
    fun duplicatesAndGarbageAreRefused() {
        assertThrows<IllegalArgumentException> { SchemaForm.toDocument("""{"namespace": "a", "types": [{"name": "A", "kind": "enum", "values": "X"}, {"name": "A", "kind": "enum", "values": "Y"}]}""") }
        assertThrows<IllegalArgumentException> { SchemaForm.toDocument("""{"namespace": "a", "types": [{"name": "A", "kind": "record", "fields": [{"name": "f", "type": "A"}, {"name": "f", "type": "A"}]}]}""") }
        assertThrows<IllegalArgumentException> { SchemaForm.toDocument("not json") }
    }
}
