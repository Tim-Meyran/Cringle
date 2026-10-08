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

/** A page body with a heading and the self-refreshing `#list`. */
internal fun section(title: String, name: String, initial: Html): Html = html(
    h("<h1>{}</h1>", title),
    // the refresh morphs the list in place and waits while the user is working in it (see `cringleIdle` in app.js)
    h("<div id=\"list\" hx-get=\"/{}/list\" hx-trigger=\"every 5s [cringleIdle()]\" hx-swap=\"morph:innerHTML\">", name),
    initial,
    raw("</div>"),
    h("<p id=\"paused\" class=\"muted\" hidden>Refresh paused while you edit. <a href=\"/{}/list\" hx-get=\"/{}/list\" hx-target=\"#list\" hx-swap=\"morph:innerHTML\">Refresh now</a></p>", name, name),
)

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
