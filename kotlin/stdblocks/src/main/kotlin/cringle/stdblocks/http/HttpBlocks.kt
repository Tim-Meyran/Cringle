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
import cringle.contract.TetherEvent
import cringle.contract.PortDirection
import cringle.contract.Tether
import cringle.contract.TetherType
import cringle.stdblocks.ANY
import cringle.stdblocks.Entry
import cringle.stdblocks.entry
import cringle.stdblocks.long
import cringle.stdblocks.parseJson
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
    private var contentType: String? = null
    private lateinit var handler: Tether
    private var registration: HttpRegistration? = null

    override suspend fun init(context: BlockContext) {
        val path = (context.config["path"] as? String)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("the configuration 'path' is missing")
        val method = ((context.config["method"] as? String) ?: "GET").uppercase()
        val timeoutMs = context.config.long("timeoutMs") ?: 30_000
        require(timeoutMs in 1..600_000) { "the configuration 'timeoutMs' must be between 1 and 600000" }
        route = HttpRoute(method, path, context.config["auth"] as? Boolean ?: false, Duration.ofMillis(timeoutMs))
        contentType = (context.config["contentType"] as? String)?.takeIf { it.isNotBlank() }
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
        if (body is String && contentType != null && headers.keys.none { it.equals("content-type", ignoreCase = true) }) headers["Content-Type"] = contentType!!
        if (body != null && body !is String && headers.keys.none { it.equals("content-type", ignoreCase = true) }) headers["Content-Type"] = "application/json; charset=utf-8"
        return HttpResponse(status, headers, bodyText(body))
    }
}

/**
 * `http.response`: answers the request of an `http.endpoint` with a response: `status` (default 200), `contentType` and `headers` (texts) from the configuration. The
 * body is the answer of the block at `source` (it gets the request), or, with nothing connected at `source`, the configured `body` (a text, empty by default; a `{}`
 * in it is replaced by what this block got as its request, so a field from `http.request-field` can be part of the answer). A body that is not a text goes out as JSON.
 */
internal class HttpResponseBlock : Block {
    private var status = 200L
    private val headers = LinkedHashMap<String, String>()
    private var body: String = ""
    private lateinit var source: Tether

    override suspend fun init(context: BlockContext) {
        status = context.config.long("status") ?: 200
        require(status in 100..599) { "the configuration 'status' must be a HTTP status (100 to 599)" }
        (context.config["headers"] as? Map<*, *>)?.forEach { (k, v) -> headers[k.toString()] = v.toString() }
        (context.config["contentType"] as? String)?.takeIf { it.isNotBlank() }?.let { type ->
            if (headers.keys.none { it.equals("content-type", ignoreCase = true) }) headers["Content-Type"] = type
        }
        body = context.config["body"] as? String ?: ""
        source = context.ports.port("source")
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event !is TetherEvent.Request || event.port.name != "request") return
        val answer: Any? = try {
            source.request(event.value)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("is not connected") != true) throw e
            null
        }
        // without a source the configured body is a template: `{}` becomes what the block got as its request (a text as it is, anything else as JSON)
        val text = body.replace("{}", event.value as? String ?: toJson(event.value))
        event.respond(mapOf("status" to status, "headers" to LinkedHashMap(headers), "body" to (answer ?: text)))
    }
}

/**
 * `http.request-field`: takes one part of the request of an `http.endpoint` and asks the block at `next` with it; the answer of `next` is its answer. `field` is
 * `body`, `method`, `path`, `user`, or `params.<name>`, `query.<name>`, `headers.<name>` (header names in lower case); a part that the request does not have is an empty
 * text. With `json` the part is parsed as JSON first (a bad one fails the request).
 */
internal class HttpRequestField : Block {
    private lateinit var field: String
    private var json = false
    private lateinit var next: Tether

    override suspend fun init(context: BlockContext) {
        field = (context.config["field"] as? String)?.trim().orEmpty()
        require(FIELD.matches(field)) { "the configuration 'field' is body, method, path, user, params.<name>, query.<name> or headers.<name>" }
        json = context.config["json"] as? Boolean ?: false
        next = context.ports.port("next")
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event !is TetherEvent.Request || event.port.name != "request") return
        val request = event.value as? Map<*, *> ?: throw IllegalArgumentException("the request is not the request of an http.endpoint")
        val part: Any = when (field.substringBefore('.')) {
            "params", "query", "headers" -> {
                val key = field.substringAfter('.').let { if (field.startsWith("headers.")) it.lowercase() else it }
                (request[field.substringBefore('.')] as? Map<*, *>)?.get(key) ?: ""
            }
            else -> request[field] ?: ""
        }
        val value: Any = if (json) {
            try {
                parseJson(part.toString())
            } catch (e: Exception) {
                throw IllegalArgumentException("'${field}' of the request is not valid JSON: ${e.message}")
            }
        } else {
            part
        }
        event.respond(next.request(value))
    }

    private companion object {
        val FIELD = Regex("body|method|path|user|(params|query|headers)\\.\\S+")
    }
}

internal val HTTP_ENTRIES: List<Entry> = listOf(
    entry(
        "http.endpoint",
        listOf(PortDefinition("handler", PortDirection.OUT, setOf(TetherType.REQUEST_RESPONSE), ANY)),
        "HttpEndpointConfig",
        listOf(BuiltinDriverTypes.HTTP.id),
    ) { HttpEndpoint(it[HttpDriver::class]) },
    entry(
        "http.response",
        listOf(
            PortDefinition("request", PortDirection.IN, setOf(TetherType.REQUEST_RESPONSE), ANY),
            PortDefinition("source", PortDirection.OUT, setOf(TetherType.REQUEST_RESPONSE), ANY),
        ),
        "HttpResponseConfig",
    ) { HttpResponseBlock() },
    entry(
        "http.request-field",
        listOf(
            PortDefinition("request", PortDirection.IN, setOf(TetherType.REQUEST_RESPONSE), ANY),
            PortDefinition("next", PortDirection.OUT, setOf(TetherType.REQUEST_RESPONSE), ANY),
        ),
        "HttpRequestFieldConfig",
    ) { HttpRequestField() },
)
