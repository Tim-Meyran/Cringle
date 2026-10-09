// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The assertions of a blueprint (#232): declaration, JSON and validation. */
class AssertionTest {
    private val plugins = listOf(Fixtures.pluginPackage())
    private val tether = Fixtures.main.tethers.first().localId()!!
    private val block = Fixtures.main.blocks.first().id

    private val all = listOf(
        FabricRunning(),
        BlockRunning(block, "the source runs"),
        TetherFlow(tether, 1, 60),
        NoErrors("quiet"),
    )

    private fun problems(vararg assertions: Assertion): List<PackageProblem> =
        PackageValidator.validateProject(Fixtures.projectPackage(listOf(Fixtures.main.copy(assertions = assertions.toList()))), plugins)

    private fun parse(fragment: String) =
        ManifestJson.parseBlueprint("""{"name":"main","blocks":[],"tethers":[],"assertions":[$fragment]}""", "f.json")

    @Test
    fun theLocalIdOfATetherIsTheOneTheEngineUses() {
        assertEquals("source.out -> sink.in", tether)
        assertEquals("a.many[2] -> b.in", TetherDef(cringle.contract.TetherType.MESSAGE, Endpoint("a", "many", 2), Endpoint("b", "in")).localId())
        assertEquals(null, TetherDef(cringle.contract.TetherType.MESSAGE, Endpoint("a", "out"), null, service = "orders").localId())
    }

    @Test
    fun allFourTypesRoundTripAndNothingIsWrittenWithoutAssertions() {
        val bp = Fixtures.main.copy(assertions = all)
        val text = ManifestJson.encode(bp)
        val back = ManifestJson.parseBlueprint(text, "f.json")
        assertEquals(all, back.assertions)
        assertEquals(text, ManifestJson.encode(back))
        assertTrue("assertions" !in ManifestJson.encode(Fixtures.main))
        assertEquals(emptyList<Assertion>(), ManifestJson.parseBlueprint(Fixtures.blueprint, "f.json").assertions)
    }

    @Test
    fun theFileFormatOfTheFourTypes() {
        val parsed = parse(
            """{"type":"fabric-running"},{"type":"block-running","block":"s1"},{"type":"tether-flow","tether":"a.out -> b.in","min":5,"perSeconds":10},{"type":"no-errors","name":"quiet"}""",
        ).assertions
        assertEquals(listOf(FabricRunning(), BlockRunning("s1"), TetherFlow("a.out -> b.in", 5, 10), NoErrors("quiet")), parsed)
    }

    @Test
    fun badDeclarationsAreRejectedWhenTheFileIsRead() {
        fun message(fragment: String) = assertThrows<PackageFormatException> { parse(fragment) }.message!!
        assertTrue(message("""{"type":"disk-full"}""").contains("unknown assertion type 'disk-full'"))
        assertTrue(message("""{"block":"x"}""").contains("type"))
        assertTrue(message("""{"type":"fabric-running","block":"x"}""").contains("block"), "a key of another type")
        assertTrue(message("""{"type":"block-running"}""").contains("block"))
        assertTrue(message("""{"type":"tether-flow","tether":"a.out -> b.in","min":0,"perSeconds":10}""").contains("min"))
        assertTrue(message("""{"type":"tether-flow","tether":"a.out -> b.in","min":1,"perSeconds":0}""").contains("perSeconds"))
        assertTrue(message("""{"type":"tether-flow","tether":"a.out -> b.in","min":1}""").contains("perSeconds"))
        assertTrue(message("""{"type":"no-errors","name":""}""").contains("1 to 100"))
        assertTrue(message("""{"type":"no-errors","name":"${"x".repeat(101)}"}""").contains("1 to 100"))
    }

    @Test
    fun aValidBlueprintWithAllFourTypesHasNoProblem() {
        assertEquals(emptyList<PackageProblem>(), problems(*all.toTypedArray()))
    }

    @Test
    fun unknownTargetsAndDuplicatesAreProblems() {
        assertTrue(problems(BlockRunning("ghost")).any { it.path.endsWith("$.assertions[0].block") && it.message.contains("unknown block 'ghost'") })
        assertTrue(problems(TetherFlow("x.out -> y.in", 1, 1)).any { it.path.endsWith("$.assertions[0].tether") && it.message.contains("unknown tether") })
        val duplicate = problems(FabricRunning(), FabricRunning("again"))
        assertTrue(duplicate.any { it.path.endsWith("$.assertions[1]") && it.message.contains("duplicate assertion 'fabric-running'") }, duplicate.toString())
        assertTrue(problems(BlockRunning(block), BlockRunning(block)).any { it.message.contains("duplicate") })
        // another window on the same tether is another check
        assertEquals(emptyList<PackageProblem>(), problems(TetherFlow(tether, 1, 60), TetherFlow(tether, 100, 3600)))
        assertTrue(problems(TetherFlow(tether, 1, 60), TetherFlow(tether, 1, 60, "same")).any { it.message.contains("duplicate") })
    }
}
