// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import java.nio.file.Path
import java.nio.file.Paths

/** Thrown for invalid command line arguments; the message is meant for the person starting the engine. */
public class EngineArgsException(message: String) : RuntimeException(message)

/** Parsed command line of an engine process. */
public data class EngineArgs(
    val id: String,
    val name: String?,
    val home: Path?,
    val managementPort: Int,
) {
    public companion object {
        /** Engine ids become directory names, so they are restricted to a safe grammar. */
        public val idPattern: Regex = Regex("[a-z0-9][a-z0-9-]{0,62}")

        /** Usage text printed on argument errors. */
        public const val USAGE: String =
            "usage: engine --id <id> [--name <name>] [--home <dir>] [--management-port <port>]"

        /** Parses [args]; throws [EngineArgsException] for unknown, missing or malformed arguments. */
        public fun parse(args: List<String>): EngineArgs {
            var id: String? = null
            var name: String? = null
            var home: Path? = null
            var port = 0
            var i = 0
            fun value(option: String): String {
                if (i + 1 >= args.size) throw EngineArgsException("$option needs a value")
                i += 1
                return args[i]
            }
            while (i < args.size) {
                when (val option = args[i]) {
                    "--id" -> id = value(option)
                    "--name" -> name = value(option)
                    "--home" -> home = Paths.get(value(option))
                    "--management-port" -> {
                        val text = value(option)
                        port = text.toIntOrNull()?.takeIf { it in 0..65535 }
                            ?: throw EngineArgsException("--management-port must be 0..65535, got '$text'")
                    }
                    else -> throw EngineArgsException("unknown argument '$option'")
                }
                i += 1
            }
            val engineId = id ?: throw EngineArgsException("--id is required")
            if (!idPattern.matches(engineId)) {
                throw EngineArgsException("--id '$engineId' must match ${idPattern.pattern}")
            }
            if (name != null && name.isBlank()) throw EngineArgsException("--name must not be blank")
            return EngineArgs(engineId, name, home, port)
        }
    }
}

/** Locates the Cringle home directory (`~/.cringle` by default). */
public object CringleHome {
    /** Name of the environment variable that overrides the default location. */
    public const val ENV: String = "CRINGLE_HOME"

    /** Returns [explicit] if given, else the value of `CRINGLE_HOME` in [env], else `<user.home>/.cringle`. */
    public fun resolve(explicit: Path?, env: Map<String, String> = System.getenv()): Path =
        explicit ?: env[ENV]?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".cringle")

    /** Directory of the engine [id] below [home]: `<home>/engines/<id>`. */
    public fun engineDir(home: Path, id: String): Path = home.resolve("engines").resolve(id)
}
