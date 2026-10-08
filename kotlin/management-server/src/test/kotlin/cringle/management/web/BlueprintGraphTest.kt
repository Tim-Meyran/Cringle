// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.BlockDefinition
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.DeliveryPolicy
import cringle.packaging.Endpoint
import cringle.packaging.ManifestJson
import cringle.packaging.RecordConfig
import cringle.packaging.TetherDef
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BlueprintGraphTest {
    private val string = SchemaRef("cringle.std", "String")

    private val definitions = mapOf(
        "p/src" to BlockDefinition("src", emptyList(), listOf(PortDefinition("out", PortDirection.OUT, setOf(TetherType.MESSAGE, TetherType.STREAM), string)), emptyList()),
        "p/mid" to BlockDefinition(
            "mid", emptyList(),
            listOf(PortDefinition("in", PortDirection.IN, setOf(TetherType.MESSAGE), string), PortDefinition("out", PortDirection.OUT, setOf(TetherType.MESSAGE), string), PortDefinition("log", PortDirection.OUT, setOf(TetherType.MESSAGE), string)),
            emptyList(),
        ),
        "p/sink" to BlockDefinition("sink", emptyList(), listOf(PortDefinition("in", PortDirection.IN, setOf(TetherType.MESSAGE), string)), emptyList()),
    )

    private fun definition(reference: String) = definitions[reference]

    private fun roundTrip(blueprint: Blueprint): Blueprint {
        val graph = BlueprintGraph.toGraph(blueprint, emptyMap(), ::definition)
        val kept = blueprint.tethers.filterNot { BlueprintGraph.drawable(it) }
        return BlueprintGraph.toBlueprint(blueprint.name, graph, BlueprintGraph.options(blueprint), kept, blueprint.provides, ::definition)
    }

    @Test
    fun aBlueprintWithTethersComesBackUnchanged() {
        val blueprint = Blueprint(
            "app",
            listOf(
                BlueprintBlock("a", "p/src", JsonObject(mapOf("x" to JsonPrimitive("1")))),
                BlueprintBlock("b", "p/mid"),
                BlueprintBlock("c", "p/sink"),
                BlueprintBlock("d", "p/sink"),
            ),
            listOf(
                TetherDef(TetherType.MESSAGE, Endpoint("a", "out"), Endpoint("b", "in"), delivery = DeliveryPolicy.BUFFER, record = RecordConfig()),
                TetherDef(TetherType.MESSAGE, Endpoint("b", "log"), Endpoint("d", "in")),
                TetherDef(TetherType.MESSAGE, Endpoint("b", "out"), Endpoint("c", "in")),
            ).sortedBy { BlueprintGraph.key(it.from!!, it.to!!) },
        )
        assertEquals(blueprint, roundTrip(blueprint))
        assertEquals(ManifestJson.encode(blueprint), ManifestJson.encode(roundTrip(blueprint)))
    }

    @Test
    fun theSampleBlueprintsComeBackUnchangedWithTheirServicesKept() {
        for (file in listOf("shop/blueprints/app.json", "orders-service/blueprints/service.json")) {
            val text = Files.readString(Path.of("..", "..", "samples", "shared-service", file))
            val blueprint = ManifestJson.parseBlueprint(text, file)
            val defs = blueprint.blocks.associate { it.block to BlockDefinition(it.block.substringAfter('/'), emptyList(), emptyList(), emptyList()) }
            val graph = BlueprintGraph.toGraph(blueprint, emptyMap()) { defs[it] }
            val back = BlueprintGraph.toBlueprint(blueprint.name, graph, JsonObject(emptyMap()), blueprint.tethers.filterNot { BlueprintGraph.drawable(it) }, blueprint.provides) { defs[it] }
            assertEquals(blueprint, back, file)
        }
    }

    @Test
    fun theTetherTypeIsTheFirstTheTwoPortsShare() {
        val out = definitions.getValue("p/src").ports.single()
        val input = definitions.getValue("p/sink").ports.single()
        assertEquals(TetherType.MESSAGE, BlueprintGraph.commonType(out, input))
        val streamOnly = PortDefinition("in", PortDirection.IN, setOf(TetherType.BYTE_STREAM), string)
        assertEquals(null, BlueprintGraph.commonType(out, streamOnly))
    }

    @Test
    fun aMalformedOrImpossibleGraphIsRefused() {
        assertThrows<GraphException> { BlueprintGraph.toBlueprint("x", JsonObject(emptyMap()), JsonObject(emptyMap()), emptyList(), emptyList(), ::definition) }
        val blueprint = Blueprint("app", listOf(BlueprintBlock("a", "p/src"), BlueprintBlock("c", "p/sink")), listOf(TetherDef(TetherType.MESSAGE, Endpoint("a", "out"), Endpoint("c", "in"))))
        val graph = BlueprintGraph.toGraph(blueprint, emptyMap(), ::definition)
        assertThrows<GraphException> { BlueprintGraph.toBlueprint("app", graph, JsonObject(emptyMap()), emptyList(), emptyList()) { null } }
    }
}
