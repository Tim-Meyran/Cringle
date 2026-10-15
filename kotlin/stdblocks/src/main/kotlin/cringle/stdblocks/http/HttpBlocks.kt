// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.http

import cringle.contract.Block
import cringle.contract.BlockDefinition
import cringle.contract.BlockContext
import cringle.contract.BuiltinDriverTypes
import cringle.contract.ExclusiveKind
import cringle.contract.ExclusiveResource
import cringle.contract.HttpDriver
import cringle.contract.HttpRegistration
import cringle.contract.HttpRequest
import cringle.contract.HttpResponse
import cringle.contract.HttpRoute
import cringle.contract.PortDefinition
import cringle.contract.TetherEvent
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.Tether
import cringle.contract.TetherType
import cringle.contract.TypeParameter
import cringle.stdblocks.ANY
import cringle.stdblocks.Entry
import cringle.stdblocks.StdBlockProvider
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
 * `http.response`: answers the request of an `http.endpoint` with a response. Every part of it can come from a port or from the properties: the ports `status`,
 * `contentType`, `headers` and `body` are asked for (`PULL`, with the request as the context) when the request comes; a port that nothing is connected to gives the
 * value of the properties (`status` 200, `contentType`, `headers` and `body`, a text in which `{}` is replaced by what this block got as its request). The `headers`
 * of a port are added to those of the properties and win; a `body` that is not a text goes out as JSON.
 */
internal class HttpResponseBlock : Block {
    private var status = 200L
    private val headers = LinkedHashMap<String, String>()
    private var contentType: String? = null
    private var body: String = ""
    private lateinit var statusPort: Tether
    private lateinit var contentTypePort: Tether
    private lateinit var headersPort: Tether
    private lateinit var bodyPort: Tether

    override suspend fun init(context: BlockContext) {
        status = context.config.long("status") ?: 200
        require(status in 100..599) { "the configuration 'status' must be a HTTP status (100 to 599)" }
        (context.config["headers"] as? Map<*, *>)?.forEach { (k, v) -> headers[k.toString()] = v.toString() }
        contentType = (context.config["contentType"] as? String)?.takeIf { it.isNotBlank() }
        body = context.config["body"] as? String ?: ""
        statusPort = context.ports.port("status")
        contentTypePort = context.ports.port("contentType")
        headersPort = context.ports.port("headers")
        bodyPort = context.ports.port("body")
    }

    /** What the block behind [port] answers, or `null` if nothing that can be asked is connected there (the value of the properties counts then). */
    private suspend fun ask(port: Tether, context: Any): Any? = try {
        port.request(context)
    } catch (e: IllegalStateException) {
        if (e.message?.contains("is not connected") != true && e.message?.contains("cannot initiate") != true) throw e
        null
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event !is TetherEvent.Request || event.port.name != "request") return
        val context = event.value
        val answerStatus = (ask(statusPort, context) as? Number)?.toLong() ?: status
        require(answerStatus in 100..599) { "the status $answerStatus is not a HTTP status (100 to 599)" }
        val all = LinkedHashMap(headers)
        (ask(contentTypePort, context) as? String ?: contentType)?.let { type ->
            if (all.keys.none { it.equals("content-type", ignoreCase = true) }) all["Content-Type"] = type
        }
        (ask(headersPort, context) as? Map<*, *>)?.forEach { (k, v) ->
            all.keys.firstOrNull { it.equals(k.toString(), ignoreCase = true) }?.let { all.remove(it) }
            all[k.toString()] = v.toString()
        }
        // without a body port the configured body is a template: `{}` becomes what the block got as its request (a text as it is, anything else as JSON)
        val answerBody = ask(bodyPort, context) ?: body.replace("{}", context as? String ?: toJson(context))
        event.respond(mapOf("status" to answerStatus, "headers" to all, "body" to answerBody))
    }
}

/**
 * `http.request-value`: the answer of a pull tether with one part of the HTTP request that it is asked with (the context of the pull): `field` as in `http.request-field`,
 * with `json` parsed as JSON. A part that the request does not have is an empty text.
 */
internal class HttpRequestValue : Block {
    private lateinit var field: String
    private var json = false

