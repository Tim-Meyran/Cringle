// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.router.users.Permission

/** An entry of the navigation; shown only to a session that has [permission]. */
public data class NavItem(val label: String, val path: String, val permission: Permission = Permission.READ)

/** The frame of every page: head, navigation, user, footer. */
public class Layout(private val navigation: List<NavItem>, private val version: String, private val fingerprint: String) {
    /** A full page with [content] in the frame; [session] is `null` on the login page. */
    public fun page(title: String, session: Session?, content: Html, openMode: Boolean = false): Html {
        val nav = if (session == null) {
            Html("")
        } else {
            html(
                raw("<nav>"),
                navigation.filter { session.can(it.permission) }.map { h("<a href=\"{}\">{}</a>", it.path, it.label) },
                raw("</nav><div class=\"user\">"),
                esc(session.user?.name ?: "everybody"),
                if (session.user != null) h("<button hx-post=\"/logout\" class=\"link\">Logout</button>") else Html(""),
                raw("</div>"),
            )
        }
        val csrf = session?.csrfToken
        return html(
            raw("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><link rel=\"icon\" href=\"data:,\">"),
            // the style of htmx is not injected: the content security policy allows no inline style from scripts
            raw("<meta name=\"htmx-config\" content='{\"includeIndicatorStyles\":false}'>"),
            h("<title>{} - Cringle</title>", title),
            raw("<link rel=\"stylesheet\" href=\"/static/vendor/drawflow.min.css\"><link rel=\"stylesheet\" href=\"/static/app.css\"><script src=\"/static/vendor/htmx.min.js\" defer></script><script src=\"/static/vendor/idiomorph-ext.min.js\" defer></script>"),
            raw("<script src=\"/static/vendor/drawflow.min.js\" defer></script><script src=\"/static/vendor/alpine.min.js\" defer></script><script src=\"/static/app.js\" defer></script></head>"),
            if (csrf != null) h("<body hx-ext=\"morph\" hx-headers='{\"X-CSRF-Token\": \"{}\"}'>", csrf) else raw("<body hx-ext=\"morph\">"),
            raw("<header><a class=\"brand\" href=\"/\">Cringle</a>"),
            nav,
            raw("</header>"),
            if (openMode) raw("<p class=\"warning\">The ManagementServer runs without --auth: everybody who can reach this page is administrator.</p>") else Html(""),
            raw("<main>"),
            content,
            raw("</main>"),
            h("<footer>Cringle {} &middot; server key <code>{}</code></footer></body></html>", version, fingerprint),
        )
    }
}
