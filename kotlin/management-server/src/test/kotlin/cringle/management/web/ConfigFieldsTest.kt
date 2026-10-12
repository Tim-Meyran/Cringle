// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.BlockDefinition
import cringle.contract.SchemaRef
import cringle.schema.SchemaParser
import cringle.schema.SchemaRegistry
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConfigFieldsTest {
    private val registry = SchemaRegistry().also {
        it.add(
            SchemaParser.parse(
                """
                {
                  "namespace": "acme.cfg",
                  "types": {
                    "Mode": { "enum": ["FAST", "SAFE"] },
                    "Point": { "record": { "x": "cringle.std/Int" } },
                    "Config": {
                      "record": {
                        "name": "cringle.std/String",
                        "count": "cringle.std/Int",
                        "ratio": { "optional": "cringle.std/Double" },
                        "flag": { "optional": "cringle.std/Boolean" },
                        "mode": "Mode",
                        "note": { "optional": "cringle.std/String" },
                        "tags": { "list": "cringle.std/String" },
                        "labels": { "map": "cringle.std/String" },
                        "origin": "Point",
                        "any": "cringle.std/Any",
                        "blob": "cringle.std/Bytes",
                        "when": { "optional": "cringle.std/Timestamp" }
                      }
                    },
                    "NotARecord": { "enum": ["A"] }
                  }
                }
                """.trimIndent(),
                "cfg.json",
            ),
        )
    }

    private fun block(config: SchemaRef?) = BlockDefinition("b", emptyList(), emptyList(), emptyList(), config)

    @Test
    fun theFieldsOfARecordAreFlat() {
        val fields = ConfigFields.of(block(SchemaRef("acme.cfg", "Config")), registry).map { it.jsonObject }
        val byName = fields.associateBy { it.getValue("name").jsonPrimitive.content }
        assertEquals(
            listOf("name", "count", "ratio", "flag", "mode", "note", "tags", "labels", "origin", "any", "blob", "when"),
            fields.map { it.getValue("name").jsonPrimitive.content },
            "in the order of the schema",
        )
        fun kind(name: String) = byName.getValue(name).getValue("kind").jsonPrimitive.content
        fun optional(name: String) = byName.getValue(name).getValue("optional").jsonPrimitive.content.toBoolean()
        assertEquals("string", kind("name"))
        assertEquals("int", kind("count"))
        assertEquals("double", kind("ratio"))
        assertEquals("boolean", kind("flag"))
        assertEquals("enum", kind("mode"))
        assertEquals(listOf("FAST", "SAFE"), byName.getValue("mode").getValue("values").jsonArray.map { it.jsonPrimitive.content })
        for (json in listOf("tags", "labels", "origin", "any", "blob", "when")) assertEquals("json", kind(json), json)
        assertEquals(listOf(false, false, true, true, false, true), listOf("name", "count", "ratio", "flag", "mode", "note").map { optional(it) })
    }

    @Test
    fun noFormWithoutARecordType() {
        assertEquals(0, ConfigFields.of(block(null), registry).size)
        assertEquals(0, ConfigFields.of(block(SchemaRef("acme.cfg", "NotARecord")), registry).size)
        assertEquals(0, ConfigFields.of(block(SchemaRef("nope", "Ghost")), registry).size)
    }
}
