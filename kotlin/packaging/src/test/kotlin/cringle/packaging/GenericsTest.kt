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

class GenericsTest {
    private val any = SchemaRef("cringle.std", "Any")
    private val string = SchemaRef("cringle.std", "String")
    private val int = SchemaRef("cringle.std", "Int")
    private val message = setOf(TetherType.MESSAGE)

    private val pass = BlockDefinition(
        "pass",
        listOf(any),
        listOf(
            PortDefinition("in", PortDirection.IN, message, any, typeParam = "T"),
            PortDefinition("out", PortDirection.OUT, message, any, typeParam = "T"),
        ),
        emptyList(),
        typeParameters = listOf(TypeParameter("T")),
    )
    private val sink = BlockDefinition("sink", listOf(string), listOf(PortDefinition("in", PortDirection.IN, message, string)), emptyList())
    private val numbers = BlockDefinition("numbers", listOf(int), listOf(PortDefinition("out", PortDirection.OUT, message, int)), emptyList())
    private val blocks = mapOf("p/pass" to pass, "p/sink" to sink, "p/numbers" to numbers)

    private fun check(vararg b: BlueprintBlock, tether: TetherDef) =
        PackageValidator.validateBlueprint(Blueprint("t", b.toList(), listOf(tether)), blocks::get, SchemaRegistry(), "t").map { it.message }

    private fun edge(from: String, to: String) = TetherDef(TetherType.MESSAGE, Endpoint(from, "out"), Endpoint(to, "in"))

    @Test
    fun bindingReplacesTheSchemasOfTheParameterPorts() {
        val bound = pass.bind(mapOf("T" to string))
        assertEquals(listOf(string, string), bound.ports.map { it.schema })
        assertTrue(bound.typeParameters.isEmpty() && bound.ports.all { it.typeParam == null })
        assertEquals(any, pass.bind(emptyMap()).ports[0].schema, "unbound, a parameter is its bound")
        assertThrows<IllegalArgumentException> { pass.bind(mapOf("U" to string)) }
        assertThrows<IllegalArgumentException> { BlockDefinition("x", emptyList(), listOf(PortDefinition("in", PortDirection.IN, message, any, typeParam = "T")), emptyList()) }
        assertThrows<IllegalArgumentException> { BlockDefinition("x", emptyList(), emptyList(), emptyList(), typeParameters = listOf(TypeParameter("T"), TypeParameter("T"))) }
    }

    @Test
    fun parametersAndBindingsSurviveTheJson() {
        val definition = pass
        assertEquals(definition, BlockDefinitionJson.parse(BlockDefinitionJson.encode(definition)))
        val bounded = BlockDefinitionJson.parse("""{"name":"x","typeParameters":[{"name":"A","bound":"cringle.std/String"},{"name":"B"}]}""")
        assertEquals(listOf(TypeParameter("A", string), TypeParameter("B", any)), bounded.typeParameters)
        assertThrows<PackageFormatException> { BlockDefinitionJson.parse("""{"name":"x","typeParameters":[{"name":"A","bound":"nonsense"}]}""") }

        val blueprint = Blueprint("t", listOf(BlueprintBlock("a", "p/pass", types = mapOf("T" to string))), emptyList())
        assertEquals(blueprint, ManifestJson.parseBlueprint(ManifestJson.encode(blueprint), "t.json"))
        assertThrows<PackageFormatException> { ManifestJson.parseBlueprint("""{"name":"t","blocks":[{"id":"a","block":"p/pass","types":{"T":3}}],"tethers":[]}""", "t.json") }
    }

    @Test
    fun aBoundParameterTakesPartInTheTypeCheck() {
        val bound = BlueprintBlock("a", "p/pass", types = mapOf("T" to string))
        val toSink = BlueprintBlock("s", "p/sink")
        assertEquals(emptyList<String>(), check(bound, toSink, tether = edge("a", "s")))

        val unbound = BlueprintBlock("a", "p/pass")
        assertTrue(check(unbound, toSink, tether = edge("a", "s")).single().contains("is not assignable"), "unbound it is Any")

        val ints = BlueprintBlock("n", "p/numbers")
        val intPass = BlueprintBlock("a", "p/pass", types = mapOf("T" to int))
        assertEquals(emptyList<String>(), check(ints, intPass, tether = edge("n", "a")))
        assertTrue(check(ints, BlueprintBlock("a", "p/pass", types = mapOf("T" to string)), tether = edge("n", "a")).single().contains("not assignable"))
    }

    @Test
    fun badBindingsAreReported() {
        val s = BlueprintBlock("s", "p/sink")
        fun problems(types: Map<String, SchemaRef>) = check(BlueprintBlock("a", "p/pass", types = types), s, tether = edge("a", "s"))
        assertTrue(problems(mapOf("U" to string)).any { it.contains("no type parameter 'U'") })
        assertTrue(problems(mapOf("T" to SchemaRef("nope", "Nothing"))).any { it.contains("does not resolve") })
        val bounded = BlockDefinition(
            "text", emptyList(), listOf(PortDefinition("in", PortDirection.IN, message, string, typeParam = "T")), emptyList(), typeParameters = listOf(TypeParameter("T", string)),
        )
        val problems = PackageValidator.validateBlueprint(
            Blueprint("t", listOf(BlueprintBlock("a", "p/text", types = mapOf("T" to int))), emptyList()), mapOf("p/text" to bounded)::get, SchemaRegistry(), "t",
        ).map { it.message }
        assertTrue(problems.any { it.contains("not assignable to the bound") }, problems.toString())
    }
}
