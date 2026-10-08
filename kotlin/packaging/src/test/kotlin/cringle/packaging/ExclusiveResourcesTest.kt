// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import cringle.contract.BlockDefinition
import cringle.contract.ExclusiveKind
import cringle.contract.ExclusiveResource
import cringle.contract.TetherType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExclusiveResourcesTest {
    private val definitions = mapOf(
        "p/plain" to BlockDefinition("plain", emptyList(), emptyList(), emptyList()),
        "p/modbus" to BlockDefinition("modbus", emptyList(), emptyList(), emptyList(), null, listOf(ExclusiveResource(ExclusiveKind.SERIAL, "COM3"))),
    )

    private fun of(blueprint: Blueprint) = ExclusiveResources.of(blueprint) { definitions[it] }

    @Test
    fun aBlueprintOfPlainBlocksHoldsNothing() {
        assertTrue(of(Blueprint("app", listOf(BlueprintBlock("a", "p/plain"), BlueprintBlock("b", "p/plain")), listOf(TetherDef(TetherType.MESSAGE, Endpoint("a", "out"), Endpoint("b", "in"))))).isEmpty())
    }

    @Test
    fun aBlockThatDeclaresOneIsFoundWithItsId() {
        val found = of(Blueprint("app", listOf(BlueprintBlock("a", "p/plain"), BlueprintBlock("m", "p/modbus")), emptyList()))
        assertEquals(listOf("block 'm' holds SERIAL 'COM3'"), found)
    }

    @Test
    fun tcpAndSerialTethersCountWithoutAnyDeclaration() {
        val blueprint = Blueprint(
            "app", listOf(BlueprintBlock("a", "p/plain"), BlueprintBlock("b", "p/plain")),
            listOf(
                TetherDef(TetherType.TCP, Endpoint("a", "out"), Endpoint("b", "in"), port = 9000),
                TetherDef(TetherType.SERIAL, Endpoint("a", "out"), Endpoint("b", "in"), serial = SerialTetherConfig("/dev/ttyUSB0")),
            ),
        )
        assertEquals(listOf("a TCP tether listens on port 9000", "a SERIAL tether opens /dev/ttyUSB0"), of(blueprint))
    }

    @Test
    fun anUnknownBlockDeclaresNothing() {
        assertTrue(of(Blueprint("app", listOf(BlueprintBlock("x", "p/unknown")), emptyList())).isEmpty())
    }
}
