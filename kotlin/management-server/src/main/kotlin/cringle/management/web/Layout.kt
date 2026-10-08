// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.UserRole
import cringle.router.users.Permission

/** An entry of the navigation in [group]; shown only to a session that has [permission]. */
public data class NavItem(val label: String, val path: String, val permission: Permission = Permission.READ, val group: String = "Operate")

/** The title block of a page: [title], one line of help ([subtitle]) and the [actions] on the right. Pages use it instead of a bare `<h1>`. */
public fun pageHeader(title: String, subtitle: String = "", actions: Html = Html("")): Html =
    h(
        "<header class=\"page-header\"><div><h1>{}</h1>{}</div>{}</header>",
        title, if (subtitle.isEmpty()) Html("") else h("<p class=\"subtitle\">{}</p>", subtitle), if (actions.value.isEmpty()) Html("") else h("<div class=\"page-actions\">{}</div>", actions),
    )

/** The frame of every page: head, sidebar with the grouped navigation and the user, the banner of the open mode. See `docs/webui.md`, "Design system". */
public class Layout(private val navigation: List<NavItem>, private val version: String, private val fingerprint: String) {
    /** The groups of the navigation in the order they are shown. */
    private val groups = listOf("Overview", "Operate", "Observe", "Build", "Administer")

    /** The entry of the navigation that [path] belongs to: the longest path that is [path] or a parent of it; the editors belong to *Drafts*. */
    internal fun activeEntry(path: String?): String? {
        if (path == null) return null
        val effective = if (path.startsWith("/schemas/") || path.startsWith("/blueprints/")) "/drafts" else path
        return navigation.filter { it.path == effective || (it.path != "/" && effective.startsWith(it.path + "/")) }.maxByOrNull { it.path.length }?.path
    }

    private fun roleName(session: Session): String = when {
        session.user == null -> "open mode"
        UserRole.ADMIN in session.user.roles -> "administrator"
        UserRole.OPERATOR in session.user.roles -> "operator"
        UserRole.VIEWER in session.user.roles -> "viewer"
        else -> "user"
    }

    private fun sidebar(session: Session, path: String?): Html {
        val active = activeEntry(path)
        val visible = navigation.filter { session.can(it.permission) }
        val name = session.user?.name ?: "Everybody"
        return html(
            raw("<aside class=\"sidebar\"><a class=\"brand\" href=\"/\">"),
            raw("<svg viewBox=\"0 0 24 24\" width=\"22\" height=\"22\" aria-hidden=\"true\"><circle cx=\"12\" cy=\"12\" r=\"9\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"/><circle cx=\"12\" cy=\"12\" r=\"3.2\" fill=\"currentColor\"/></svg>Cringle</a>"),
            raw("<nav aria-label=\"Main\">"),
            groups.mapNotNull { group ->
                val entries = visible.filter { it.group == group }
                if (entries.isEmpty()) {
                    null
                } else {
                    html(
                        h("<div class=\"nav-group\"><p class=\"nav-title\">{}</p>", group),
                        entries.map { e -> h("<a href=\"{}\"{}>{}</a>", e.path, if (e.path == active) raw(" aria-current=\"page\"") else Html(""), e.label) },
                        raw("</div>"),
                    )
                }
            },
            raw("</nav><div class=\"sidebar-foot\">"),
            h("<div class=\"who\"><span class=\"avatar\" aria-hidden=\"true\">{}</span><div><strong>{}</strong><small>{}</small></div></div>", name.take(1).uppercase(), name, roleName(session)),
            if (session.user != null) raw("<button class=\"btn ghost small\" hx-post=\"/logout\">Sign out</button>") else Html(""),
            h("<small class=\"build\" title=\"Server key {}\">Cringle {} &middot; {}&hellip;</small>", fingerprint, version, fingerprint.take(8)),
            raw("</div></aside>"),
        )
    }

    /** A full page with [content] in the frame; [session] is `null` on the login page and on pages for a visitor without a session. [path] marks the current entry. */
    public fun page(title: String, session: Session?, content: Html, openMode: Boolean = false, path: String? = null): Html {
        val csrf = session?.csrfToken
        return html(
            raw("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><meta name=\"color-scheme\" content=\"dark\">"),
            raw("<link rel=\"icon\" type=\"image/svg+xml\" href=\"/static/favicon.svg\">"),
            // the style of htmx is not injected: the content security policy allows no inline style from scripts
            raw("<meta name=\"htmx-config\" content='{\"includeIndicatorStyles\":false}'>"),
            h("<title>{} - Cringle</title>", title),
            raw("<link rel=\"stylesheet\" href=\"/static/vendor/drawflow.min.css\"><link rel=\"stylesheet\" href=\"/static/app.css\"><script src=\"/static/vendor/htmx.min.js\" defer></script><script src=\"/static/vendor/idiomorph-ext.min.js\" defer></script>"),
            raw("<script src=\"/static/vendor/drawflow.min.js\" defer></script><script src=\"/static/vendor/alpine.min.js\" defer></script><script src=\"/static/app.js\" defer></script></head>"),
            if (csrf != null) h("<body hx-ext=\"morph\" hx-headers='{\"X-CSRF-Token\": \"{}\"}'>", csrf) else raw("<body hx-ext=\"morph\">"),
            if (openMode) raw("<p class=\"banner\" role=\"alert\">The ManagementServer runs without <code>--auth</code>: everybody who can reach this page is administrator.</p>") else Html(""),
            if (session == null) {
                html(raw("<main class=\"bare\">"), content, raw("</main>"))
            } else {
                html(raw("<div class=\"app\">"), sidebar(session, path), raw("<div class=\"content\"><main>"), content, raw("</main></div></div>"))
            },
            raw("</body></html>"),
        )
    }
}
