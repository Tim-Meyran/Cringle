// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.management.ManagementException
import cringle.router.users.Permission
import kotlinx.coroutines.CancellationException

internal fun fragment(content: Html): WebResponse = WebResponse.page(200, content)

/** An error message above a list; nothing for `null`. */
internal fun notice(message: String?): Html = if (message == null) Html("") else h("<p class=\"error\" role=\"alert\">{}</p>", message)

/** A success message above a list. */
internal fun info(message: String?): Html = if (message == null) Html("") else h("<p class=\"info\" role=\"status\">{}</p>", message)

/** Runs [action]; the error text (with the gRPC code) if it fails, `null` if not. */
internal suspend fun attempt(action: suspend () -> Unit): String? = try {
    action()
    null
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    describe(e)
}

internal fun describe(e: Exception): String = when (e) {
    is ManagementException -> "${e.code}: ${e.message}"
    is io.grpc.StatusException -> "${e.status.code}: ${e.status.description.orEmpty()}"
    else -> e.message ?: e.toString()
}

/** A button that posts to [url] and replaces the content of `#list`; not rendered if the session lacks [permission]. */
internal fun button(session: Session, permission: Permission, label: String, url: String, confirm: String? = null): Html =
    if (!session.can(permission)) {
        Html("")
    } else {
        h("<button hx-post=\"{}\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\"{}>{}</button> ", url, if (confirm != null) h(" hx-confirm=\"{}\"", confirm) else Html(""), label)
    }

internal fun Any.pretty(): String = toString().substringAfter("STATE_").lowercase()

/** A page body: the title block and the self-refreshing `#list`; [actions] stand on the right of the title. */
internal fun section(title: String, subtitle: String, name: String, initial: Html, actions: Html = Html("")): Html = html(
    pageHeader(title, subtitle, actions),
    h("<p id=\"paused\" class=\"paused\" role=\"status\" hidden>Refresh paused while you edit. <a href=\"/{}/list\" hx-get=\"/{}/list\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">Refresh now</a></p>", name, name),
    // the refresh morphs the list in place and waits while the user is working in it (see `cringleIdle` in app.js)
    h("<div id=\"list\" hx-get=\"/{}/list\" hx-trigger=\"every 5s [cringleIdle()]\" hx-swap=\"morph:innerHTML\">", name),
    initial,
    raw("</div>"),
)

/** A table with [headers]; with no [rows] the [empty] text (what to do next) instead. A header that is empty is the column of the actions. */
internal fun dataTable(headers: List<String>, rows: List<Html>, empty: Html): Html =
    if (rows.isEmpty()) {
        h("<p class=\"empty\">{}</p>", empty)
    } else {
        html(
            raw("<div class=\"table-wrap\"><table class=\"data\"><thead><tr>"),
            headers.map { if (it.isEmpty()) raw("<th class=\"actions\"><span class=\"sr-only\">Actions</span></th>") else h("<th>{}</th>", it) },
            raw("</tr></thead><tbody>"),
            rows,
            raw("</tbody></table></div>"),
        )
    }

/** The cell of a row with its buttons (right aligned); what the user may not use is not rendered, an empty cell stays empty. */
internal fun actionsCell(vararg buttons: Html): Html = h("<td class=\"actions\"><div class=\"row-actions\">{}</div></td>", buttons.toList())

/** A panel with a heading, an optional hint and a form: the place to create something. Not rendered (empty) if there is no [form]. */
internal fun formPanel(title: String, hint: String, form: Html): Html =
    if (form.value.isEmpty()) {
        Html("")
    } else {
        h("<section class=\"panel\"><h2>{}</h2>{}{}</section>", title, if (hint.isEmpty()) Html("") else h("<p class=\"hint\">{}</p>", hint), form)
    }

/** A labelled input for a form: the label above the control, [control] is the element. */
internal fun field(label: String, control: Html, hint: String = ""): Html =
    h("<label class=\"field\"><span>{}</span>{}{}</label>", label, control, if (hint.isEmpty()) Html("") else h("<small>{}</small>", hint))

/** A key fingerprint or another long hexadecimal id: the first and last characters, the whole value as the title; a click copies it (see `data-copy` in app.js). */
internal fun fingerprint(value: String): Html =
    if (value.length <= 20) h("<code>{}</code>", value) else h("<code class=\"fp\" data-copy=\"{}\" title=\"{} (click to copy)\">{}&hellip;{}</code>", value, value, value.take(10), value.takeLast(6))

/** Publishes the package [data] in the repository of [core] (the repository checks the package and the hash). */
internal suspend fun publishPackage(core: cringle.management.ManagementCore, data: ByteArray): cringle.repository.v1.PackageMetadata {
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    val requests = kotlinx.coroutines.flow.flow {
        emit(cringle.repository.v1.PublishRequest.newBuilder().setHeader(cringle.repository.v1.PublishHeader.newBuilder().setExpectedSha256(hash)).build())
        var offset = 0
        while (offset < data.size) {
            val n = minOf(64 * 1024, data.size - offset)
            emit(cringle.repository.v1.PublishRequest.newBuilder().setChunk(com.google.protobuf.ByteString.copyFrom(data, offset, n)).build())
            offset += n
        }
    }
    return core.repository().publishPackage(requests).metadata
}

/** The tones of a [badge]. */
internal enum class Tone { NEUTRAL, OK, WARN, BAD, INFO }

/** A small label that shows a state; the tone says how good it is (the color is never the only carrier: the text says it too). */
internal fun badge(text: String, tone: Tone = Tone.NEUTRAL): Html = h("<span class=\"badge {}\">{}</span>", tone.name.lowercase(), text)

/** The badge for a state word of an engine, a fabric, a block, a machine or a trust status. Unknown words are neutral. */
internal fun stateBadge(state: String): Html = badge(
    state.replace('_', ' '),
    when (state.lowercase()) {
        "running", "reachable", "trusted", "ok", "valid" -> Tone.OK
        "starting", "stopping", "created" -> Tone.WARN
        "failed", "migration_failed", "crashed", "not reachable", "untrusted", "violated" -> Tone.BAD
        else -> Tone.NEUTRAL
    },
)

/** The content of a page for a status that is not 200 (a missing page, a refused request), shown inside the frame. */
internal fun problemContent(title: String, text: String): Html =
    h("<div class=\"problem\"><h1>{}</h1><p class=\"subtitle\">{}</p><p><a class=\"btn\" href=\"/\">Back to the dashboard</a></p></div>", title, text)
