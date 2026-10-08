// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import cringle.contract.TetherType
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The `record` of a tether (#193). */
class TetherRecordTest {
    private val plugins = listOf(Fixtures.pluginPackage())

    private fun problems(blueprint: Blueprint): List<PackageProblem> =
        PackageValidator.validateProject(Fixtures.projectPackage(listOf(blueprint)), plugins)

    private fun withRecord(record: RecordConfig?, type: TetherType = TetherType.MESSAGE): Blueprint =
        Fixtures.main.copy(tethers = Fixtures.main.tethers.mapIndexed { i, t -> if (i == 0) t.copy(type = type, record = record) else t })

    @Test
    fun aRecordIsReadWrittenAndKeptUnchanged() {
        val bp = withRecord(RecordConfig(Duration.ofMillis(86_400_000), 1_000_000))
        val text = ManifestJson.encode(bp)
        assertTrue("\"maxAge\": 86400000" in text && "\"maxBytes\": 1000000" in text, text)
        assertEquals(bp, ManifestJson.parseBlueprint(text, "f.json"))
        assertEquals(emptyList<PackageProblem>(), problems(bp))
        // an empty record means "record without limits"
        val empty = withRecord(RecordConfig())
        assertEquals(RecordConfig(), ManifestJson.parseBlueprint(ManifestJson.encode(empty), "f.json").tethers[0].record)
        assertEquals(emptyList<PackageProblem>(), problems(empty))
        // a blueprint without one is written without the key
        assertTrue("record" !in ManifestJson.encode(Fixtures.main))
    }

    @Test
    fun unknownKeysAndWrongTypesAreFormatErrors() {
        val json = ManifestJson.encode(withRecord(RecordConfig(Duration.ofSeconds(1), 5)))
        assertThrows<PackageFormatException> { ManifestJson.parseBlueprint(json.replace("\"maxBytes\"", "\"maxRows\""), "f.json") }
        assertThrows<PackageFormatException> { ManifestJson.parseBlueprint(json.replace("\"maxBytes\": 5", "\"maxBytes\": \"five\""), "f.json") }
    }

    @Test
    fun limitsMustBePositiveAndBytesTethersAreNotRecorded() {
        val zero = problems(withRecord(RecordConfig(Duration.ZERO, -1)))
        assertTrue(zero.any { it.path.endsWith("record.maxAge") } && zero.any { it.path.endsWith("record.maxBytes") }, zero.toString())
        val bytes = problems(withRecord(RecordConfig(), TetherType.BYTE_STREAM))
        assertTrue(bytes.any { it.path.endsWith("$.tethers[0].record") && "not recorded" in it.message }, bytes.toString())
    }
}
