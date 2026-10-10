// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.text

import cringle.stdblocks.BOOLEAN
import cringle.stdblocks.DOUBLE
import cringle.stdblocks.Entry
import cringle.stdblocks.INT
import cringle.stdblocks.STRING
import cringle.stdblocks.asDouble
import cringle.stdblocks.asLong
import cringle.stdblocks.handlerEntry
import cringle.stdblocks.inPort
import cringle.stdblocks.latest2Entry
import cringle.stdblocks.long
import cringle.stdblocks.outPort

private fun Map<String, Any?>.requireText(name: String): String = this[name] as? String ?: throw IllegalArgumentException("the configuration '$name' is missing")

/** A block that sends `f(text)` on `out` for every text at `in`. */
private fun mapText(name: String, f: (String) -> String) = handlerEntry(name, listOf(inPort("in"), outPort("out"))) { _ -> { _, v, emit -> emit.send("out", f(v as String)) } }

/** A converter from the text at `in`: `out` gets the value, `error` the reason when the text does not fit. */
private fun parse(name: String, type: cringle.contract.SchemaRef, f: (String) -> Any?) =
    handlerEntry(name, listOf(inPort("in"), outPort("out", type), outPort("error"))) { _ ->
        { _, v, emit ->
            val text = v as String
            val parsed = f(text.trim())
            if (parsed != null) emit.send("out", parsed) else emit.send("error", "not a valid value: $text")
        }
    }

internal val TEXT_ENTRIES: List<Entry> = listOf(
    latest2Entry("text.concat", STRING, listOf(outPort("out")), "ConcatConfig") { config ->
        val separator = config["separator"] as? String ?: ""
        return@latest2Entry { a, b, emit -> emit.send("out", (a as String) + separator + (b as String)) }
    },
    handlerEntry("text.template", listOf(inPort("in"), outPort("out")), "TemplateConfig") { config ->
        val template = config.requireText("template")
        require("{}" in template) { "the configuration 'template' needs a '{}' where the text goes" }
        return@handlerEntry { _, v, emit -> emit.send("out", template.replace("{}", v as String)) }
    },
    handlerEntry("text.split", listOf(inPort("in"), outPort("part")), "SplitConfig") { config ->
        val separator = config.requireText("separator")
        require(separator.isNotEmpty()) { "the configuration 'separator' must not be empty" }
        return@handlerEntry { _, v, emit -> for (part in (v as String).split(separator)) emit.send("part", part) }
    },
    handlerEntry("text.replace", listOf(inPort("in"), outPort("out")), "ReplaceConfig") { config ->
        val find = config.requireText("find")
        require(find.isNotEmpty()) { "the configuration 'find' must not be empty" }
        val replacement = config["replacement"] as? String ?: ""
        val regex = if (config["regex"] as? Boolean == true) Regex(find) else null
        return@handlerEntry { _, v, emit -> emit.send("out", if (regex != null) regex.replace(v as String, replacement) else (v as String).replace(find, replacement)) }
    },
    handlerEntry("text.regex-match", listOf(inPort("in"), outPort("match"), outPort("nomatch")), "RegexMatchConfig") { config ->
        val regex = Regex(config.requireText("pattern"))
        val group = config.long("group")?.toInt() ?: 0
        return@handlerEntry { _, v, emit ->
            val found = regex.find(v as String)?.groups?.get(group)?.value
            if (found != null) emit.send("match", found) else emit.send("nomatch", v)
        }
    },
    mapText("text.trim") { it.trim() },
    mapText("text.upper") { it.uppercase() },
    mapText("text.lower") { it.lowercase() },
    handlerEntry("text.length", listOf(inPort("in"), outPort("out", INT))) { _ -> { _, v, emit -> emit.send("out", (v as String).length.toLong()) } },
    handlerEntry("text.contains", listOf(inPort("in"), outPort("yes"), outPort("no")), "ContainsConfig") { config ->
        val needle = config.requireText("needle")
        return@handlerEntry { _, v, emit -> emit.send(if (needle in (v as String)) "yes" else "no", v) }
    },
    parse("text.parse-int", INT) { it.toLongOrNull() },
    parse("text.parse-double", DOUBLE) { it.toDoubleOrNull()?.takeIf { d -> d.isFinite() } },
    parse("text.parse-boolean", BOOLEAN) { if (it.equals("true", true)) true else if (it.equals("false", true)) false else null },
    handlerEntry("text.to-string", listOf(inPort("in", INT), outPort("out"))) { _ -> { _, v, emit -> emit.send("out", v.asLong().toString()) } },
    handlerEntry("text.double-to-string", listOf(inPort("in", DOUBLE), outPort("out"))) { _ -> { _, v, emit -> emit.send("out", v.asDouble().toString()) } },
    handlerEntry("text.boolean-to-string", listOf(inPort("in", BOOLEAN), outPort("out"))) { _ -> { _, v, emit -> emit.send("out", (v as Boolean).toString()) } },
)
