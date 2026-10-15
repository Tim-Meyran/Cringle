// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import cringle.contract.BlockDefinition
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import cringle.contract.TypeParameter
import cringle.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResponseSchemaTest {
    private val any = SchemaRef("cringle.std", "Any")
    private val string = SchemaRef("cringle.std", "String")
    private val int = SchemaRef("cringle.std", "Int")
    private val rr = setOf(TetherType.REQUEST_RESPONSE)

    private fun block(name: String, port: PortDefinition) = BlockDefinition(name, emptyList(), listOf(port), emptyList())

    private val asker = block("asker", PortDefinition("ask", PortDirection.OUT, rr, string, responseSchema = int))
    private val answersInt = block("ints", PortDefinition("in", PortDirection.IN, rr, string, responseSchema = int))
    private val answersText = block("texts", PortDefinition("in", PortDirection.IN, rr, string))
    private val answersAny = block("anys", PortDefinition("in", PortDirection.IN, rr, any, responseSchema = any))
    private val plainAsker = block("plain", PortDefinition("ask", PortDirection.OUT, rr, string))
    private val blocks = mapOf("p/asker" to asker, "p/ints" to answersInt, "p/texts" to answersText, "p/anys" to answersAny, "p/plain" to plainAsker)

    private fun check(from: String, to: String): List<String> {
        val blueprint = Blueprint(
            "t", listOf(BlueprintBlock("a", from), BlueprintBlock("b", to)),
            listOf(TetherDef(TetherType.REQUEST_RESPONSE, Endpoint("a", if (from == "p/asker" || from == "p/plain") "ask" else "in"), Endpoint("b", "in"))),
        )
        return PackageValidator.validateBlueprint(blueprint, blocks::get, SchemaRegistry(), "t").map { it.message }
    }

    @Test
    fun theResponseDirectionIsCheckedWhereAResponseSchemaIsDeclared() {
        assertEquals(emptyList<String>(), check("p/asker", "p/ints"))
        assertTrue(check("p/asker", "p/texts").single().contains("response schema"), "the receiver answers Strings, the sender wants Ints")
        assertTrue(check("p/asker", "p/anys").single().contains("response schema"), "Any is not an Int")
        // a sender without a response schema expects Strings: Int answers do not fit, but nothing was declared on the sender: the declaring receiver is checked
        assertTrue(check("p/plain", "p/ints").single().contains("response schema"))
        // nothing declared on either side: not checked, as before
        assertEquals(emptyList<String>(), check("p/plain", "p/texts"))
    }

    @Test
    fun aResponseSchemaNeedsAPortThatAnswersAndSurvivesTheJsonAndBinding() {
        assertThrows<IllegalArgumentException> { PortDefinition("in", PortDirection.IN, setOf(TetherType.MESSAGE), string, responseSchema = int) }
        assertEquals(string, answersText.ports.single().response)
        assertEquals(int, answersInt.ports.single().response)
        assertEquals(answersInt, BlockDefinitionJson.parse(BlockDefinitionJson.encode(answersInt)))
        val generic = BlockDefinition(
            "g", emptyList(), listOf(PortDefinition("next", PortDirection.OUT, rr, any, typeParam = "T", responseSchema = int)), emptyList(), typeParameters = listOf(TypeParameter("T")),
        )
        val bound = generic.bind(mapOf("T" to string)).ports.single()
        assertEquals(string, bound.schema)
        assertEquals(int, bound.responseSchema, "binding keeps the response schema")
        assertThrows<PackageFormatException> { BlockDefinitionJson.parse("""{"name":"x","ports":[{"name":"p","direction":"IN","tetherTypes":["MESSAGE"],"schema":"cringle.std/String","responseSchema":"cringle.std/Int"}]}""") }
    }
}
