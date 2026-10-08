// SPDX-License-Identifier: Apache-2.0

package cringle.common.logging

import ch.qos.logback.classic.Level as LogbackLevel
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.spi.FilterReply
import ch.qos.logback.core.util.FileSize
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

public object CringleLogging {
    public const val MAX_FILE_SIZE_BYTES: Long = 100L * 1024 * 1024
    public const val MAX_HISTORY_DAYS: Int = 14
    public const val TOTAL_SIZE_CAP_BYTES: Long = 2L * 1024 * 1024 * 1024
    public const val CRINGLE_LOG_LEVEL_ENV: String = "CRINGLE_LOG_LEVEL"
    public const val LOGGING_PROPERTIES: String = "logging.properties"

    private val VALID_COMPONENTS = setOf("daemon", "engine", "management")
    private val warnedInvalidLevels = mutableSetOf<String>()
    private val warnedInvalidPropertyLines = mutableSetOf<String>()

    /**
     * Logging of the command line: only warnings and errors of the libraries (gRPC and Netty log at DEBUG to the standard output
     * by default, which would be mixed into the output of a command and into `--json`), in the form `message`, on the standard
     * error. [CRINGLE_LOG_LEVEL_ENV] sets another level.
     */
    public fun initCommandLine(env: Map<String, String> = System.getenv()) {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        context.reset()
        val encoder = PatternLayoutEncoder().apply {
            this.context = context
            this.pattern = "%msg%n"
            start()
        }
        val maskingFilter = SecretMaskingFilter().apply {
            this.context = context
            start()
        }
        val stderr = ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
            this.context = context
            this.target = "System.err"
            this.encoder = encoder
            this.addFilter(maskingFilter)
            start()
        }
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        root.level = parseLevel(env[CRINGLE_LOG_LEVEL_ENV], fallback = LogbackLevel.WARN)
        root.addAppender(stderr)
    }

    public fun init(home: Path, component: String, name: String, env: Map<String, String> = System.getenv()) {
        init(home, component, name, env, MAX_FILE_SIZE_BYTES, MAX_HISTORY_DAYS, TOTAL_SIZE_CAP_BYTES)
    }

    internal fun init(
        home: Path,
        component: String,
        name: String,
        env: Map<String, String>,
        maxFileSizeBytes: Long,
        maxHistoryDays: Int,
        totalSizeCapBytes: Long,
    ) {
        require(component in VALID_COMPONENTS) {
            "component must be one of $VALID_COMPONENTS, got '$component'"
        }
        val safeName = name.map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("").ifEmpty { "unnamed" }
        val logsDir = home.resolve("logs")
        Files.createDirectories(logsDir)
        val logFile = logsDir.resolve("$component-$safeName.log")

        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        context.reset()
        context.name = "cringle"
        context.putProperty("HOME", home.toString())

        val pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger - %msg%n"

        val encoder = PatternLayoutEncoder().apply {
            this.context = context
            this.pattern = pattern
            start()
        }

        val maskingFilter = SecretMaskingFilter().apply {
            this.context = context
            start()
        }

        val fileAppender = RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
            this.context = context
            this.file = logFile.toString()
            this.encoder = encoder
            this.addFilter(maskingFilter)
        }
        val policy = SizeAndTimeBasedRollingPolicy<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
            this.context = context
            this.fileNamePattern = "$logFile.%d{yyyy-MM-dd}.%i.log.gz"
            setMaxFileSize(FileSize(maxFileSizeBytes))
            this.maxHistory = maxHistoryDays
            setTotalSizeCap(FileSize(totalSizeCapBytes))
            setParent(fileAppender)
            start()
        }
        fileAppender.rollingPolicy = policy
        fileAppender.start()

        val stderrAppender = ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
            this.context = context
            this.target = "System.err"
            this.encoder = encoder
            this.addFilter(maskingFilter)
            start()
        }

        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        root.level = parseLevel(env[CRINGLE_LOG_LEVEL_ENV], fallback = LogbackLevel.INFO)
        root.addAppender(fileAppender)
        root.addAppender(stderrAppender)

        val propsFile = home.resolve(LOGGING_PROPERTIES)
        if (Files.exists(propsFile)) {
            for (line in Files.readAllLines(propsFile)) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val eq = trimmed.indexOf('=')
                if (eq <= 0) {
                    warnOnce("invalid logging.properties line (no '='): $trimmed", warnedInvalidPropertyLines)
                    continue
                }
                val key = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (!key.startsWith("level.")) {
                    warnOnce("unsupported logging.properties key '$key'", warnedInvalidPropertyLines)
                    continue
                }
                val loggerName = key.removePrefix("level.")
                val parsed = parseLevel(value, fallback = null)
                if (parsed == null) {
                    warnOnce("invalid level '$value' for logger '$loggerName'", warnedInvalidPropertyLines)
                    continue
                }
                context.getLogger(loggerName).level = parsed
            }
        }
    }

    public fun maskSecrets(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        // PEM private key blocks
        result = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----").replace(result, "***")
        // Bearer token
        result = Regex("(?i)Bearer\\s+\\S+").replace(result, "Bearer ***")
        // JSON-style: "key":"value" or 'key':'value'
        result = Regex("(?i)(\"(?:password|passwd|secret|token|apikey|api[-_]key|authorization)\"\\s*:\\s*)\"([^\"]*)\"").replace(result) { m -> m.groupValues[1] + "\"***\"" }
        result = Regex("(?i)('(?:password|passwd|secret|token|apikey|api[-_]key|authorization)'\\s*:\\s*)'([^']*)'").replace(result) { m -> m.groupValues[1] + "'***'" }
        // k=v (no quotes)
        result = Regex("(?i)\\b(password|passwd|secret|token|apikey|api[-_]key|authorization)\\s*=\\s*(\\S+)").replace(result) { m -> m.groupValues[1] + "=***" }
        // k: v (no quotes, value is non-whitespace token; skip when value starts with "Bearer" so the Bearer rule above wins)
        result = Regex("(?i)\\b(password|passwd|secret|token|apikey|api[-_]key|authorization)\\s*:\\s*(?!Bearer\\s)(\\S+)").replace(result) { m -> m.groupValues[1] + ": ***" }
        return result
    }

    private fun parseLevel(value: String?, fallback: LogbackLevel?): LogbackLevel? {
        if (value == null) return fallback
        val upper = value.uppercase()
        return when (upper) {
            "DEBUG" -> LogbackLevel.DEBUG
            "INFO" -> LogbackLevel.INFO
            "WARN", "WARNING" -> LogbackLevel.WARN
            "ERROR" -> LogbackLevel.ERROR
            else -> {
                warnOnce("invalid log level '$value', using INFO", warnedInvalidLevels)
                fallback ?: LogbackLevel.INFO
            }
        }
    }

    private fun warnOnce(message: String, sink: MutableSet<String>) {
        if (message in sink) return
        sink.add(message)
        // best-effort: print to stderr so the warning is visible even before logging is fully configured
        System.err.println("WARNING: $message")
    }
}

