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

/** `cringle setup`: the settings of the services of this machine, written to the settings file of the daemon (#316). */
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

    private val text = "# Settings\nbind=loopback\n"

    private fun file(): Path = dir.resolve("config").resolve("cringle.conf").also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
    }

    @Test
    fun optionsChangeOnlyTheNamedSettingsAndKeepTheRestOfTheFile() {
        val file = file()
        val r = cli("", "--config-file", file.toString(), "--bind", "all", "--web-port", "9443", "--web-url", "https://cringle.example:9443", "--no-restart")
        assertEquals(0, r.code, r.err)
        assertEquals("# Settings\nbind=all\nmanagement.web.port=9443\nmanagement.web.url=https://cringle.example:9443\n", Files.readString(file))
        assertTrue(r.out.contains("9443") && r.out.contains("all"), r.out)
        assertTrue(r.err.contains("until it is restarted"), r.err)
    }

    @Test
    fun withoutOptionsEachSettingIsAskedAndAnEmptyAnswerKeepsIt() {
        val file = file()
        // the keys in the order of the catalog: bind, components, daemon.port, management.port, management.web.port, management.web.url, repository.port
        val r = cli("all\nmanagement\n\n\n9443\n\n\n", "--config-file", file.toString(), "--no-restart")
        assertEquals(0, r.code, r.err)
        assertEquals("# Settings\nbind=all\ncomponents=management\nmanagement.web.port=9443\n", Files.readString(file))
    }

    @Test
    fun showChangesNothingAndBadValuesAreRefusedBeforeAnythingIsWritten() {
        val file = file()
        val shown = cli("", "--config-file", file.toString(), "--show")
        assertEquals(0, shown.code, shown.err)
        assertTrue(shown.out.contains("7500") && shown.out.contains("loopback"), shown.out)
        assertEquals(text, Files.readString(file))
        val bad = listOf(
            listOf("--port", "0"), listOf("--web-port", "70000"), listOf("--port", "abc"), listOf("--bind", "a b"), listOf("--bind", "192.0.2.7"),
            listOf("--components", "management,router"), listOf("--web-url", "https://x/path"), listOf("--web-url", "http://x"),
        )
        for (arguments in bad) {
            val r = cli("", "--config-file", file.toString(), "--no-restart", *arguments.toTypedArray())
            assertEquals(2, r.code, "$arguments ${r.err}")
        }
        assertEquals(text, Files.readString(file))
        assertEquals(2, cli("", "--config-file", dir.resolve("nowhere").resolve("cringle.conf").toString(), "--show").code)
    }

    @Test
    fun theComponentsAreChosenInAnyOrderAndWrittenInTheUsualOne() {
        val file = file()
        assertEquals(0, cli("", "--config-file", file.toString(), "--components", "repository, management", "--no-restart").code)
        assertTrue(Files.readString(file).contains("components=management,repository\n"))
        assertEquals(0, cli("", "--config-file", file.toString(), "--components", "none", "--no-restart").code)
        assertTrue(Files.readString(file).contains("components=none\n"))
    }
}
