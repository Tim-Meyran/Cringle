// SPDX-License-Identifier: Apache-2.0

package cringle.contract

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue

/** The M0 criterion: an empty plugin compiles against the contract and its default hooks are usable. */
class EmptyPluginTest {
    private class EmptyBlock : Block

    private class EmptyProvider : BlockProvider {
        override val definitions: List<BlockDefinition> =
            listOf(BlockDefinition(name = "empty", schemas = emptyList(), ports = emptyList(), requiredDrivers = emptyList()))

        override fun createBlock(definitionName: String, drivers: DriverSet): Block {
            require(definitionName == "empty") { "Unknown block '$definitionName'" }
            return EmptyBlock()
        }
    }

    private object NoDrivers : DriverSet {
        override fun <T : Driver> get(type: kotlin.reflect.KClass<T>): T =
            throw IllegalArgumentException("Driver ${type.simpleName} was not declared")
    }

    private object NoPorts : BlockPorts {
        override fun port(name: String): Tether = throw IllegalArgumentException("No port '$name'")

        override fun varArgPort(name: String): List<Tether> = throw IllegalArgumentException("No port '$name'")
    }

    private object EmptyContext : BlockContext {
        override val blockId: BlockId = BlockId("empty-1")
        override val config: Map<String, Any?> = emptyMap()
        override val ports: BlockPorts = NoPorts
    }

    @Test
    fun emptyProviderCreatesABlockThatOverridesNothing() = runTest {
        val provider = EmptyProvider()
        assertEquals(listOf("empty"), provider.definitions.map { it.name })

        val block = provider.createBlock("empty", NoDrivers)

        block.init(EmptyContext)
        block.start()
        block.onTetherEvent(TetherEvent.Message(PortRef("in"), "ignored"))
        block.stop()
        block.destroy()
    }

    @Test
    fun aProviderCreatesIndependentInstances() {
        val provider = EmptyProvider()
        val first = provider.createBlock("empty", NoDrivers)
        val second = provider.createBlock("empty", NoDrivers)
        assertTrue(first !== second)
        assertSame(provider.definitions, provider.definitions)
    }
}
