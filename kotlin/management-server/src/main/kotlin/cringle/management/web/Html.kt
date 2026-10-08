// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

/** HTML that is already safe to put into a page: produced by [html] / [esc] only. */
public class Html internal constructor(public val value: String) {
    override fun toString(): String = value

    public operator fun plus(other: Html): Html = Html(value + other.value)
}

/** Escapes [text] for text and attribute values. */
public fun esc(text: Any?): String {
    val s = text?.toString() ?: return ""
    val out = StringBuilder(s.length + 8)
    for (c in s) {
        when (c) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            '\'' -> out.append("&#39;")
            else -> out.append(c)
        }
    }
    return out.toString()
}

/**
 * Joins [parts] to HTML: an [Html] is inserted as it is, an [Iterable] is joined, everything else is escaped. Literal markup is passed
 * as [raw] or built with [h].
 */
public fun html(vararg parts: Any?): Html {
    val sb = StringBuilder()
    for (p in parts) {
        when (p) {
            null -> {}
            is Html -> sb.append(p.value)
            is Iterable<*> -> p.forEach { sb.append(html(it).value) }
            else -> sb.append(esc(p))
        }
    }
    return Html(sb.toString())
}

/**
 * A template with `{}` placeholders: the template is program text and used as it is, every value is escaped (an [Html] value is inserted
 * as it is). `h("<td title=\"{}\">{}</td>", title, name)`.
 */
public fun h(template: String, vararg values: Any?): Html {
    val sb = StringBuilder()
    var next = 0
    var i = 0
    while (i < template.length) {
        if (template.startsWith("{}", i)) {
            require(next < values.size) { "template has more placeholders than values" }
            sb.append(html(values[next++]).value)
            i += 2
        } else {
            sb.append(template[i])
            i += 1
        }
    }
    require(next == values.size) { "template has fewer placeholders than values" }
    return Html(sb.toString())
}

/** Markup written by the program itself (never from input). */
public fun raw(markup: String): Html = Html(markup)
