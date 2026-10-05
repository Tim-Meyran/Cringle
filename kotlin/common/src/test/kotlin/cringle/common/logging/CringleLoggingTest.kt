// SPDX-License-Identifier: Apache-2.0

package cringle.common.logging

import ch.qos.logback.classic.LoggerContext
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory

class CringleLoggingTest {
    @TempDir
    lateinit var dir: Path

    @AfterEach
    fun resetLogback() {
        (LoggerFactory.getILoggerFactory() as LoggerContext).reset()
    }

    private fun logFile(component: String, name: String): Path =
        dir.resolve("logs").resolve("$component-$name.log")

    @Test
    fun initCreatesLogFileWithLines() {
        CringleLogging.init(dir, "daemon", "main", emptyMap())
        val log = LoggerFactory.getLogger("test.logger")
        log.info("hello world")
        val file = logFile("daemon", "main")
        assertTrue(Files.exists(file), "expected $file to exist")
        val content = Files.readString(file)
        assertTrue(content.contains("hello world"), "expected 'hello world' in:\n$content")
        assertTrue(content.contains("INFO"), "expected 'INFO' in:\n$content")
    }

    @Test
    fun differentComponentsAndNamesGiveSeparateFiles() {
        CringleLogging.init(dir, "daemon", "alpha", emptyMap())
        LoggerFactory.getLogger("test").info("from daemon-alpha")
        CringleLogging.init(dir, "engine", "beta", emptyMap())
        LoggerFactory.getLogger("test").info("from engine-beta")
        assertTrue(Files.exists(logFile("daemon", "alpha")))
        assertTrue(Files.exists(logFile("engine", "beta")))
        assertTrue(Files.readString(logFile("daemon", "alpha")).contains("from daemon-alpha"))
        assertTrue(Files.readString(logFile("engine", "beta")).contains("from engine-beta"))
    }

    @Test
    fun cringleLogLevelEnvChangesRootLevel() {
        CringleLogging.init(dir, "daemon", "main", mapOf(CringleLogging.CRINGLE_LOG_LEVEL_ENV to "DEBUG"))
        LoggerFactory.getLogger("test.debug").debug("debug-visible")
        assertTrue(Files.readString(logFile("daemon", "main")).contains("debug-visible"))

        CringleLogging.init(dir, "daemon", "main", mapOf(CringleLogging.CRINGLE_LOG_LEVEL_ENV to "WARN"))
        LoggerFactory.getLogger("test.warn").info("info-hidden")
        assertFalse(Files.readString(logFile("daemon", "main")).contains("info-hidden"))
    }

    @Test
    fun invalidLogLevelFallsBackToInfo() {
        CringleLogging.init(dir, "daemon", "main", mapOf(CringleLogging.CRINGLE_LOG_LEVEL_ENV to "BOGUS"))
        LoggerFactory.getLogger("test.invalid").debug("debug-hidden")
        assertFalse(Files.readString(logFile("daemon", "main")).contains("debug-hidden"))
    }

    @Test
    fun loggingPropertiesChangesLoggerLevel() {
        Files.writeString(dir.resolve(CringleLogging.LOGGING_PROPERTIES), "level.test.specific=DEBUG\n")
        CringleLogging.init(dir, "daemon", "main", emptyMap())
        LoggerFactory.getLogger("test.specific").debug("debug-visible")
        assertTrue(Files.readString(logFile("daemon", "main")).contains("debug-visible"))
    }

    @Test
    fun exceedingSizeLimitProducesRotatedGzFile() {
        CringleLogging.init(dir, "daemon", "main", emptyMap(), maxFileSizeBytes = 1024L, maxHistoryDays = 2, totalSizeCapBytes = 4096L)
        val log = LoggerFactory.getLogger("test.rotation")
        repeat(200) { log.info("line $it with some padding to exceed the size limit quickly") }
        val gzFiles = Files.list(dir.resolve("logs")).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".gz") }.toList()
        }
        assertTrue(gzFiles.isNotEmpty(), "expected at least one .gz rotated file in ${dir.resolve("logs")}")
    }

    @Test
    fun maskSecretsHidesPasswordsAndTokens() {
        assertEquals("password=***", CringleLogging.maskSecrets("password=hunter2"))
        assertEquals("password: ***", CringleLogging.maskSecrets("password: hunter2"))
        assertEquals("\"password\":\"***\"", CringleLogging.maskSecrets("\"password\":\"hunter2\""))
        assertEquals("'password':'***'", CringleLogging.maskSecrets("'password':'hunter2'"))
        assertEquals("Authorization: Bearer ***", CringleLogging.maskSecrets("Authorization: Bearer abc.def.ghi"))
        assertEquals("token=***", CringleLogging.maskSecrets("token=abc123"))
        assertEquals("api-key=***", CringleLogging.maskSecrets("api-key=xyz"))
        assertEquals("apikey=***", CringleLogging.maskSecrets("apikey=xyz"))
        val pem = "-----BEGIN PRIVATE KEY-----\nMIIBV...\n-----END PRIVATE KEY-----"
        assertEquals("***", CringleLogging.maskSecrets(pem))
        assertEquals("hello world", CringleLogging.maskSecrets("hello world"))
    }

    @Test
    fun secretsInLoggedMessagesAreMasked() {
        CringleLogging.init(dir, "daemon", "main", emptyMap())
        LoggerFactory.getLogger("test.secrets").info("connecting with password=hunter2")
        val content = Files.readString(logFile("daemon", "main"))
        assertTrue(content.contains("password=***"), "expected 'password=***' in:\n$content")
        assertFalse(content.contains("hunter2"), "expected 'hunter2' to be masked in:\n$content")
    }

    @Test
    fun secretsInStackTracesAreMasked() {
        CringleLogging.init(dir, "daemon", "main", emptyMap())
        val ex = RuntimeException("failed with token=abc123")
        LoggerFactory.getLogger("test.exceptions").error("operation failed", ex)
        val content = Files.readString(logFile("daemon", "main"))
        assertTrue(content.contains("token=***"), "expected 'token=***' in:\n$content")
        assertFalse(content.contains("abc123"), "expected 'abc123' to be masked in:\n$content")
    }

    @Test
    fun initIsIdempotent() {
        CringleLogging.init(dir, "daemon", "main", emptyMap())
        CringleLogging.init(dir, "daemon", "main", emptyMap())
        LoggerFactory.getLogger("test.idempotent").info("still works")
        assertTrue(Files.readString(logFile("daemon", "main")).contains("still works"))
    }

    @Test
    fun invalidComponentThrows() {
        assertThrows<IllegalArgumentException> {
            CringleLogging.init(dir, "router", "main", emptyMap())
        }
    }

    @Test
    fun nameIsSanitized() {
        CringleLogging.init(dir, "daemon", "weird/name with spaces", emptyMap())
        assertTrue(Files.exists(dir.resolve("logs").resolve("daemon-weird_name_with_spaces.log")))
    }
}
