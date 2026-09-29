// SPDX-License-Identifier: Apache-2.0

package cringle.cli

/** The command line is wrong; reported with exit code 2 and the usage of the command. */
internal class UsageException(message: String) : RuntimeException(message)

/** One option of a command. [takesValue] options are written `--name value` or `--name=value`. */
internal data class OptionSpec(val name: String, val description: String, val takesValue: Boolean = true, val repeatable: Boolean = false, val placeholder: String = "VALUE")

/** The parsed arguments of one command. */
internal class Parsed(val positional: List<String>, private val values: Map<String, List<String>>, private val flags: Set<String>) {
    fun flag(name: String): Boolean = name in flags

    fun option(name: String): String? = values[name]?.lastOrNull()

    fun options(name: String): List<String> = values[name].orEmpty()

    fun long(name: String): Long? = option(name)?.let { it.toLongOrNull() ?: throw UsageException("--$name must be a number, not '$it'") }

    /** `key=value` options as a map. */
    fun pairs(name: String): Map<String, String> = options(name).associate {
        if ('=' !in it) throw UsageException("--$name needs key=value, not '$it'")
        it.substringBefore('=') to it.substringAfter('=')
    }
}

internal object ArgParser {
    /** Splits [args] into positional arguments and the options described by [specs]. Unknown options are an error. */
    fun parse(args: List<String>, specs: List<OptionSpec>): Parsed {
        val byName = specs.associateBy { it.name }
        val positional = ArrayList<String>()
        val values = LinkedHashMap<String, MutableList<String>>()
        val flags = HashSet<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "--") {
                positional += args.drop(i + 1)
                break
            }
            if (a.startsWith("--") && a.length > 2) {
                val name = a.removePrefix("--").substringBefore('=')
                val spec = byName[name] ?: throw UsageException("unknown option --$name")
                if (spec.takesValue) {
                    val value = if ('=' in a) a.substringAfter('=') else args.getOrNull(++i) ?: throw UsageException("--$name needs a value")
                    val list = values.getOrPut(name) { ArrayList() }
                    if (list.isNotEmpty() && !spec.repeatable) throw UsageException("--$name may be given once only")
                    list += value
                } else {
                    if ('=' in a) throw UsageException("--$name takes no value")
                    flags += name
                }
            } else {
                positional += a
            }
            i++
        }
        return Parsed(positional, values, flags)
    }
}
