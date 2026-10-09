// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.management.ManagementCore
import cringle.router.users.Permission
import cringle.router.users.UserManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * The web layer of the ManagementServer (Architecture 7.2, 24): server-rendered pages and HTML fragments for htmx over HTTPS (TLS 1.3 with the
 * identity of the ManagementServer, self-signed: the browser asks once, the fingerprint is shown on the login page and at the start).
 * Login with the tokens of the user management, session cookie, CSRF header on every `POST`. See `docs/webui.md`.
 *
 * Later pages add their routes to [router] and their entries to [navigation] before [start].
 */
public class WebServer(
    public val core: ManagementCore,
    private val users: UserManager?,
    port: Int,
    /** Where the web interface listens: see [cringle.common.BindAddress]; `null` is the loopback interface. */
    private val host: String? = null,
    clock: Clock = Clock.systemUTC(),
    idleTimeout: Duration = Duration.ofHours(8),
    /** The pause after a failed login. */
    private val failedLoginDelay: Duration = Duration.ofSeconds(1),
    private val maxBodyBytes: Int = 1024 * 1024,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger("cringle.management.web")
    private val sessions = Sessions(users, clock, idleTimeout)

    /** The routes; the foundation registers `/`, `/login` and `/logout`. */
    public val router: Router = Router()

    /** The entries of the navigation. */
    public val navigation: MutableList<NavItem> = arrayListOf(NavItem("Dashboard", "/", group = "Overview"))

    private val fingerprint = core.identity.publicKeyFingerprint
    private val layout = Layout(navigation, version(), fingerprint)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server: HttpsServer = HttpsServer.create(cringle.common.BindAddress.socketAddress(host, port), 0)

    /** The port; valid after [start]. */
    public val port: Int get() = server.address.port

    init {
        val context = sslContext()
        server.httpsConfigurator = object : HttpsConfigurator(context) {
            override fun configure(params: HttpsParameters) {
                val p = context.defaultSSLParameters
                p.protocols = arrayOf("TLSv1.3")
                params.setSSLParameters(p)
            }
        }
        server.executor = executor
        server.createContext("/") { exchange -> runCatching { handle(exchange) }.onFailure { fail(exchange, it) } }
        registerFoundation()
        OverviewPages(core).register(this)
        DeploymentPages(core).register(this)
        users?.let { UserPages(it).register(this) }
        TrustPackagePages(core).register(this)
        val drafts = DraftStore(core.dataDirectory.resolve("drafts"))
        val blueprints = BlueprintPages(core, drafts)
        SchemaPages(core, drafts, blueprints).register(this)
        blueprints.register(this)
    }

    private fun sslContext(): SSLContext {
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        val password = CharArray(0)
        store.setKeyEntry("web", core.identity.keyPair.private, password, arrayOf(core.identity.certificate))
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(store, password)
        return SSLContext.getInstance("TLSv1.3").also { it.init(factory.keyManagers, null, null) }
    }

    /** Starts the server. */
    public fun start(): WebServer {
        server.start()
        log.info("web interface on https://{}:{}/ (server key {})", host ?: cringle.common.BindAddress.LOOPBACK, this.port, fingerprint)
        return this
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun registerFoundation() {
        router.get("/login", null) { r ->
            if (r.session != null) WebResponse.redirect("/") else WebResponse.page(200, loginPage(null))
        }
        router.post("/login", null) { r ->
            val session = sessions.login(r.form["token"].orEmpty())
            if (session == null) {
                // the token is not logged; the pause slows guessing down
                log.warn("failed login from {}", r.headers["x-remote"])
                delay(failedLoginDelay.toMillis())
                WebResponse.page(401, loginPage("The token is not valid."))
            } else {
                WebResponse.redirect("/", listOf(sessionCookie(session.id)))
            }
        }
        router.post("/logout", Permission.AUTHENTICATED) { r ->
            r.session?.let { sessions.end(it.id) }
            WebResponse(200, ByteArray(0), headers = mapOf("HX-Redirect" to "/login"), cookies = listOf(sessionCookie("", 0)))
        }
        val dashboard = DashboardPage(core)
        router.get("/", Permission.READ) { r -> render("Dashboard", r, dashboard.content(r.session!!)) }
    }

    /** A full page for [request] with [content] in the frame. */
    public fun render(title: String, request: WebRequest, content: Html): WebResponse =
        WebResponse.page(200, layout.page(title, request.session, content, openMode = users == null, path = request.path))

    /** A page in the frame for a status other than 200: [title] and [text] say what happened. */
    public fun problem(status: Int, title: String, text: String, request: WebRequest): WebResponse =
        WebResponse.page(status, layout.page(title, request.session, problemContent(title, text), openMode = users == null, path = request.path))

    private fun loginPage(error: String?): Html {
        val content = h(
            "<div class=\"auth\"><div class=\"auth-brand\"><svg viewBox=\"0 0 24 24\" width=\"28\" height=\"28\" aria-hidden=\"true\"><circle cx=\"12\" cy=\"12\" r=\"9\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"/><circle cx=\"12\" cy=\"12\" r=\"3.2\" fill=\"currentColor\"/></svg>Cringle</div>" +
                "<form class=\"panel auth-card\" method=\"post\" action=\"/login\"><h1>Sign in</h1><p class=\"subtitle\">{}</p>{}" +
                "<label class=\"field\"><span>Access token</span><input type=\"password\" name=\"token\" autocomplete=\"off\" autofocus required></label><button class=\"btn primary block\">Sign in</button></form>" +
                "<p class=\"auth-key\">Check that this server shows the key<br><code>{}</code></p></div>",
            if (users == null) "This server runs without user management: everybody gets in." else "Use the token of your user.",
            if (error != null) h("<p class=\"notice error\" role=\"alert\">{}</p>", error) else Html(""),
            fingerprint,
        )
        return layout.page("Sign in", null, content, openMode = users == null)
    }

    private fun sessionCookie(value: String, maxAge: Int? = null): String =
        "cringle_session=$value; HttpOnly; Secure; SameSite=Strict; Path=/" + (maxAge?.let { "; Max-Age=$it" } ?: "")

    private fun handle(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val uri: URI = exchange.requestURI
        val path = uri.rawPath ?: "/"
        val response = when {
            path.startsWith("/static/") -> if (method == "GET" || method == "HEAD") staticFile(path.removePrefix("/static/"), exchange.requestHeaders.getFirst("If-None-Match")) else status(405)
            else -> dynamic(exchange, method, path, uri)
        }
        send(exchange, response, method == "HEAD")
    }

    private fun dynamic(exchange: HttpExchange, method: String, path: String, uri: URI): WebResponse {
        val headers = HashMap<String, String>()
        exchange.requestHeaders.forEach { (k, v) -> headers[k.lowercase()] = v.firstOrNull().orEmpty() }
        headers["x-remote"] = exchange.remoteAddress.address.hostAddress
        val found = router.find(method, path)
        var session = sessions.find(cookie(headers["cookie"], "cringle_session"))
        if (found == null) {
            return when {
                router.knows(path) -> status(405)
                method == "GET" -> WebResponse.page(404, layout.page("Not found", session, problemContent("Not found", "There is no page at this address."), openMode = users == null, path = path))
                else -> notFound()
            }
        }
        val (route, params) = found
        var newCookie: List<String> = emptyList()
        if (session == null && !sessions.loginRequired) {
            session = sessions.open()
            newCookie = listOf(sessionCookie(session.id))
        }
        if (route.permission != null) {
            if (session == null) return unauthenticated(headers)
            if (!(if (route.scopes == null) session.canAnywhere(route.permission) else session.canFor(route.permission, route.scopes))) return forbidden(session, path)
        }
        if (method == "POST" && session != null && route.pattern != "/login" && !csrfOk(session, headers["x-csrf-token"])) {
            return WebResponse.page(403, layout.page("Refused", session, problemContent("Refused", "The request has no valid CSRF token. Reload the page and try again."), openMode = users == null, path = path))
        }
        val body = readBody(exchange, route.maxBodyBytes ?: maxBodyBytes)
        val form = if (headers["content-type"]?.startsWith("application/x-www-form-urlencoded") == true) parseQuery(String(body)) else emptyMap()
        val request = WebRequest(method, path, parseQuery(uri.rawQuery.orEmpty()), form, headers, session, params, body)
        val response = runBlocking { route.handler(request) }
        return if (newCookie.isEmpty()) response else WebResponse(response.status, response.body, response.contentType, response.headers, response.cookies + newCookie)
    }

    private fun csrfOk(session: Session, header: String?): Boolean =
        header != null && MessageDigest.isEqual(header.toByteArray(), session.csrfToken.toByteArray())

    private fun unauthenticated(headers: Map<String, String>): WebResponse =
        if (headers["hx-request"] == "true") WebResponse(401, ByteArray(0), headers = mapOf("HX-Redirect" to "/login")) else WebResponse.redirect("/login")

    private fun forbidden(session: Session, path: String): WebResponse =
        WebResponse.page(403, layout.page("Forbidden", session, problemContent("Forbidden", "Your user may not do this."), openMode = users == null, path = path))

    private fun notFound(): WebResponse = WebResponse(404, "Not found".toByteArray(), "text/plain; charset=utf-8")

    private fun status(code: Int): WebResponse = WebResponse(code, ByteArray(0), "text/plain; charset=utf-8")

    private fun readBody(exchange: HttpExchange, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        exchange.requestBody.use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > limit) throw IllegalArgumentException("request too large")
            }
        }
        return out.toByteArray()
    }

    private fun cookie(header: String?, name: String): String? =
        header?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8)
            map[key] = URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8)
        }
        return map
    }

    private fun staticFile(name: String, ifNoneMatch: String?): WebResponse {
        val clean = name.split('/')
        if (name.isEmpty() || clean.any { it.isEmpty() || it == "." || it == ".." || '\\' in it || '\u0000' in it }) return notFound()
        val bytes = WebServer::class.java.getResourceAsStream("/web/$name")?.use { it.readBytes() } ?: return notFound()
        val etag = "\"" + MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) } + "\""
        val headers = mapOf("ETag" to etag, "Cache-Control" to "no-cache")
        if (ifNoneMatch == etag) return WebResponse(304, ByteArray(0), contentType(name), headers)
        return WebResponse(200, bytes, contentType(name), headers)
    }

    private fun contentType(name: String): String = when (name.substringAfterLast('.')) {
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    private fun send(exchange: HttpExchange, response: WebResponse, headOnly: Boolean) {
        val h = exchange.responseHeaders
        h.set("Content-Type", response.contentType)
        h.set("X-Content-Type-Options", "nosniff")
        h.set("Referrer-Policy", "same-origin")
        h.set("X-Frame-Options", "DENY")
        // Alpine.js evaluates the expressions of x-data with new Function, which needs unsafe-eval; the pages contain no inline script
        h.set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'")
        if (response.contentType.startsWith("text/html")) h.set("Cache-Control", "no-store")
        response.headers.forEach { (k, v) -> h.set(k, v) }
        response.cookies.forEach { h.add("Set-Cookie", it) }
        if (response.status == 304 || response.status == 204 || headOnly) {
            exchange.sendResponseHeaders(response.status, -1)
        } else {
            exchange.sendResponseHeaders(response.status, if (response.body.isEmpty()) -1 else response.body.size.toLong())
            if (response.body.isNotEmpty()) exchange.responseBody.use { it.write(response.body) }
        }
        exchange.close()
    }

    private fun fail(exchange: HttpExchange, e: Throwable) {
        val code = if (e is IllegalArgumentException) 400 else 500
        if (code == 500) log.error("web request failed", e)
        val page = if (code == 400) problemContent("Bad request", "The request could not be understood.") else problemContent("Something went wrong", "The server could not answer this request. Details are in its log.")
        runCatching { send(exchange, WebResponse.page(code, layout.page(if (code == 400) "Bad request" else "Error", null, page)), false) }
    }

    private fun version(): String = WebServer::class.java.`package`?.implementationVersion ?: "dev"
}
