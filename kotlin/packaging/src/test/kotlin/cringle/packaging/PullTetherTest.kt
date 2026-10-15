// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import cringle.contract.BlockDefinition
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import cringle.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PullTetherTest {
    private val string = SchemaRef("cringle.std", "String")
    private val int = SchemaRef("cringle.std", "Int")
    private val both = setOf(TetherType.MESSAGE, TetherType.PULL)

    private val blocks = mapOf(
        "p/producer" to BlockDefinition("producer", emptyList(), listOf(PortDefinition("out", PortDirection.OUT, both, string)), emptyList()),
        "p/pushOnly" to BlockDefinition("pushOnly", emptyList(), listOf(PortDefinition("out", PortDirection.OUT, setOf(TetherType.MESSAGE), string)), emptyList()),
        "p/getter" to BlockDefinition("getter", emptyList(), listOf(PortDefinition("get", PortDirection.IN, setOf(TetherType.PULL), string)), emptyList()),
        "p/intGetter" to BlockDefinition("intGetter", emptyList(), listOf(PortDefinition("get", PortDirection.IN, setOf(TetherType.PULL), int)), emptyList()),
        "p/both" to BlockDefinition("both", emptyList(), listOf(PortDefinition("in", PortDirection.IN, both, string)), emptyList()),
    )

    private fun check(from: String, to: String, type: TetherType): List<String> {
        val port = if (to == "p/getter" || to == "p/intGetter") "get" else "in"
        val blueprint = Blueprint("t", listOf(BlueprintBlock("a", from), BlueprintBlock("b", to)), listOf(TetherDef(type, Endpoint("a", "out"), Endpoint("b", port))))
        return PackageValidator.validateBlueprint(blueprint, blocks::get, SchemaRegistry(), "t").map { it.message }
    }

    @Test
    fun aPullTetherNeedsPortsThatSupportItAndSchemasThatFit() {
        assertEquals(emptyList<String>(), check("p/producer", "p/getter", TetherType.PULL))
        assertEquals(emptyList<String>(), check("p/producer", "p/both", TetherType.PULL), "a port that supports both can be pulled and pushed")
        assertEquals(emptyList<String>(), check("p/producer", "p/both", TetherType.MESSAGE))
        assertTrue(check("p/pushOnly", "p/getter", TetherType.PULL).single().contains("does not support PULL"))
        assertTrue(check("p/producer", "p/getter", TetherType.MESSAGE).single().contains("does not support MESSAGE"))
        assertTrue(check("p/producer", "p/intGetter", TetherType.PULL).single().contains("not assignable"), "the value of the producer has to fit what the getter wants")
    }

    @Test
    fun aPullTetherCannotEndOnAnotherEngine() {
        val blueprint = Blueprint(
            "t", listOf(BlueprintBlock("a", "p/producer")),
            listOf(TetherDef(TetherType.PULL, Endpoint("a", "out"), null, remote = RemoteEndpoint("far:1", "a".repeat(64), "f", "b", "in"))),
        )
        val problems = PackageValidator.validateBlueprint(blueprint, blocks::get, SchemaRegistry(), "t").map { it.message }
        assertTrue(problems.any { it.contains("cannot have a 'remote'") }, problems.toString())
    }
}
