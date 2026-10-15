// SPDX-License-Identifier: Apache-2.0

package cringle.contract

import java.time.Duration

/** Thrown by [HttpDriver.register] when the route is taken already, or when the API port cannot be opened; the message says why. */
public class HttpRouteException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * A route that a block asks the engine to serve: [method] (upper case, `GET`, `POST`, ...) and [path], which starts with `/` and may have `{name}` segments
 * (`/orders/{id}`) whose values arrive in [HttpRequest.params]. With [auth] a request needs the token of a user who may use applications built with
 * Cringle; [timeout] is how long the handler may take before the client gets `504`.
 */
public data class HttpRoute(
    public val method: String,
    public val path: String,
    public val auth: Boolean = false,
    public val timeout: Duration = Duration.ofSeconds(30),
)

/** A request to a route: [headers] have lower case names; [body] is the body as UTF-8 text (empty without a body); [user] is the caller of a protected route. */
public class HttpRequest(
    public val method: String,
    public val path: String,
    public val params: Map<String, String>,
    public val query: Map<String, String>,
    public val headers: Map<String, String>,
    public val body: String,
    public val user: AuthenticatedUser?,
)

/** What a handler answers: [status], [headers] and the [body] as text. */
public class HttpResponse(
    public val status: Int,
    public val headers: Map<String, String> = emptyMap(),
    public val body: String = "",
)

/** A registered route; [close] removes it (the engine does it for the routes of a block when the block ends). */
public interface HttpRegistration : AutoCloseable {
    /** The route. */
    public val route: HttpRoute

    /** Removes the route: requests to it get `404` again. */
    override fun close()
}

/**
 * The HTTP API of the engine: every block that registers a route is reached on one fixed API port of the engine (setting `api.port`, HTTPS unless `api.tls`
 * is off). The port is opened with the first route. A route is unique in the engine: a second block with the same method and path (also with other names
 * for the `{...}` segments) gets an [HttpRouteException].
 */
public interface HttpDriver : Driver {
    /** Registers [route]; [handler] answers a request. Throws [HttpRouteException]. */
    public suspend fun register(route: HttpRoute, handler: suspend (HttpRequest) -> HttpResponse): HttpRegistration
}
