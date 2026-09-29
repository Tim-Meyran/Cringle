// SPDX-License-Identifier: Apache-2.0

package cringle.contract

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/** Tests of the value types, their validation rules and their immutability. */
class ContractModelTest {
    private val text = SchemaRef("acme.text", "Text")

    // --- SchemaRef ----------------------------------------------------------------

    @Test
    fun schemaRefParseRoundTripsWithToString() {
        for (input in listOf("acme.orders/Order", "cringle.std/String", "a/b/C")) {
            assertEquals(input, SchemaRef.parse(input).toString())
        }
        assertEquals(SchemaRef("a/b", "C"), SchemaRef.parse("a/b/C"))
        assertEquals(SchemaRef("acme.orders", "Order"), SchemaRef.parse("acme.orders/Order"))
    }

    @Test
    fun schemaRefParseRejectsMalformedText() {
        for (input in listOf("NoSlash", "", "/Name", "ns/", "  /Name", "ns/  ")) {
            assertThrows<IllegalArgumentException>("input '$input'") { SchemaRef.parse(input) }
        }
    }

    @Test
    fun schemaRefRejectsBlankPartsAndSlashInName() {
        assertThrows<IllegalArgumentException> { SchemaRef(" ", "Name") }
        assertThrows<IllegalArgumentException> { SchemaRef("ns", "") }
        assertThrows<IllegalArgumentException> { SchemaRef("ns", "a/b") }
    }

    // --- PortDefinition -------------------------------------------------------------

    @Test
    fun portDefinitionRejectsBlankNameAndEmptyTetherTypes() {
        assertThrows<IllegalArgumentException> { PortDefinition(" ", PortDirection.IN, setOf(TetherType.MESSAGE), text) }
        val error = assertThrows<IllegalArgumentException> { PortDefinition("in", PortDirection.IN, emptySet(), text) }
        assertTrue("in" in error.message.orEmpty())
    }

    @Test
    fun portDefinitionCopiesItsTetherTypes() {
        val types = mutableSetOf(TetherType.MESSAGE)
        val port = PortDefinition("in", PortDirection.IN, types, text)
        types += TetherType.STREAM
        assertEquals(setOf(TetherType.MESSAGE), port.tetherTypes)
    }