private class SecretMaskingFilter : Filter<ch.qos.logback.classic.spi.ILoggingEvent>() {
    override fun decide(event: ch.qos.logback.classic.spi.ILoggingEvent): FilterReply {
        val mutable = event as? ch.qos.logback.classic.spi.LoggingEvent ?: return FilterReply.NEUTRAL
        val original = mutable.formattedMessage
        val masked = CringleLogging.maskSecrets(original)
        if (masked != original) {
            val field = ch.qos.logback.classic.spi.LoggingEvent::class.java.getDeclaredField("formattedMessage")
            field.isAccessible = true
            field.set(mutable, masked)
        }
        val tp = mutable.throwableProxy
        if (tp != null) {
            val originalMessage = tp.message
            val maskedMessage = CringleLogging.maskSecrets(originalMessage)
            if (maskedMessage != originalMessage) {
                val maskedThrowable = Throwable(maskedMessage)
                val newTp = ch.qos.logback.classic.spi.ThrowableProxy(maskedThrowable)
                newTp.calculatePackagingData()
                val field = ch.qos.logback.classic.spi.LoggingEvent::class.java.getDeclaredField("throwableProxy")
                field.isAccessible = true
                field.set(mutable, newTp)
            }
        }
        return FilterReply.NEUTRAL
    }
}
