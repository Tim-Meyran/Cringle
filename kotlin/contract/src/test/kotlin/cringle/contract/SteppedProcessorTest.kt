// SPDX-License-Identifier: Apache-2.0

package cringle.contract

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SteppedProcessorTest {
    private class Ctx(override val from: String, override val to: String) : MigrationContext {
        override val scope = MigrationScope.PLUGIN
        override val blockId: String? = "b"
        override val dataDirectory: Path = Path.of(".")
        override fun log(message: String) {}
    }

    private class Chain(val ran: MutableList<String>, fail: String? = null) : SteppedProcessor() {
        init {
            for (v in listOf("1.9.0", "1.10.0", "2.0.0-rc1", "2.0.0", "3.0.0")) {
                step(v) { if (v == fail) throw MigrationException("boom"); ran += v }
            }
        }
    }

    private fun run(from: String, to: String, fail: String? = null): List<String> {
        val ran = ArrayList<String>()
        Chain(ran, fail).migrate(Ctx(from, to))
        return ran
    }

    @Test
    fun updateRunsStepsAscendingInRange() = assertEquals(listOf("1.10.0", "2.0.0-rc1", "2.0.0"), run("1.9.0", "2.0.0"))

    @Test
    fun downgradeRunsStepsDescending() = assertEquals(listOf("3.0.0", "2.0.0", "2.0.0-rc1"), run("3.0.0", "1.10.0"))

    @Test
    fun preReleaseIsBeforeRelease() = assertEquals(listOf("2.0.0"), run("2.0.0-rc1", "2.0.0"))

    @Test
    fun exceptionNamesTheStep() {
        val e = assertThrows<MigrationException> { run("1.0.0", "3.0.0", fail = "2.0.0") }
        assertTrue(e.message!!.contains("step 2.0.0"))
    }

    @Test
    fun invalidStepVersionIsRejected() {
        assertThrows<IllegalArgumentException> { object : SteppedProcessor() { init { step("x") {} } } }
    }
}