    @Test
    fun portDefinitionsCompareByValue() {
        val a = PortDefinition("in", PortDirection.IN, setOf(TetherType.MESSAGE), text)
        val b = PortDefinition("in", PortDirection.IN, setOf(TetherType.MESSAGE), text)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, PortDefinition("in", PortDirection.IN, setOf(TetherType.MESSAGE), text, varArg = true))
        assertNotEquals(a, PortDefinition("in", PortDirection.OUT, setOf(TetherType.MESSAGE), text))
    }

    // --- BlockDefinition ------------------------------------------------------------

    private fun port(name: String, varArg: Boolean = false) =
        PortDefinition(name, PortDirection.IN, setOf(TetherType.MESSAGE), text, varArg)

    @Test
    fun blockDefinitionRejectsDuplicatePortNames() {
        val error = assertThrows<IllegalArgumentException> {
            BlockDefinition("b", emptyList(), listOf(port("in"), port("in")), emptyList())
        }
        assertTrue("in" in error.message.orEmpty())
    }

    @Test
    fun blockDefinitionRejectsBlankNameAndBadDriverIds() {
        assertThrows<IllegalArgumentException> { BlockDefinition(" ", emptyList(), emptyList(), emptyList()) }
        assertThrows<IllegalArgumentException> { BlockDefinition("b", emptyList(), emptyList(), listOf(" ")) }
        val error = assertThrows<IllegalArgumentException> {
            BlockDefinition("b", emptyList(), emptyList(), listOf("dwh", "tcp", "dwh"))
        }
        assertTrue("dwh" in error.message.orEmpty())
    }

    @Test
    fun blockDefinitionCopiesItsCollections() {
        val schemas = mutableListOf(text)
        val ports = mutableListOf(port("in"))
        val drivers = mutableListOf("dwh")
        val definition = BlockDefinition("b", schemas, ports, drivers)

        schemas.clear()
        ports.clear()
        drivers.clear()

        assertEquals(listOf(text), definition.schemas)
        assertEquals(listOf("in"), definition.ports.map { it.name })
        assertEquals(listOf("dwh"), definition.requiredDrivers)
        assertThrows<UnsupportedOperationException> { (definition.ports as MutableList<PortDefinition>).clear() }
    }

    @Test
    fun blockDefinitionCanRepresentEverythingChapter22Lists() {
        val orders = SchemaRef.parse("acme.orders/Order")
        val config = SchemaRef.parse("acme.orders/RouterConfig")
        val definition = BlockDefinition(
            name = "order-router",
            schemas = listOf(orders, config),
            ports = listOf(
                PortDefinition("orders", PortDirection.IN, setOf(TetherType.MESSAGE, TetherType.REQUEST_RESPONSE), orders),
                PortDefinition("targets", PortDirection.OUT, setOf(TetherType.MESSAGE), orders, varArg = true),
                PortDefinition("audit", PortDirection.OUT, setOf(TetherType.STREAM, TetherType.BYTE_STREAM), orders),
            ),
            requiredDrivers = listOf("logging", "dwh"),
            configSchema = config,
        )

        assertEquals("order-router", definition.name)
        assertEquals(listOf(orders, config), definition.schemas)
        assertEquals(listOf("orders", "targets", "audit"), definition.ports.map { it.name })
        assertEquals(listOf(false, true, false), definition.ports.map { it.varArg })
        assertEquals(setOf(TetherType.STREAM, TetherType.BYTE_STREAM), definition.ports[2].tetherTypes)
        assertEquals(listOf("logging", "dwh"), definition.requiredDrivers)
        assertEquals(config, definition.configSchema)
        assertNull(BlockDefinition("plain", emptyList(), emptyList(), emptyList()).configSchema)
    }

    @Test
    fun blockDefinitionsCompareByValue() {
        fun make() = BlockDefinition("b", listOf(text), listOf(port("in")), listOf("dwh"), text)
        assertEquals(make(), make())
        assertEquals(make().hashCode(), make().hashCode())
        assertNotEquals(make(), BlockDefinition("other", listOf(text), listOf(port("in")), listOf("dwh"), text))
    }

    // --- Drivers ---------------------------------------------------------------------

    @Test
    fun processIsolationIsStricterThanShared() {
        assertTrue(IsolationLevel.PROCESS > IsolationLevel.SHARED)
        assertEquals(IsolationLevel.PROCESS, maxOf(IsolationLevel.SHARED, IsolationLevel.PROCESS))
        assertEquals(IsolationLevel.SHARED, minOf(IsolationLevel.SHARED, IsolationLevel.PROCESS))
    }

    @Test
    fun driverTypeAndDwhEntryRejectBlankIdentifiers() {
        assertThrows<IllegalArgumentException> { DriverType(" ", IsolationLevel.SHARED) }
        assertThrows<IllegalArgumentException> { DwhEntry(" ", "value") }
        assertEquals(DriverType("dwh", IsolationLevel.SHARED), DriverType("dwh", IsolationLevel.SHARED))
    }

    @Test
    fun dwhEntryDefaultsToTheCurrentTime() {
        val before = java.time.Instant.now()
        val entry = DwhEntry("key", 42)
        assertTrue(entry.timestamp >= before && entry.timestamp <= java.time.Instant.now())
    }

    // --- Identity and events -------------------------------------------------------

    @Test
    fun blockIdAndPortRefValidateTheirValues() {
        assertThrows<IllegalArgumentException> { BlockId(" ") }
        assertEquals("block-1", BlockId("block-1").toString())
        assertThrows<IllegalArgumentException> { PortRef(" ") }
        assertThrows<IllegalArgumentException> { PortRef("p", -1) }
        assertNull(PortRef("p").index)
        assertEquals(0, PortRef("p", 0).index)
    }

    @Test
    fun requestEventDeliversTheResponseToItsResponder() = runTest {
        val responses = mutableListOf<Any>()
        val event = TetherEvent.Request(PortRef("in"), "ping") { responses += it }

        assertEquals("ping", event.value)
        event.respond("pong")

        assertEquals(listOf<Any>("pong"), responses)
    }
}
