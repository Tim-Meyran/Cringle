// SPDX-License-Identifier: Apache-2.0

package cringle.common.logging

import ch.qos.logback.classic.LoggerContext
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** The logging of the command line keeps the libraries out of the output of a command. */
class CommandLineLoggingTest {
    private val realOut = System.out
    private val realErr = System.err

    @AfterEach
    fun restore() {
        System.setOut(realOut)
        System.setErr(realErr)
        (LoggerFactory.getILoggerFactory() as LoggerContext).reset()
    }

    private fun capture(body: () -> Unit): Pair<String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(err, true))
        body()
        return out.toString().trim() to err.toString().trim()
    }

    @Test
    fun debugAndInfoOfALibraryAreNotPrintedAndAWarningGoesToTheStandardErrorOnly() {
        val (out, err) = capture {
            CringleLogging.initCommandLine(emptyMap())
            val log = LoggerFactory.getLogger("io.grpc.netty.shaded.test")
            log.debug("Using SLF4J as the default logging framework")
            log.info("an information")
            log.warn("a warning")
        }
        assertEquals("", out, "nothing on the standard output: it is the output of the command")
        assertEquals("a warning", err)
    }

    @Test
    fun theLevelCanBeRaisedWithTheEnvironment() {
        val (out, err) = capture {
            CringleLogging.initCommandLine(mapOf(CringleLogging.CRINGLE_LOG_LEVEL_ENV to "DEBUG"))
            LoggerFactory.getLogger("test").debug("a detail")
        }
        assertEquals("", out)
        assertEquals("a detail", err)
    }

    @Test
    fun aSecretInAMessageIsMasked() {
        val (_, err) = capture {
            CringleLogging.initCommandLine(emptyMap())
            LoggerFactory.getLogger("test").warn("authorization: Bearer abc123def456")
        }
        assertEquals(false, err.contains("abc123def456"), err)
    }
}