    override suspend fun init(context: BlockContext) {
        field = (context.config["field"] as? String)?.trim().orEmpty()
        require(HttpRequestField.FIELD.matches(field)) { "the configuration 'field' is body, method, path, user, params.<name>, query.<name> or headers.<name>" }
        json = context.config["json"] as? Boolean ?: false
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event !is TetherEvent.Request || event.port.name != "out") return
        val part = HttpRequestField.part(event.value, field)
        event.respond(if (json) HttpRequestField.parse(part.toString(), field) else part)
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
        val part = part(event.value, field)
        val value: Any = if (json) parse(part.toString(), field) else part
        event.respond(next.request(value))
    }

    internal companion object {
        val FIELD = Regex("body|method|path|user|(params|query|headers)\\.\\S+")

        /** The part [field] of the HTTP request [request] (an empty text if it has none). */
        fun part(request: Any, field: String): Any {
            val map = request as? Map<*, *> ?: throw IllegalArgumentException("the request is not the request of an http.endpoint")
            return when (field.substringBefore('.')) {
                "params", "query", "headers" -> {
                    val key = field.substringAfter('.').let { if (field.startsWith("headers.")) it.lowercase() else it }
                    (map[field.substringBefore('.')] as? Map<*, *>)?.get(key) ?: ""
                }
                else -> map[field] ?: ""
            }
        }

        fun parse(text: String, field: String): Any = try {
            parseJson(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("'$field' of the request is not valid JSON: ${e.message}")
        }
    }
}

private val HTTP_REQUEST = SchemaRef(StdBlockProvider.NAMESPACE, "HttpRequest")
private val HTTP_RESPONSE = SchemaRef(StdBlockProvider.NAMESPACE, "HttpResponse")
private val REQUEST_RESPONSE = setOf(TetherType.REQUEST_RESPONSE)
private val PULL = setOf(TetherType.PULL)

/** `http.request-field` with the type parameter `T` (bound `Any`): the value that `next` gets, a text or, with `json`, a JSON value. */
private val REQUEST_FIELD = Entry(
    BlockDefinition(
        "http.request-field",
        listOf(HTTP_REQUEST, HTTP_RESPONSE, ANY),
        listOf(
            PortDefinition("request", PortDirection.IN, REQUEST_RESPONSE, HTTP_REQUEST, responseSchema = HTTP_RESPONSE),
            PortDefinition("next", PortDirection.OUT, REQUEST_RESPONSE, ANY, typeParam = "T", responseSchema = HTTP_RESPONSE),
        ),
        emptyList(),
        SchemaRef(StdBlockProvider.NAMESPACE, "HttpRequestFieldConfig"),
        emptyList(),
        listOf(TypeParameter("T")),
    ),
    { HttpRequestField() },
)

internal val HTTP_ENTRIES: List<Entry> = listOf(
    entry(
        "http.endpoint",
        // a request is an HttpRequest, the answer of the flow an HttpResponse (http.response builds one)
        listOf(PortDefinition("handler", PortDirection.OUT, REQUEST_RESPONSE, HTTP_REQUEST, responseSchema = HTTP_RESPONSE)),
        "HttpEndpointConfig",
        listOf(BuiltinDriverTypes.HTTP.id),
        // a route of the API port is served by one fabric at a time: a project with an endpoint is updated by stopping the old fabric first
        listOf(ExclusiveResource(ExclusiveKind.PORT, "api")),
    ) { HttpEndpoint(it[HttpDriver::class]) },
    entry(
        "http.response",
        listOf(
            PortDefinition("request", PortDirection.IN, REQUEST_RESPONSE, ANY, responseSchema = HTTP_RESPONSE),
            // the parts of the response: asked for when a request comes; the properties are the defaults
            PortDefinition("status", PortDirection.IN, PULL, SchemaRef("cringle.std", "Int")),
            PortDefinition("contentType", PortDirection.IN, PULL, SchemaRef("cringle.std", "String")),
            PortDefinition("headers", PortDirection.IN, PULL, ANY),
            PortDefinition("body", PortDirection.IN, PULL, ANY),
        ),
        "HttpResponseConfig",
    ) { HttpResponseBlock() },
    entry(
        "http.request-value",
        listOf(PortDefinition("out", PortDirection.OUT, PULL, ANY)),
        "HttpRequestFieldConfig",
    ) { HttpRequestValue() },
    REQUEST_FIELD,
)
