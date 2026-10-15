// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.http

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.BuiltinDriverTypes
import cringle.contract.HttpDriver
import cringle.contract.HttpRegistration
import cringle.contract.HttpRequest
import cringle.contract.HttpResponse
import cringle.contract.HttpRoute
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.Tether
import cringle.contract.TetherType
import cringle.stdblocks.ANY
import cringle.stdblocks.Entry
import cringle.stdblocks.entry
import cringle.stdblocks.long
import cringle.stdblocks.toJson
import java.time.Duration

/**
 * `http.endpoint`: one route (`method` and `path`, with `{name}` segments) on the API port of the engine. An HTTP request goes to the flow as a request on `handler`
 * (`REQUEST_RESPONSE`): `{method, path, params, query, headers, body, user}` (`user` is the name of the caller of a protected route, `auth`: the request needs the
 * token of a user who may use applications). The answer is the HTTP response: a map `{status, headers, body}` (all optional: 200, none, empty), or any other value as the
 * body with status 200 (a text as it is, anything else as JSON with `application/json`). The route is served from the start of the block to its stop.
 */
internal class HttpEndpoint(private val http: HttpDriver) : Block {
    private lateinit var route: HttpRoute
    private lateinit var handler: Tether
    private var registration: HttpRegistration? = null

    override suspend fun init(context: BlockContext) {
        val path = (context.config["path"] as? String)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("the configuration 'path' is missing")
        val method = ((context.config["method"] as? String) ?: "GET").uppercase()
        val timeoutMs = context.config.long("timeoutMs") ?: 30_000
        require(timeoutMs in 1..600_000) { "the configuration 'timeoutMs' must be between 1 and 600000" }
        route = HttpRoute(method, path, context.config["auth"] as? Boolean ?: false, Duration.ofMillis(timeoutMs))
        handler = context.ports.port("handler")
    }

    override suspend fun start() {
        registration = http.register(route) { request -> toResponse(handler.request(toMap(request))) }
    }

    override suspend fun stop() {
        registration?.close()
        registration = null
    }

    override suspend fun destroy() = stop()

    private fun toMap(r: HttpRequest): Map<String, Any> {
        val map = linkedMapOf<String, Any>(
            "method" to r.method, "path" to r.path, "params" to r.params, "query" to r.query, "headers" to r.headers, "body" to r.body,
        )
        r.user?.let { map["user"] = it.name }
        return map
    }

    private fun bodyText(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        else -> toJson(value)
    }

    private fun toResponse(answer: Any): HttpResponse {
        val structured = answer is Map<*, *> && (answer.containsKey("status") || answer.containsKey("body") || answer.containsKey("headers"))
        val map = if (structured) answer as Map<*, *> else mapOf("body" to answer)
        val status = (map["status"] as? Number)?.toInt() ?: 200
        val headers = LinkedHashMap<String, String>()
        (map["headers"] as? Map<*, *>)?.forEach { (k, v) -> if (k != null && v != null) headers[k.toString()] = v.toString() }
        val body = map["body"]
        if (body != null && body !is String && headers.keys.none { it.equals("content-type", ignoreCase = true) }) headers["Content-Type"] = "application/json; charset=utf-8"
        return HttpResponse(status, headers, bodyText(body))
    }
}

internal val HTTP_ENTRIES: List<Entry> = listOf(
    entry(
        "http.endpoint",
        listOf(PortDefinition("handler", PortDirection.OUT, setOf(TetherType.REQUEST_RESPONSE), ANY)),
        "HttpEndpointConfig",
        listOf(BuiltinDriverTypes.HTTP.id),
    ) { HttpEndpoint(it[HttpDriver::class]) },
)
