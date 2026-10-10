// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import cringle.contract.BuiltinDriverTypes
import cringle.contract.DriverType
import cringle.contract.LogLevel
import cringle.contract.LoggingDriver
import cringle.packaging.PackageValidator
import cringle.packaging.PluginManifest
import cringle.packaging.PluginPackage
import cringle.stdblocks.flow.Constant
import cringle.stdblocks.flow.Log
import cringle.stdblocks.time.TimerTrigger
import cringle.testkit.BlockTestHarness
import cringle.testkit.TestBlockPorts
import cringle.testkit.TestDriverSet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class BlocksTest {
    private class RecordingLog : LoggingDriver {
        val lines = ArrayList<Pair<LogLevel, String>>()
        override val type: DriverType get() = BuiltinDriverTypes.LOGGING

        override suspend fun log(level: LogLevel, message: String, error: Throwable?) {
            lines += level to message
        }
    }

    private fun ports(vararg names: String) = TestBlockPorts().also { p -> names.forEach { p.addPort(it) } }

    @Test
    fun theTimerTicksAfterTheInitialDelayAndStopsAfterCount() = runTest {
        val ports = ports("tick")
        val harness = BlockTestHarness(
            TimerTrigger(StandardTestDispatcher(testScheduler)),
            config = mapOf("intervalMs" to 100, "initialDelayMs" to 50, "count" to 3),
            ports = ports,
        )
        harness.init()
        harness.start()
        advanceTimeBy(49)
        runCurrent()
        assertEquals(emptyList<Any>(), ports.tether("tick").sentMessages, "nothing before the initial delay")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf<Any>(1L), ports.tether("tick").sentMessages)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf<Any>(1L, 2L, 3L), ports.tether("tick").sentMessages)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(3, ports.tether("tick").sentMessages.size, "no tick after count")
        harness.stop()
        harness.destroy()
    }

    @Test
    fun theTimerStopsWhenTheBlockStops() = runTest {
        val ports = ports("tick")
        val harness = BlockTestHarness(TimerTrigger(StandardTestDispatcher(testScheduler)), config = mapOf("intervalMs" to 10L), ports = ports)
        harness.init()
        harness.start()
        advanceTimeBy(35)
        runCurrent()
        val before = ports.tether("tick").sentMessages.size
        assertTrue(before >= 3, "ticks: $before")
        harness.stop()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(before, ports.tether("tick").sentMessages.size)
    }

    @Test
    fun theTimerRefusesABadConfiguration() = runTest {
        assertThrows<IllegalArgumentException> { BlockTestHarness(TimerTrigger(), config = emptyMap(), ports = ports("tick")).init() }
        assertThrows<IllegalArgumentException> { BlockTestHarness(TimerTrigger(), config = mapOf("intervalMs" to 0), ports = ports("tick")).init() }
        assertThrows<IllegalArgumentException> { BlockTestHarness(TimerTrigger(), config = mapOf("intervalMs" to 5, "count" to 0), ports = ports("tick")).init() }
        assertThrows<IllegalArgumentException> { BlockTestHarness(TimerTrigger(), config = mapOf("intervalMs" to 5, "initialDelayMs" to -1), ports = ports("tick")).init() }
    }

    @Test
    fun constantSendsItsValueOnEveryTrigger() = runTest {
        val ports = ports("trigger", "out")
        BlockTestHarness(Constant(), config = mapOf("value" to "hello"), ports = ports).runLifecycle {
            sendMessage("trigger", 1L)
            sendMessage("trigger", 2L)
            assertEquals(listOf<Any>("hello", "hello"), ports.tether("out").sentMessages)
        }
        assertThrows<IllegalArgumentException> { BlockTestHarness(Constant(), ports = ports("trigger", "out")).init() }
    }

    @Test
    fun logWritesWithLevelAndPrefix() = runTest {
        val driver = RecordingLog()
        val ports = ports("in")
        BlockTestHarness(Log(driver), config = mapOf("level" to "WARN", "prefix" to "tick: "), ports = ports).runLifecycle { sendMessage("in", "3") }
        BlockTestHarness(Log(driver), ports = ports).runLifecycle { sendMessage("in", "plain") }
        assertEquals(listOf(LogLevel.WARN to "tick: 3", LogLevel.INFO to "plain"), driver.lines)
        assertThrows<IllegalArgumentException> { BlockTestHarness(Log(driver), config = mapOf("level" to "LOUD"), ports = ports).init() }
    }

    @Test
    fun everyDefinitionHasABlockAndAValidSchemaReference() {
        val provider = StdBlockProvider()
        val drivers = TestDriverSet().add(LoggingDriver::class, RecordingLog())
            .add(cringle.contract.FilesystemDriver::class, cringle.engine.drivers.FilesystemSandbox(java.nio.file.Files.createTempDirectory("std-blocks-test")))
        for (definition in provider.definitions) provider.createBlock(definition.name, drivers)
        assertThrows<IllegalArgumentException> { provider.createBlock("nothing", drivers) }

        val schema = StdBlockProvider::class.java.getResourceAsStream("/cringle/stdblocks/schema.json")!!.readBytes().decodeToString()
        val manifest = PluginManifest(
            StdBlockProvider.PLUGIN_NAME, StdBlockProvider.VERSION, emptyMap(), listOf(StdBlockProvider::class.qualifiedName!!), emptyList(),
            provider.definitions, listOf("lib/stdblocks.jar"), listOf("schemas/cringle.stdblocks.json"),
        )
        val problems = PackageValidator.validatePlugin(PluginPackage(manifest, mapOf("schemas/cringle.stdblocks.json" to schema), listOf("lib/stdblocks.jar")))
        assertEquals(emptyList<Any>(), problems)
        assertEquals(provider.definitions.size, provider.definitions.map { it.name }.toSet().size)
    }
}
