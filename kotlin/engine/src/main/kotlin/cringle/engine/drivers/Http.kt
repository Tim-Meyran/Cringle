// SPDX-License-Identifier: Apache-2.0

package cringle.engine.drivers

import cringle.common.BindAddress
import cringle.common.Identity
import cringle.contract.AuthenticatedUser
import cringle.contract.BuiltinDriverTypes
import cringle.contract.DriverType
import cringle.contract.HttpDriver
import cringle.contract.HttpRegistration
import cringle.contract.HttpRequest
import cringle.contract.HttpResponse
import cringle.contract.HttpRoute
import cringle.contract.HttpRouteException
import cringle.contract.UserManagementDriver
import cringle.contract.UserRole
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import java.io.ByteArrayOutputStream
import java.net.BindException
import java.net.URI
import java.net.URLDecoder
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

/**
 * The HTTP API of one engine (`HttpDriver`): one server on the fixed API port that serves the routes of all blocks of all fabrics of the engine. The port is opened
 * when the first route is registered ([ensureStarted]), HTTPS with a certificate of the key of the engine (host names `localhost`, the loopback addresses and the host
 * name of the machine) unless [identity] is `null`. A route with `auth` needs `Authorization: Bearer <token>` of a user of [users] with the role `END_USER` or `ADMIN`
 * (the permission to use applications); without [users] such a route refuses everybody. The answers of the server itself: `404` (no such path), `405` (with `Allow`),
 * `401`, `403`, `413` (a body above [MAX_BODY]), `503` (the handler is not connected in the blueprint), `504` (the handler did not answer in time), `500`.
 */
