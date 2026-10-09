// SPDX-License-Identifier: Apache-2.0

package cringle.cli

import java.nio.file.Files
import java.nio.file.Path

/**
 * The settings of an installed Cringle service that `cringle setup` changes and the installers write (docs/daemon-service.md): the address the
 * servers listen on and the four ports. On Linux they are lines `KEY=value` in `/etc/cringle/cringle.env` (the unit refers to them as `${KEY}`),
 * on Windows `<env name="KEY" value="value"/>` elements of the WinSW file of the daemon (the arguments refer to them as `%KEY%`).
 */
internal object ServiceSettings {
    const val BIND = "CRINGLE_BIND"
    const val DAEMON_PORT = "CRINGLE_DAEMON_PORT"
    const val MANAGEMENT_PORT = "CRINGLE_MANAGEMENT_PORT"
    const val WEB_PORT = "CRINGLE_WEB_PORT"
    const val REPOSITORY_PORT = "CRINGLE_REPOSITORY_PORT"

    /** The settings with their defaults, in the order they are written. */
    val DEFAULTS: Map<String, String> = linkedMapOf(
        BIND to "loopback",
        DAEMON_PORT to "7400",
        MANAGEMENT_PORT to "7500",
        WEB_PORT to "8443",
        REPOSITORY_PORT to "7600",
    )

    private val ENV_LINE = Regex("^\\s*([A-Z_]+)=(.*)$")
    private val XML_ENV = Regex("<env name=\"([A-Z_]+)\" value=\"([^\"]*)\"\\s*/>")

    /** Checks the value of a setting; returns the problem or `null`. */
    fun problem(key: String, value: String): String? = when (key) {
        BIND -> if (value.isBlank() || value.any { it.isWhitespace() || it == '"' || it == '\'' }) "the address is empty or has a blank or a quote" else null
        else -> if (value.toIntOrNull()?.let { it in 1..65535 } == true) null else "$value is not a port (1 to 65535)"
    }

    /** The values in [text] of an env file; settings that are not in it are not in the result. */
    fun readEnv(text: String): Map<String, String> =
        text.lineSequence().mapNotNull { ENV_LINE.matchEntire(it) }.filter { it.groupValues[1] in DEFAULTS }.associate { it.groupValues[1] to it.groupValues[2].trim() }

    /** The values in the WinSW file [text]. */
    fun readXml(text: String): Map<String, String> =
        XML_ENV.findAll(text).filter { it.groupValues[1] in DEFAULTS }.associate { it.groupValues[1] to it.groupValues[2] }

    /** [text] with [changes] set: a line of a key is replaced, a missing key is appended; every other line stays as it is. */
    fun updateEnv(text: String, changes: Map<String, String>): String {
        val pending = LinkedHashMap(changes)
        val lines = text.lines().toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        for (i in lines.indices) {
            val key = ENV_LINE.matchEntire(lines[i])?.groupValues?.get(1) ?: continue
            pending.remove(key)?.let { lines[i] = "$key=$it" }
        }
        for ((key, value) in pending) lines += "$key=$value"
        return lines.joinToString("\n") + "\n"
    }

    /** The WinSW file [text] with [changes] set: the `<env>` of a key gets the value, a missing one is added after the `CRINGLE_HOME` element. */
    fun updateXml(text: String, changes: Map<String, String>): String {
        var result = text
        for ((key, value) in changes) {
            val element = "<env name=\"$key\" value=\"${xmlEscape(value)}\"/>"
            val existing = Regex("<env name=\"$key\" value=\"[^\"]*\"\\s*/>")
            result = if (existing.containsMatchIn(result)) {
                existing.replace(result) { element }
            } else {
                val home = Regex("(<env name=\"CRINGLE_HOME\" value=\"[^\"]*\"\\s*/>)").find(result) ?: throw IllegalStateException("no CRINGLE_HOME in the service file")
                result.replaceRange(home.range, home.value + "\n  " + element)
            }
        }
        return result
    }

    private fun xmlEscape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** The file `cringle setup` changes by default: the env file on Linux, the WinSW file of the daemon in [installRoot] on Windows. */
    fun defaultFile(platform: Platform, installRoot: Path?): Path = when (platform) {
        Platform.WINDOWS -> (installRoot ?: throw UsageException("not an installed distribution: use --config-file <file>")).resolve("service").resolve("cringle-daemon.xml")
        else -> Path.of("/etc/cringle/cringle.env")
    }

    /** Reads the current values of [file] (with the defaults for settings that it does not have). */
    fun read(file: Path, platform: Platform): Map<String, String> {
        val text = Files.readString(file)
        val found = if (file.fileName.toString().endsWith(".xml")) readXml(text) else readEnv(text)
        return DEFAULTS + found
    }

    /** Writes [changes] into [file]. */
    fun write(file: Path, changes: Map<String, String>) {
        val text = Files.readString(file)
        val next = if (file.fileName.toString().endsWith(".xml")) updateXml(text, changes) else updateEnv(text, changes)
        Files.writeString(file, next)
    }
}
