// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.BlockDefinition
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import cringle.packaging.Blueprint
import cringle.packaging.Backoff
import cringle.packaging.BlueprintBlock
import cringle.contract.IsolationLevel
import cringle.packaging.RetryConfig
import cringle.packaging.DeliveryPolicy
import cringle.packaging.Endpoint
import cringle.packaging.ManifestJson
import cringle.packaging.RecordConfig
import cringle.packaging.RemoteEndpoint
import cringle.packaging.TetherDef
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        "p/fan" to BlockDefinition("fan", emptyList(), listOf(PortDefinition("in", PortDirection.IN, setOf(TetherType.MESSAGE), string, varArg = true)), emptyList()),
        "p/sink" to BlockDefinition("sink", emptyList(), listOf(PortDefinition("in", PortDirection.IN, setOf(TetherType.MESSAGE), string)), emptyList()),
    )

    private fun definition(reference: String) = definitions[reference]

    private fun roundTrip(blueprint: Blueprint): Blueprint {
        val graph = BlueprintGraph.toGraph(blueprint, emptyMap(), ::definition)
        val kept = blueprint.tethers.filterNot { BlueprintGraph.drawable(it) }
        return BlueprintGraph.toBlueprint(blueprint.name, graph, BlueprintGraph.options(blueprint), kept, blueprint.provides, blueprint.assertions, definition = ::definition)
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
    fun tetherOptionsAreWrittenAndComeBack() {
        val blueprint = Blueprint(
            "app",
            listOf(BlueprintBlock("a", "p/src"), BlueprintBlock("c", "p/sink")),
            listOf(
                TetherDef(
                    TetherType.MESSAGE, Endpoint("a", "out"), Endpoint("c", "in"), delivery = DeliveryPolicy.BUFFER, bufferCapacity = 64,
                    requestTimeout = java.time.Duration.ofMillis(2500), retry = RetryConfig(5, 100, Backoff.EXPONENTIAL, 4000),
                ),
            ),
        )
        assertEquals(blueprint, roundTrip(blueprint))
        val options = BlueprintGraph.options(blueprint).getValue("a.out>c.in").jsonObject
        assertEquals("64", options.getValue("bufferCapacity").jsonPrimitive.content)
        assertEquals("EXPONENTIAL", options.getValue("retry").jsonObject.getValue("backoff").jsonPrimitive.content)
        val graph = BlueprintGraph.toGraph(blueprint, emptyMap(), ::definition)
        assertThrows<GraphException> {
            BlueprintGraph.toBlueprint("app", graph, JsonObject(mapOf("a.out>c.in" to JsonObject(mapOf("bufferCapacity" to JsonPrimitive("many"))))), emptyList(), emptyList(), definition = ::definition)
        }
    }

    @Test
    fun retentionIsolationAndVarArgCountsSurviveASave() {
        val blueprint = Blueprint(
            "app",
            listOf(BlueprintBlock("a", "p/src", isolation = IsolationLevel.PROCESS), BlueprintBlock("c", "p/fan", varArgCounts = mapOf("in" to 2))),
            listOf(TetherDef(TetherType.MESSAGE, Endpoint("a", "out"), Endpoint("c", "in", 1), record = RecordConfig(java.time.Duration.ofDays(7), 1_000_000L))),
        )
        val graph = BlueprintGraph.toGraph(blueprint, emptyMap(), ::definition)
        val saved = BlueprintGraph.toBlueprint("app", graph, BlueprintGraph.options(blueprint), emptyList(), emptyList(), previousBlocks = blueprint.blocks, definition = ::definition)
        assertEquals(blueprint, saved)
        // the node carries them, so they survive even without the previous blueprint
        assertEquals(blueprint, BlueprintGraph.toBlueprint("app", graph, BlueprintGraph.options(blueprint), emptyList(), emptyList(), definition = ::definition))
    }

    @Test
    fun varArgSlotsComeBackAsTethersWithIndex() {
        val blueprint = Blueprint(
            "app",
            listOf(BlueprintBlock("a", "p/src"), BlueprintBlock("b", "p/src"), BlueprintBlock("f", "p/fan", varArgCounts = mapOf("in" to 2))),
            listOf(
                TetherDef(TetherType.MESSAGE, Endpoint("a", "out"), Endpoint("f", "in", 0)),
                TetherDef(TetherType.MESSAGE, Endpoint("b", "out"), Endpoint("f", "in", 1)),
            ),
        )
        assertEquals(blueprint, roundTrip(blueprint))
        assertEquals(listOf("in[0]", "in[1]"), BlueprintGraph.slots(definitions.getValue("p/fan"), PortDirection.IN, mapOf("in" to 2)).map { it.label })
        assertEquals(listOf("a.out>f.in[0]", "b.out>f.in[1]"), blueprint.tethers.map { BlueprintGraph.key(it.from!!, it.to!!) })
    }

    @Test
    fun isolationAndCountsOfTheNodeAreWritten() {
        val blueprint = Blueprint("app", listOf(BlueprintBlock("f", "p/fan", isolation = IsolationLevel.PROCESS, varArgCounts = mapOf("in" to 3))), emptyList())
        val graph = BlueprintGraph.toGraph(blueprint, emptyMap(), ::definition)
        val data = graph.toString()
        assertEquals(true, data.contains("\"isolation\":\"PROCESS\"") && data.contains("\"varArgCounts\":{\"in\":3}"), data)
        assertEquals(blueprint, BlueprintGraph.toBlueprint("app", graph, JsonObject(emptyMap()), emptyList(), emptyList(), definition = ::definition))
        // a node without counts gets one slot per VarArg port
        val bare = Blueprint("app", listOf(BlueprintBlock("f", "p/fan")), emptyList())
        assertEquals(mapOf("in" to 1), BlueprintGraph.toBlueprint("app", BlueprintGraph.toGraph(bare, emptyMap(), ::definition), JsonObject(emptyMap()), emptyList(), emptyList(), definition = ::definition).blocks.single().varArgCounts)
    }

    @Test
    fun remoteAndServiceTethersAreDrawnAndComeBackUnchanged() {
        val remote = RemoteEndpoint(null, "ab".repeat(32), "other", "in", "port")
        val blueprint = Blueprint(
            "app",
            listOf(BlueprintBlock("a", "p/src"), BlueprintBlock("c", "p/sink")),
            listOf(
                TetherDef(TetherType.MESSAGE, Endpoint("a", "out"), null, delivery = DeliveryPolicy.BUFFER, remote = remote.copy(address = "10.0.0.5:7460")),
                TetherDef(TetherType.MESSAGE, Endpoint("a", "out"), null, service = "orders"),
                TetherDef(TetherType.MESSAGE, null, Endpoint("c", "in"), remote = remote),
            ),
        )
        assertEquals(true, blueprint.tethers.all { BlueprintGraph.drawable(it) })
        assertEquals(blueprint, roundTrip(blueprint))
        assertEquals(setOf("a.out>ext1"), BlueprintGraph.options(blueprint).keys)
        val graph = BlueprintGraph.toGraph(blueprint, emptyMap(), ::definition).toString()
        assertEquals(true, graph.contains("\"class\":\"cringle-external\"") && graph.contains("\"mode\":\"service\"") && graph.contains("\"send\":false"), graph)
    }

    @Test
    fun aRemoteEndNeedsAPortThatSupportsItsType() {
        val blueprint = Blueprint(
            "app",
            listOf(BlueprintBlock("a", "p/src")),
            listOf(TetherDef(TetherType.REQUEST_RESPONSE, Endpoint("a", "out"), null, remote = RemoteEndpoint(null, "ab".repeat(32), "other", "in", "port"))),
        )
        val e = assertThrows<GraphException> { roundTrip(blueprint) }
        assertEquals(true, e.message!!.contains("does not support REQUEST_RESPONSE"), e.message)
    }

    @Test
    fun anOutPortWithSeveralTethersComesBackUnchanged() {
        val blueprint = Blueprint(
            "app",
            listOf(BlueprintBlock("a", "p/src"), BlueprintBlock("c", "p/sink"), BlueprintBlock("d", "p/sink")),
            listOf(
                TetherDef(TetherType.MESSAGE, Endpoint("a", "out"), Endpoint("c", "in")),
                TetherDef(TetherType.MESSAGE, Endpoint("a", "out"), Endpoint("d", "in"), delivery = DeliveryPolicy.BUFFER),
            ),
        )
        assertEquals(blueprint, roundTrip(blueprint))
    }

    @Test
    fun aTetherToAPortThatNoLongerExistsIsKeptNotLost() {
        val gone = TetherDef(TetherType.MESSAGE, Endpoint("a", "gone"), null, service = "orders")
        val blueprint = Blueprint("app", listOf(BlueprintBlock("a", "p/src")), listOf(gone))
        assertEquals(listOf(gone), BlueprintGraph.unresolved(blueprint, ::definition))
        val graph = BlueprintGraph.toGraph(blueprint, emptyMap(), ::definition)
        val saved = BlueprintGraph.toBlueprint("app", graph, JsonObject(emptyMap()), BlueprintGraph.unresolved(blueprint, ::definition), emptyList(), definition = ::definition)
        assertEquals(blueprint, saved)
    }

    @Test
    fun theSampleBlueprintsComeBackUnchangedWithTheirServicesKept() {
        for (file in listOf("shop/blueprints/app.json", "orders-service/blueprints/service.json")) {
            val text = Files.readString(Path.of("..", "..", "samples", "shared-service", file))
            val blueprint = ManifestJson.parseBlueprint(text, file)
            val defs = blueprint.blocks.associate { it.block to BlockDefinition(it.block.substringAfter('/'), emptyList(), emptyList(), emptyList()) }
            val graph = BlueprintGraph.toGraph(blueprint, emptyMap()) { defs[it] }
            val back = BlueprintGraph.toBlueprint(blueprint.name, graph, BlueprintGraph.options(blueprint), blueprint.tethers.filterNot { BlueprintGraph.drawable(it) } + BlueprintGraph.unresolved(blueprint) { defs[it] }, blueprint.provides) { defs[it] }
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
        assertThrows<GraphException> { BlueprintGraph.toBlueprint("x", JsonObject(emptyMap()), JsonObject(emptyMap()), emptyList(), emptyList(), emptyList(), definition = ::definition) }
        val blueprint = Blueprint("app", listOf(BlueprintBlock("a", "p/src"), BlueprintBlock("c", "p/sink")), listOf(TetherDef(TetherType.MESSAGE, Endpoint("a", "out"), Endpoint("c", "in"))))
        val graph = BlueprintGraph.toGraph(blueprint, emptyMap(), ::definition)
        assertThrows<GraphException> { BlueprintGraph.toBlueprint("app", graph, JsonObject(emptyMap()), emptyList(), emptyList()) { null } }
    }
}
