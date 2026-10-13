// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.TetherType
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.Endpoint
import cringle.packaging.PackageProblem
import cringle.packaging.TetherDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BlueprintProblemsTest {
    private val blueprint = Blueprint(
        "app",
        listOf(BlueprintBlock("t", "cringle-std/time.timer-trigger"), BlueprintBlock("l", "cringle-std/flow.log"), BlueprintBlock("s", "cringle-std/text.to-string")),
        listOf(
            TetherDef(TetherType.MESSAGE, Endpoint("t", "tick"), Endpoint("l", "in")),
            TetherDef(TetherType.MESSAGE, Endpoint("s", "out"), null, service = "orders"),
            TetherDef(TetherType.MESSAGE, Endpoint("t", "tick", 1), Endpoint("s", "in")),
        ),
    )

    private fun describe(path: String, message: String) = BlueprintProblems.describe(PackageProblem(path, message), blueprint)

    @Test
    fun aBlockProblemNamesTheBlockAndThePlace() {
        val p = describe("blueprints/app.json \$.blocks[0].config", "missing required field 'intervalMs'")
        assertEquals("Block t (cringle-std/time.timer-trigger), config: missing required field 'intervalMs'", p.text)
        assertEquals(listOf("t"), p.blocks)
        assertEquals("Block l (cringle-std/flow.log): unknown block", describe("\$.blocks[1]", "unknown block").text, "a problem of the block itself has no place")
    }

    @Test
    fun aTetherProblemNamesBothEndsAndMarksBothBlocks() {
        val p = describe("blueprints/app.json \$.tethers[0].type", "port 'in' does not support STREAM")
        assertEquals("Tether t.tick → l.in, type: port 'in' does not support STREAM", p.text)
        assertEquals(listOf("t", "l"), p.blocks)
        val service = describe("\$.tethers[1]", "service is not bound")
        assertEquals("Tether s.out → service orders: service is not bound", service.text)
        assertEquals(listOf("s"), service.blocks)
        assertEquals("Tether t.tick[1] → s.in: x", describe("\$.tethers[2]", "x").text)
    }

    @Test
    fun otherProblemsKeepTheirPathAndMarkNothing() {
        val p = describe("blueprints/app.json \$.provides[0]", "unknown service")
        assertEquals(DescribedProblem("unknown service", emptyList(), "blueprints/app.json \$.provides[0]"), p)
        // an index that does not exist is not turned into a block
        assertEquals(emptyList<String>(), describe("\$.blocks[9].config", "x").blocks)
        assertEquals(emptyList<String>(), describe("\$.tethers[9]", "x").blocks)
    }

    @Test
    fun severalProblemsOfOneBlockAreListedEach() {
        val all = BlueprintProblems.describe(
            listOf(PackageProblem("\$.blocks[2].config.a", "bad a"), PackageProblem("\$.blocks[2].config.b", "bad b")),
            blueprint,
        )
        assertEquals(listOf("s", "s"), all.flatMap { it.blocks })
        assertEquals(2, all.map { it.text }.distinct().size)
    }
}
