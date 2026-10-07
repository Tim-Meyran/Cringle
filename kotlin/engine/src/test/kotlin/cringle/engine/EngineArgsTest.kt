// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Paths

class EngineArgsTest {
    private fun bad(vararg args: String) = assertThrows<EngineArgsException> { EngineArgs.parse(args.toList()) }

    @Test
    fun parsesAllOptions() {
        val a = EngineArgs.parse(listOf("--id", "e-1", "--name", "Edge One", "--home", "h", "--management-port", "8123"))
        assertEquals(EngineArgs("e-1", "Edge One", Paths.get("h"), 8123), a)
    }

    @Test
    fun defaults() {
        val a = EngineArgs.parse(listOf("--id", "e1"))
        assertNull(a.name)
        assertNull(a.home)
        assertEquals(0, a.managementPort)
    }

    @Test
    fun rejectsInvalidInput() {
        assertTrue(bad().message!!.contains("--id is required"))
        assertTrue(bad("--id").message!!.contains("needs a value"))
        assertTrue(bad("--id", "../x").message!!.contains("must match"))
        assertTrue(bad("--id", "Upper").message!!.contains("must match"))
        assertTrue(bad("--id", "a", "--bogus").message!!.contains("unknown argument '--bogus'"))
        assertTrue(bad("--id", "a", "--management-port", "70000").message!!.contains("0..65535"))
        assertTrue(bad("--id", "a", "--management-port", "x").message!!.contains("0..65535"))
        assertTrue(bad("--id", "a", "--name", " ").message!!.contains("must not be blank"))
    }

    @Test
    fun homePrecedenceIsArgumentThenEnvironmentThenUserHome() {
        assertEquals(Paths.get("arg"), CringleHome.resolve(Paths.get("arg"), mapOf("CRINGLE_HOME" to "env")))
        assertEquals(Paths.get("env"), CringleHome.resolve(null, mapOf("CRINGLE_HOME" to "env")))
        assertEquals(Paths.get(System.getProperty("user.home"), ".cringle"), CringleHome.resolve(null, emptyMap()))
        assertEquals(Paths.get(System.getProperty("user.home"), ".cringle"), CringleHome.resolve(null, mapOf("CRINGLE_HOME" to " ")))
        assertEquals(Paths.get("h", "engines", "e1"), CringleHome.engineDir(Paths.get("h"), "e1"))
    }
}
