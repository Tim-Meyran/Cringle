// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.common.PublicKeyFingerprint
import cringle.contract.UserRole
import cringle.management.ManagementData
import cringle.management.ManagementStore
import cringle.management.MachineRecord
import cringle.management.test.ManagementTls
import cringle.router.users.FileUserStore
import cringle.router.users.Permission
import cringle.router.users.UserManager
import java.net.URI
import java.net.Socket
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Tag("integration")
class WebServerTest {
    @TempDir
    lateinit var dir: Path

    private class TestClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = now
    }

    private val clock = TestClock()
    private val closeables = ArrayList<AutoCloseable>()
    private lateinit var tls: ManagementTls
    private lateinit var users: UserManager
    private lateinit var web: WebServer
    private lateinit var adminToken: String
    private lateinit var viewerToken: String
    private lateinit var client: HttpClient

    @BeforeEach
    fun start() {
        users = UserManager(FileUserStore(dir.resolve("users.json")), clock)
        adminToken = users.bootstrap()!!
        viewerToken = users.createToken(users.createUser("vera", setOf(UserRole.VIEWER)).user.id, "web", null).secret
        tls = ManagementTls(dir.resolve("tls"))
        val store = ManagementStore(dir.resolve("state.json"))
        store.save(ManagementData(machines = listOf(MachineRecord("<script>x</script>", "127.0.0.1:1", "127.0.0.1", null))))
        val core = tls.core(store)
        closeables += core
        web = WebServer(core, users, 0, clock = clock, idleTimeout = Duration.ofHours(8), failedLoginDelay = Duration.ofMillis(300))
        web.router.post("/operate", Permission.OPERATE) { WebResponse.page(200, raw("operated")) }
        web.start()
        closeables += web
        // trusts exactly the key of the ManagementServer, as a browser user does after checking the fingerprint
        val manager = object : X509ExtendedTrustManager() {
            private fun check(chain: Array<X509Certificate>) {
                check(PublicKeyFingerprint.of(chain[0]) == tls.identity.publicKeyFingerprint) { "unexpected server key" }
            }

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw UnsupportedOperationException()

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = throw UnsupportedOperationException()

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = throw UnsupportedOperationException()

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = check(chain)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = check(chain)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = check(chain)

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLSv1.3").also { it.init(null, arrayOf<TrustManager>(manager), null) }
        client = HttpClient.newBuilder().sslContext(context).followRedirects(HttpClient.Redirect.NEVER).build()
    }

    @AfterEach
    fun stop() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    private fun uri(path: String) = URI.create("https://localhost:${web.port}$path")

    private fun get(path: String, cookie: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val b = HttpRequest.newBuilder(uri(path))
        cookie?.let { b.header("Cookie", it) }
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, form: Map<String, String> = emptyMap(), cookie: String? = null, csrf: String? = null): HttpResponse<String> {
        val body = form.entries.joinToString("&") { URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(it.value, Charsets.UTF_8) }
        val b = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body))
        cookie?.let { b.header("Cookie", it) }
        csrf?.let { b.header("X-CSRF-Token", it) }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private class Login(val cookie: String, val csrf: String, val setCookie: String)

    private fun login(token: String): Login {
        val response = post("/login", mapOf("token" to token))
        assertEquals(303, response.statusCode(), response.body())
        val setCookie = response.headers().firstValue("Set-Cookie").get()
        val cookie = setCookie.substringBefore(';')
        val page = get("/", cookie).body()
        val csrf = Regex("\"X-CSRF-Token\": \"([^\"]+)\"").find(page)!!.groupValues[1]
        return Login(cookie, csrf, setCookie)
    }

    @Test
    fun loginPageShowsTheKeyAndSecurityHeaders() {
        val response = get("/login")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains(tls.identity.publicKeyFingerprint))
        assertTrue(response.headers().firstValue("Content-Security-Policy").get().startsWith("default-src 'self'"))
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").get())
        assertEquals("same-origin", response.headers().firstValue("Referrer-Policy").get())
        assertEquals("no-store", response.headers().firstValue("Cache-Control").get())
    }

    @Test
    fun wrongTokenIsRefusedAfterADelay() {
        val started = System.nanoTime()
        val response = post("/login", mapOf("token" to "wrong"))
        assertEquals(401, response.statusCode())
        assertTrue(Duration.ofNanos(System.nanoTime() - started) >= Duration.ofMillis(250))
        assertFalse(response.headers().firstValue("Set-Cookie").isPresent)
        assertFalse(response.body().contains("wrong"))
    }

    @Test
    fun rightTokenGivesASessionCookieWithFlags() {
        val login = login(adminToken)
        for (flag in listOf("HttpOnly", "Secure", "SameSite=Strict", "Path=/")) assertTrue(login.setCookie.contains(flag), login.setCookie)
        assertEquals(200, get("/", login.cookie).statusCode())
    }

    @Test
    fun pagesNeedTheSession() {
        val response = get("/")
        assertEquals(303, response.statusCode())
        assertEquals("/login", response.headers().firstValue("Location").get())
        val htmx = get("/", headers = mapOf("HX-Request" to "true"))
        assertEquals(401, htmx.statusCode())
        assertEquals("/login", htmx.headers().firstValue("HX-Redirect").get())
    }

    @Test
    fun postNeedsTheCsrfToken() {
        val login = login(adminToken)
        assertEquals(403, post("/operate", cookie = login.cookie).statusCode())
        assertEquals(403, post("/operate", cookie = login.cookie, csrf = "wrong").statusCode())
        val ok = post("/operate", cookie = login.cookie, csrf = login.csrf)
        assertEquals(200, ok.statusCode())
        assertEquals("operated", ok.body())
    }

    @Test
    fun logoutEndsTheSession() {
        val login = login(adminToken)
        val out = post("/logout", cookie = login.cookie, csrf = login.csrf)
        assertEquals("/login", out.headers().firstValue("HX-Redirect").get())
        assertEquals(303, get("/", login.cookie).statusCode())
    }

    @Test
    fun aViewerCannotReachAnOperateRoute() {
        val login = login(viewerToken)
        assertEquals(200, get("/", login.cookie).statusCode())
        assertEquals(403, post("/operate", cookie = login.cookie, csrf = login.csrf).statusCode())
    }

    @Test
    fun anIdleSessionExpires() {
        val login = login(adminToken)
        clock.now = clock.now.plus(Duration.ofHours(7))
        assertEquals(200, get("/", login.cookie).statusCode())
        clock.now = clock.now.plus(Duration.ofHours(9))
        val response = get("/", login.cookie)
        assertEquals(303, response.statusCode())
        assertEquals("/login", response.headers().firstValue("Location").get())
    }

    @Test
    fun vendoredLibrariesAreServedWithEtag() {
        val types = mapOf(
            "htmx.min.js" to "text/javascript",
            "alpine.min.js" to "text/javascript",
            "drawflow.min.js" to "text/javascript",
            "drawflow.min.css" to "text/css",
        )
        for ((name, type) in types) {
            val response = get("/static/vendor/$name")
            assertEquals(200, response.statusCode(), name)
            assertTrue(response.headers().firstValue("Content-Type").get().startsWith(type), name)
            assertTrue(response.body().length > 1000, name)
            val etag = response.headers().firstValue("ETag").get()
            assertEquals(304, get("/static/vendor/$name", headers = mapOf("If-None-Match" to etag)).statusCode(), name)
        }
        assertEquals(200, get("/static/app.css").statusCode())
        assertEquals(200, get("/static/app.js").statusCode())
    }

    @Test
    fun aPathOutsideTheStaticFolderIsNotFound() {
        for (path in listOf("/static/../web/app.css", "/static/%2e%2e/web/app.css", "/static/vendor/../../logback.xml", "/static/", "/static/nothing.js", "/static/..%5Capp.css")) {
            assertEquals(404, get(path).statusCode(), path)
        }
    }

    @Test
    fun dynamicValuesAreEscaped() {
        val login = login(adminToken)
        val page = get("/", login.cookie).body()
        assertTrue(page.contains("&lt;script&gt;x&lt;/script&gt;"), page)
        assertFalse(page.contains("<script>x</script>"))
        assertNotNull(page)
    }

    @Test
    fun withoutAuthTheLayerIsOpenAndSaysSo() {
        val core = tls.core(ManagementStore(dir.resolve("open.json")))
        closeables += core
        val open = WebServer(core, null, 0).start()
        closeables += open
        val response = client.send(HttpRequest.newBuilder(URI.create("https://localhost:${open.port}/")).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("without <code>--auth</code>"))
    }
}
