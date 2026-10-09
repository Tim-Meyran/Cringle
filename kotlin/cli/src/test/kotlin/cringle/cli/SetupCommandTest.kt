// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `cringle setup` and the settings files it changes (the env file of Linux and the WinSW file of Windows). */
class SetupCommandTest {
    @TempDir
    lateinit var dir: Path

    private class Result(val code: Int, val out: String, val err: String)

    private fun cli(stdin: String, vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Cli(PrintStream(out, true), PrintStream(err, true), ByteArrayInputStream(stdin.toByteArray()), mapOf()).run(listOf("setup") + args.toList())
        return Result(code, out.toString(), err.toString())
    }

    private val envText = "# Environment\nJAVA_HOME=/my/jdk\nCRINGLE_BIND=loopback\n"

    private fun envFile(): Path = dir.resolve("cringle.env").also { Files.writeString(it, envText) }

    @Test
    fun optionsChangeOnlyTheNamedSettingsAndKeepTheRestOfTheFile() {
        val file = envFile()
        val r = cli("", "--config-file", file.toString(), "--bind", "all", "--web-port", "9443", "--no-restart")
        assertEquals(0, r.code, r.err)
        assertEquals("# Environment\nJAVA_HOME=/my/jdk\nCRINGLE_BIND=all\nCRINGLE_WEB_PORT=9443\n", Files.readString(file))
        assertTrue(r.out.contains("9443") && r.out.contains("all"), r.out)
        assertTrue(r.err.contains("until they are restarted"), r.err)
    }

    @Test
    fun withoutOptionsEachSettingIsAskedAndAnEmptyAnswerKeepsIt() {
        val file = envFile()
        // bind, management port, web port, repository port, daemon port
        val r = cli("all\n\n9443\n\n\n", "--config-file", file.toString(), "--no-restart")
        assertEquals(0, r.code, r.err)
        assertEquals("# Environment\nJAVA_HOME=/my/jdk\nCRINGLE_BIND=all\nCRINGLE_WEB_PORT=9443\n", Files.readString(file))
    }

    @Test
    fun showChangesNothingAndBadValuesAreRefusedBeforeAnythingIsWritten() {
        val file = envFile()
        val shown = cli("", "--config-file", file.toString(), "--show")
        assertEquals(0, shown.code, shown.err)
        assertTrue(shown.out.contains("7500") && shown.out.contains("loopback"), shown.out)
        assertEquals(envText, Files.readString(file))
        for (bad in listOf(listOf("--port", "0"), listOf("--web-port", "70000"), listOf("--port", "abc"), listOf("--bind", "a b"))) {
            val r = cli("", "--config-file", file.toString(), "--no-restart", *bad.toTypedArray())
            assertEquals(2, r.code, "$bad ${r.err}")
        }
        assertEquals(envText, Files.readString(file))
        assertEquals(2, cli("", "--config-file", dir.resolve("missing.env").toString(), "--show").code)
    }

    @Test
    fun theWinswFileGetsTheValuesAsEnvElements() {
        val xml = "<service>\n  <id>cringle-daemon</id>\n  <env name=\"CRINGLE_HOME\" value=\"C:\\ProgramData\\Cringle\"/>\n  <env name=\"CRINGLE_WEB_PORT\" value=\"8443\"/>\n  <logpath>x</logpath>\n</service>\n"
        val file = dir.resolve("cringle-daemon.xml").also { Files.writeString(it, xml) }
        val r = cli("", "--config-file", file.toString(), "--bind", "all", "--web-port", "9443", "--no-restart")
        assertEquals(0, r.code, r.err)
        val after = Files.readString(file)
        assertTrue(after.contains("<env name=\"CRINGLE_WEB_PORT\" value=\"9443\"/>") && !after.contains("8443"), after)
        assertTrue(after.contains("<env name=\"CRINGLE_BIND\" value=\"all\"/>"), after)
        assertTrue(after.indexOf("CRINGLE_HOME") < after.indexOf("CRINGLE_BIND") && after.contains("<logpath>x</logpath>"), after)
        assertEquals("9443", ServiceSettings.read(file, Platform.WINDOWS)[ServiceSettings.WEB_PORT])
    }
}
