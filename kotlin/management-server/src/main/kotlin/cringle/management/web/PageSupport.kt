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
        h("<button hx-post=\"{}\" hx-target=\"#list\" hx-swap=\"innerHTML\"{}>{}</button> ", url, if (confirm != null) h(" hx-confirm=\"{}\"", confirm) else Html(""), label)
    }

internal fun Any.pretty(): String = toString().substringAfter("STATE_").lowercase()

/** A page body with a heading and the self-refreshing `#list`. */
internal fun section(title: String, name: String, initial: Html): Html = html(
    h("<h1>{}</h1>", title),
    h("<div id=\"list\" hx-get=\"/{}/list\" hx-trigger=\"every 5s\" hx-swap=\"innerHTML\">", name),
    initial,
    raw("</div>"),
)
