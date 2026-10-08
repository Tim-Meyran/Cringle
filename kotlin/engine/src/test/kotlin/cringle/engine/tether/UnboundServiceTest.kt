// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.contract.BlockDefinition
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.Endpoint
import cringle.packaging.TetherDef
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A service dependency that no deploy has bound yet is refused (#170). */
class UnboundServiceTest {
    private val types = setOf(TetherType.MESSAGE)
    private val string = SchemaRef("cringle.std", "String")
    private val src = BlockDefinition("src", emptyList(), listOf(PortDefinition("out", PortDirection.OUT, types, string)), emptyList())

    @Test
    fun aFabricWithAnUnboundServiceTetherCannotBeWired() {
        val blueprint = Blueprint(
            "bp",
            listOf(BlueprintBlock("s", "p/src")),
            listOf(TetherDef(TetherType.MESSAGE, Endpoint("s", "out"), null, service = "orders")),
        )
        val e = assertThrows<TetherWiringException> { TetherNetwork.create(blueprint, mapOf("s" to src), TetherConfig()) }
        assertTrue("service 'orders'" in e.message!! && "not bound" in e.message!!, e.message)
    }
}
