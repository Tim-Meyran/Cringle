// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import cringle.contract.BlockDefinition
import cringle.contract.ExclusiveKind
import cringle.contract.ExclusiveResource
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BlockDefinitionJsonTest {
    private val definition = BlockDefinition(
        name = "gateway",
        schemas = listOf(SchemaRef("acme", "Request")),
        ports = listOf(
            PortDefinition("in", PortDirection.IN, setOf(TetherType.REQUEST_RESPONSE, TetherType.MESSAGE), SchemaRef("acme", "Request")),
            PortDefinition("workers", PortDirection.OUT, setOf(TetherType.STREAM), SchemaRef("cringle.std", "Bytes"), varArg = true),
        ),
        requiredDrivers = listOf("tcp", "logging"),
        configSchema = SchemaRef("acme", "GatewayConfig"),
    )

    @Test
    fun roundTripKeepsEverythingIncludingOrder() {
        val parsed = BlockDefinitionJson.parse(BlockDefinitionJson.encode(definition))
        assertEquals(definition, parsed)
        assertEquals(definition.requiredDrivers, parsed.requiredDrivers)
        assertEquals(definition.ports[0].tetherTypes.toList(), parsed.ports[0].tetherTypes.toList())
    }

    @Test
    fun optionalPartsMayBeMissing() {
        val parsed = BlockDefinitionJson.parse("""{"name":"x"}""")
        assertEquals(BlockDefinition("x", emptyList(), emptyList(), emptyList()), parsed)
        assertEquals(null, parsed.configSchema)
    }

    @Test
    fun invalidInputsAreReportedWithPaths() {
        fun bad(text: String) = assertThrows<PackageFormatException> { BlockDefinitionJson.parse(text) }
        assertTrue(bad("""{"name":"x","extra":1}""").message!!.contains("unknown key 'extra'"))
        assertTrue(bad("""{}""").message!!.contains("missing key 'name'"))
        assertTrue(bad("""{"name":" "}""").message!!.contains("must not be blank"))
        assertEquals("$.ports[0].varArg", bad("""{"name":"x","ports":[{"name":"p","direction":"IN","tetherTypes":["MESSAGE"],"schema":"a/B","varArg":"yes"}]}""").path)
        assertTrue(bad("""{"name":"x","requiredDrivers":["a","a"]}""").message!!.contains("more than once"))
        assertTrue(bad("""{"name":"x","ports":[{"name":"p","direction":"IN","tetherTypes":["MESSAGE"],"schema":"a/B"},{"name":"p","direction":"IN","tetherTypes":["MESSAGE"],"schema":"a/B"}]}""").message!!.contains("more than one port"))
    }

    @Test
    fun exclusiveResourcesRoundTripAndAreChecked() {
        val withResources = BlockDefinition("modbus", emptyList(), emptyList(), emptyList(), null, listOf(ExclusiveResource(ExclusiveKind.SERIAL, "COM3"), ExclusiveResource(ExclusiveKind.PORT, "http")))
        val text = BlockDefinitionJson.encode(withResources)
        assertTrue(text.contains("\"exclusiveResources\""))
        assertEquals(withResources, BlockDefinitionJson.parse(text))
        assertTrue(!BlockDefinitionJson.encode(definition).contains("exclusiveResources"), "nothing is written for a block without any")
        fun bad(json: String) = assertThrows<PackageFormatException> { BlockDefinitionJson.parse(json) }
        assertEquals("$.exclusiveResources[0].kind", bad("""{"name":"x","exclusiveResources":[{"kind":"FILE","label":"a"}]}""").path)
        assertTrue(bad("""{"name":"x","exclusiveResources":[{"kind":"PORT","label":" "}]}""").message!!.contains("needs a label"))
        assertTrue(bad("""{"name":"x","exclusiveResources":[{"kind":"PORT","label":"a","extra":1}]}""").message!!.contains("unknown key 'extra'"))
        assertTrue(bad("""{"name":"x","exclusiveResources":[{"kind":"PORT","label":"a"},{"kind":"PORT","label":"a"}]}""").message!!.contains("more than once"))
    }
}
