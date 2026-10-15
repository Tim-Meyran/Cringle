// SPDX-License-Identifier: Apache-2.0

package cringle.schema

import cringle.contract.SchemaRef
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConstrainedTypesTest {
    private val document = """
        {
          "namespace": "acme",
          "types": {
            "Path": { "string": { "pattern": "/[a-z]*", "maxLength": 5, "description": "a short path like /ab" } },
            "Name": { "string": { "minLength": 2 } },
            "Port": { "int": { "minimum": 1, "maximum": 65535 } },
            "Ratio": { "double": { "minimum": 0, "maximum": 1 } },
            "Config": { "record": { "path": "Path", "name": { "optional": "Name" }, "port": "Port", "ratio": { "optional": "Ratio" } } }
          }
        }
    """.trimIndent()

    private val registry = SchemaRegistry().also { it.add(SchemaParser.parse(document, "test")) }

    private fun problems(json: String): List<String> =
        SchemaValidator(registry).validate(Json.parseToJsonElement(json), SchemaRef("acme", "Config")).map { "${it.path}: ${it.message}" }

    @Test
    fun aValueThatMeetsTheConstraintsIsValid() {
        assertEquals(emptyList<String>(), problems("""{"path":"/ab","port":8080,"name":"ada","ratio":0.5}"""))
    }

    @Test
    fun aValueThatBreaksOneIsRefusedWithTheDescription() {
        val path = problems("""{"path":"ab","port":8080}""").single()
        assertTrue(path.startsWith("$.path:") && path.contains("a short path like /ab"), path)
        assertTrue(problems("""{"path":"/abcdef","port":1}""").single().contains("longer than 5"))
        assertTrue(problems("""{"path":"/a","port":1,"name":"x"}""").single().contains("shorter than 2"))
        assertTrue(problems("""{"path":"/a","port":0}""").single().contains("below the minimum 1"))
        assertTrue(problems("""{"path":"/a","port":70000}""").single().contains("above the maximum 65535"))
        assertTrue(problems("""{"path":"/a","port":1,"ratio":1.5}""").single().contains("above the maximum 1.0"))
        // the base type is still checked first
        assertTrue(problems("""{"path":3,"port":1}""").single().contains("a string"))
        assertTrue(problems("""{"path":"/a","port":"x"}""").single().isNotEmpty())
    }

    @Test
    fun theDocumentIsCheckedWhenItIsRead() {
        fun bad(types: String) = assertThrows<SchemaParseException> { SchemaParser.parse("""{"namespace":"acme","types":{$types}}""", "t") }
        assertTrue(bad(""""A": {"string": {"pattern": "("}}""").message!!.contains("regular expression"))
        assertTrue(bad(""""A": {"string": {"minimum": 1}}""").message!!.contains("unknown constraint"))
        assertTrue(bad(""""A": {"int": {"pattern": "x"}}""").message!!.contains("unknown constraint"))
        assertTrue(bad(""""A": {"int": {"minimum": 5, "maximum": 1}}""").message!!.contains("larger"))
        assertTrue(bad(""""A": {"int": {"minimum": 1.5}}""").message!!.contains("whole number"))
        assertTrue(bad(""""A": {"string": {"minLength": 5, "maxLength": 2}}""").message!!.contains("larger"))
        assertTrue(bad(""""A": {"string": {}, "enum": ["X"]}""").message!!.contains("needs exactly one"))
    }

    @Test
    fun aConstrainedTypeIsItsOwnType() {
        val path = SchemaRef("acme", "Path")
        assertTrue(isAssignable(path, path, registry))
        assertTrue(isAssignable(path, StandardSchemas.ANY, registry))
        assertTrue(!isAssignable(path, SchemaRef("cringle.std", "String"), registry))
    }
}
