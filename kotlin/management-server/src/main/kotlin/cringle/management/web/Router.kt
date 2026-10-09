// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.AuthenticatedUser
import cringle.router.users.Permission
import cringle.router.users.Scope

/** What a handler gets: the request and, if there is one, the session. */
public class WebRequest(
    public val method: String,
    public val path: String,
    public val query: Map<String, String>,
    public val form: Map<String, String>,
    public val headers: Map<String, String>,
    public val session: Session?,
    /** Path variables of the route (`/engines/{id}`). */
    public val params: Map<String, String>,
    public val body: ByteArray,
) {
    /** True for an htmx request (the answer is a fragment). */
    public val isHtmx: Boolean get() = headers["hx-request"] == "true"
}

/** A response. [body] is sent as is. */
public class WebResponse(
    public val status: Int,
    public val body: ByteArray,
    public val contentType: String = "text/html; charset=utf-8",
    public val headers: Map<String, String> = emptyMap(),
    public val cookies: List<String> = emptyList(),
) {
    public companion object {
        public fun page(status: Int, content: Html): WebResponse = WebResponse(status, content.value.toByteArray())

        public fun redirect(to: String, cookies: List<String> = emptyList()): WebResponse =
            WebResponse(303, ByteArray(0), headers = mapOf("Location" to to), cookies = cookies)
    }
}

/** A route: [method] and a path pattern with `{name}` segments, the [permission] it needs (`null`: public). */
public class Route(
    public val method: String,
    public val pattern: String,
    public val permission: Permission?,
    /** The largest request body this route accepts; `null`: the limit of the server. */
    public val maxBodyBytes: Int? = null,
    public val handler: suspend (WebRequest) -> WebResponse,
    /** The scopes the whole route needs the permission for (a framework function such as `function:users`); `null`: the page checks the objects it shows. */
    public val scopes: List<Scope>? = null,
) {
    private val segments = pattern.trim('/').split('/').filter { it.isNotEmpty() }

    internal fun match(path: String): Map<String, String>? {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.size != segments.size) return null
        val params = HashMap<String, String>()
        for ((s, p) in segments.zip(parts)) {
            if (s.startsWith("{") && s.endsWith("}")) params[s.substring(1, s.length - 1)] = p else if (s != p) return null
        }
        return params
    }
}

/** The routes of the web layer. */
public class Router {
    private val routes = ArrayList<Route>()

    /** Adds a route. */
    public fun add(method: String, pattern: String, permission: Permission?, maxBodyBytes: Int? = null, handler: suspend (WebRequest) -> WebResponse): Router {
        routes += Route(method, pattern, permission, maxBodyBytes, handler, routeScopes)
        return this
    }

    private var routeScopes: List<Scope>? = null

    /** Adds the routes that [block] registers with the requirement that the permission is held for [scopes] (globally or for one of them), not just anywhere. */
    public fun scoped(scopes: List<Scope>, block: Router.() -> Unit) {
        routeScopes = scopes
        try {
            block()
        } finally {
            routeScopes = null
        }
    }

    public fun get(pattern: String, permission: Permission?, handler: suspend (WebRequest) -> WebResponse): Router = add("GET", pattern, permission, null, handler)

    public fun post(pattern: String, permission: Permission?, maxBodyBytes: Int? = null, handler: suspend (WebRequest) -> WebResponse): Router = add("POST", pattern, permission, maxBodyBytes, handler)

    /** The match for [method] and [path]: the route and its path variables, or `null`. */
    internal fun find(method: String, path: String): Pair<Route, Map<String, String>>? {
        for (r in routes) {
            if (r.method != method) continue
            r.match(path)?.let { return r to it }
        }
        return null
    }

    /** True if some route has [path] with another method. */
    internal fun knows(path: String): Boolean = routes.any { it.match(path) != null }
}

/** A login session. */
public class Session(
    public val id: String,
    public val user: AuthenticatedUser?,
    public val permissions: Set<Permission>,
    public val csrfToken: String,
    @Volatile internal var lastUsed: java.time.Instant,
    /** The scoped roles of the user (#271): whether the user may do a permission to an object in the given scopes; `null` if the user has no scoped roles to ask. */
    private val scoped: ((Permission, List<Scope>) -> Boolean)? = null,
    /** Whether the user has a permission for any scope. */
    private val anywhere: ((Permission) -> Boolean)? = null,
) {
    /** True if the session may do [permission] globally. */
    public fun can(permission: Permission): Boolean = permission in permissions

    /** True if the session has [permission] globally or for any object: enough to open a page, the objects on it are checked one by one. */
    public fun canAnywhere(permission: Permission): Boolean = can(permission) || anywhere?.invoke(permission) == true

    /** True if the session may do [permission] to an object that lies in [scopes] (globally, or for one of them). */
    public fun canFor(permission: Permission, scopes: List<Scope>): Boolean = can(permission) || scoped?.invoke(permission, scopes) == true
}