public class HttpService(
    /** The API port; 0 picks a free one. */
    private val port: Int,
    /** `loopback` (or `null`), `all` or an address, see [BindAddress]. */
    private val bind: String?,
    /** The identity whose key the HTTPS certificate is for; `null` serves plain HTTP. */
    private val identity: Identity?,
    /** Where the tokens are checked; `null`: protected routes refuse everybody. */
    private val users: UserManagementDriver?,
    /** Called with the port once the server listens (the engine writes it to a file). */
    private val onStarted: (Int) -> Unit = {},
) : AutoCloseable {
    private val log = LoggerFactory.getLogger("cringle.engine.http")
    private val routes = CopyOnWriteArrayList<Entry>()
    private val lock = Any()
    private var server: HttpServer? = null
    private var executor: java.util.concurrent.ExecutorService? = null

    private class Entry(val owner: String, val route: HttpRoute, val segments: List<String>, val handler: suspend (HttpRequest) -> HttpResponse) {
        /** The route without the names of its parameters: `GET /orders/{}`. */
        val key: String get() = route.method + " /" + segments.joinToString("/") { if (isParameter(it)) "{}" else it }
    }

    /** The port of the server; `0` until a route was registered. */
    public val boundPort: Int get() = synchronized(lock) { server?.address?.port ?: 0 }

    /** Registers [route] for [owner] (a text for messages: fabric and block). Throws [HttpRouteException]. */
    public fun register(owner: String, route: HttpRoute, handler: suspend (HttpRequest) -> HttpResponse): HttpRegistration {
        val method = route.method.uppercase()
        if (!Regex("[A-Z]{3,10}").matches(method)) throw HttpRouteException("the method '${route.method}' is not an HTTP method")
        val path = route.path
        if (!path.startsWith("/") || path.contains("//") || path.any { it.isWhitespace() || it == '?' || it == '#' }) {
            throw HttpRouteException("the path '$path' must start with '/' and has no '//', blank, '?' or '#'")
        }
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        val names = HashSet<String>()
        for (s in segments) {
            if (isParameter(s)) {
                if (!Regex("\\{[A-Za-z][A-Za-z0-9_]*}").matches(s)) throw HttpRouteException("'$s' in '$path' is not a parameter like {id}")
                if (!names.add(s)) throw HttpRouteException("the parameter $s is used twice in '$path'")
            } else if (s.contains('{') || s.contains('}')) {
                throw HttpRouteException("'$s' in '$path': a parameter is a whole segment like {id}")
            }
        }
        val entry = Entry(owner, route.copy(method = method), segments, handler)
        synchronized(lock) {
            routes.firstOrNull { it.key == entry.key }?.let { throw HttpRouteException("the route ${entry.route.method} $path is already served for ${it.owner} (as ${it.route.method} ${it.route.path}); $owner cannot use it") }
            ensureStarted()
            routes += entry
        }
        return object : HttpRegistration {
            override val route: HttpRoute = entry.route

            override fun close() {
                routes.remove(entry)
            }
        }
    }

    private fun ensureStarted() {
        if (server != null) return
        val address = BindAddress.socketAddress(bind, port)
        val created: HttpServer = try {
            if (identity == null) HttpServer.create(address, 0) else HttpsServer.create(address, 0).also { configureTls(it, identity) }
        } catch (e: BindException) {
            throw HttpRouteException("the API port ${if (port == 0) "" else "$port "}cannot be opened: ${e.message} (another engine of this machine may use it: set api.port, or 0 for any free port)", e)
        } catch (e: java.io.IOException) {
            throw HttpRouteException("the API port ${if (port == 0) "" else "$port "}cannot be opened: ${e.message}", e)
        }
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        created.executor = pool
        created.createContext("/") { exchange -> runCatching { handle(exchange) }.onFailure { failure(exchange, it) } }
        created.start()
        server = created
        executor = pool
        log.info("API on {}://{}:{}/", if (identity == null) "http" else "https", address.hostString, created.address.port)
        onStarted(created.address.port)
    }

    private fun configureTls(created: HttpsServer, id: Identity) {
        val names = linkedSetOf("localhost", "127.0.0.1", "::1")
        runCatching { java.net.InetAddress.getLocalHost().hostName.lowercase() }.getOrNull()?.let { names += it }
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        val password = CharArray(0)
        store.setKeyEntry("api", id.keyPair.private, password, arrayOf(id.webCertificate(names)))
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(store, password)
        val context = SSLContext.getInstance("TLSv1.3").also { it.init(factory.keyManagers, null, null) }
        created.httpsConfigurator = object : HttpsConfigurator(context) {
            override fun configure(params: HttpsParameters) {
                val p = context.defaultSSLParameters
                p.protocols = arrayOf("TLSv1.3")
                params.setSSLParameters(p)
            }
        }
    }

    private fun handle(exchange: HttpExchange) {
        val uri: URI = exchange.requestURI
        val rawPath = uri.rawPath ?: "/"
        val parts = rawPath.trim('/').split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }
        val method = exchange.requestMethod.uppercase()
        val candidates = routes.mapNotNull { e -> match(e.segments, parts)?.let { e to it } }
        if (candidates.isEmpty()) return reply(exchange, 404, error("there is nothing at $rawPath"))
        val chosen = candidates.filter { it.first.route.method == method }.maxByOrNull { (e, _) -> e.segments.count { !isParameter(it) } }
        if (chosen == null) {
            val allowed = candidates.map { it.first.route.method }.distinct().sorted().joinToString(", ")
            return reply(exchange, 405, error("$method is not served at $rawPath"), mapOf("Allow" to allowed))
        }
        val (entry, params) = chosen
        var user: AuthenticatedUser? = null
        if (entry.route.auth) {
            val token = exchange.requestHeaders.getFirst("Authorization")?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
            val found = if (token.isNullOrEmpty() || users == null) null else runBlocking { users.authenticate(token) }
            if (found == null) return reply(exchange, 401, error("a valid token is needed: Authorization: Bearer <token>"), mapOf("WWW-Authenticate" to "Bearer"))
            if (found.roles.none { it == UserRole.END_USER || it == UserRole.ADMIN }) return reply(exchange, 403, error("the user may not use applications"))
            user = found
        }
        val declared = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && declared > MAX_BODY) return tooLarge(exchange)
        val body = readBody(exchange) ?: return tooLarge(exchange)
        val headers = HashMap<String, String>()
        exchange.requestHeaders.forEach { (k, v) -> headers[k.lowercase()] = v.firstOrNull().orEmpty() }
        val request = HttpRequest(method, rawPath, params, query(uri.rawQuery), headers, body.toString(Charsets.UTF_8), user)
        val answer = try {
            runBlocking { withTimeout(entry.route.timeout.toMillis()) { entry.handler(request) } }
        } catch (e: TimeoutCancellationException) {
            return reply(exchange, 504, error("no answer within ${entry.route.timeout.toMillis()} ms"))
        } catch (e: CancellationException) {
            return reply(exchange, 503, error("the endpoint is being stopped"))
        } catch (e: IllegalStateException) {
            if (e.message?.contains("is not connected") == true) return reply(exchange, 503, error("the endpoint has no handler in the blueprint"))
            log.warn("the handler of {} {} failed", entry.route.method, entry.route.path, e)
            return reply(exchange, 500, error("the handler failed"))
        } catch (e: Exception) {
            log.warn("the handler of {} {} failed", entry.route.method, entry.route.path, e)
            return reply(exchange, 500, error("the handler failed"))
        }
        reply(exchange, answer.status.coerceIn(100, 599), answer.body.toByteArray(Charsets.UTF_8), answer.headers, defaultType = "text/plain; charset=utf-8")
    }

    /** Answers `413`; what the client is still sending is read and dropped first (up to a limit), or it would see a reset instead of the answer. */
    private fun tooLarge(exchange: HttpExchange) {
        runCatching {
            val buffer = ByteArray(8192)
            var left = 8L * MAX_BODY
            while (left > 0) {
                val n = exchange.requestBody.read(buffer)
                if (n < 0) break
                left -= n
            }
        }
        reply(exchange, 413, error("the body is larger than $MAX_BODY bytes"), mapOf("Connection" to "close"))
    }

    private fun readBody(exchange: HttpExchange): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        exchange.requestBody.use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BODY) return null
            }
        }
        return out.toByteArray()
    }

    private fun query(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            map[URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8)] = URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8)
        }
        return map
    }

    private fun error(message: String): ByteArray = ("{\"error\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}").toByteArray(Charsets.UTF_8)

    private fun reply(exchange: HttpExchange, status: Int, body: ByteArray, headers: Map<String, String> = emptyMap(), defaultType: String = "application/json") {
        val h = exchange.responseHeaders
        var typed = false
        for ((k, v) in headers) {
            if (k.any { it == '\r' || it == '\n' || it == ':' } || v.any { it == '\r' || it == '\n' }) continue
            if (k.equals("content-type", ignoreCase = true)) typed = true
            if (k.equals("content-length", ignoreCase = true) || k.equals("transfer-encoding", ignoreCase = true)) continue
            h.set(k, v)
        }
        if (!typed) h.set("Content-Type", defaultType)
        h.set("X-Content-Type-Options", "nosniff")
        if (status == 204 || status == 304 || status in 100..199 || body.isEmpty()) {
            exchange.sendResponseHeaders(status, -1)
        } else {
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        exchange.close()
    }

    private fun failure(exchange: HttpExchange, e: Throwable) {
        log.warn("an API request failed", e)
        runCatching { reply(exchange, 500, error("the request failed")) }
    }

    override fun close() {
        synchronized(lock) {
            server?.stop(0)
            server = null
            executor?.shutdownNow()
            executor = null
            routes.clear()
        }
    }

    /** The driver of one block: [owner] is `fabric/block`; the routes of the block end with it. */
    internal fun driverFor(owner: String): BlockHttp = BlockHttp(this, owner)

    internal class BlockHttp(private val service: HttpService, private val owner: String) : HttpDriver, AutoCloseable {
        override val type: DriverType = BuiltinDriverTypes.HTTP
        private val registrations = CopyOnWriteArrayList<HttpRegistration>()

        override suspend fun register(route: HttpRoute, handler: suspend (HttpRequest) -> HttpResponse): HttpRegistration {
            val inner = service.register(owner, route, handler)
            registrations += inner
            return object : HttpRegistration {
                override val route: HttpRoute = inner.route

                override fun close() {
                    inner.close()
                    registrations.remove(inner)
                }
            }
        }

        override fun close() {
            registrations.forEach { runCatching { it.close() } }
            registrations.clear()
        }
    }

    public companion object {
        /** The largest request body in bytes. */
        public const val MAX_BODY: Int = 1024 * 1024

        private fun isParameter(segment: String): Boolean = segment.startsWith("{") && segment.endsWith("}")

        /** The values of the parameters of [pattern] for the path [parts], or `null` if the path does not fit. */
        private fun match(pattern: List<String>, parts: List<String>): Map<String, String>? {
            if (pattern.size != parts.size) return null
            val params = LinkedHashMap<String, String>()
            for ((p, v) in pattern.zip(parts)) {
                if (isParameter(p)) params[p.substring(1, p.length - 1)] = v else if (p != v) return null
            }
            return params
        }
    }
}
