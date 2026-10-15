// SPDX-License-Identifier: Apache-2.0

package cringle.engine.drivers

import cringle.common.ComponentKind
import cringle.common.Identity
import cringle.contract.AuthenticatedUser
import cringle.contract.BuiltinDriverTypes
import cringle.contract.DriverType
import cringle.contract.HttpResponse
import cringle.contract.HttpRoute
import cringle.contract.HttpRouteException
import cringle.contract.UserManagementDriver
import cringle.contract.UserRole
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse as ClientResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

@Tag("integration")
class HttpServiceTest {
    @TempDir
    lateinit var dir: Path

    private val closeables = ArrayList<AutoCloseable>()
    private val client = HttpClient.newHttpClient()

    private object Users : UserManagementDriver {
        override val type: DriverType = BuiltinDriverTypes.USER_MANAGEMENT

        override suspend fun authenticate(token: String): AuthenticatedUser? = when (token) {
            "end-user" -> AuthenticatedUser("u1", "ada", setOf(UserRole.END_USER))
            "viewer" -> AuthenticatedUser("u2", "vera", setOf(UserRole.VIEWER))
            else -> null
        }

        override suspend fun hasRole(user: AuthenticatedUser, role: UserRole): Boolean = role in user.roles
    }

    @AfterEach
    fun stop() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    private fun service(users: UserManagementDriver? = Users, identity: Identity? = null, port: Int = 0): HttpService =
        HttpService(port, null, identity, users).also { closeables += it }

    private fun get(service: HttpService, path: String, method: String = "GET", body: String? = null, token: String? = null, scheme: String = "http", http: HttpClient = client): ClientResponse<String> {
        val b = HttpRequest.newBuilder(URI.create("$scheme://localhost:${service.boundPort}$path"))
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody()) else b.method(method, HttpRequest.BodyPublishers.ofString(body))
        token?.let { b.header("Authorization", "Bearer $it") }
        return http.send(b.build(), ClientResponse.BodyHandlers.ofString())
    }

    private fun ok(text: String): suspend (cringle.contract.HttpRequest) -> HttpResponse = { HttpResponse(200, body = text) }

    @Test
    fun aRouteAnswersWithTheRequestOfTheClient() {
        val s = service()
        s.register("a/b", HttpRoute("POST", "/orders/{id}/lines/{line}")) { r ->
            HttpResponse(201, mapOf("X-Echo" to r.params.toString()), "${r.method} ${r.path} ${r.params} ${r.query} ${r.headers["x-test"]} ${r.body}")
        }
        val response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:${s.boundPort}/orders/7/lines/a%20b?x=1&y=two%20words")).header("X-Test", "t").POST(HttpRequest.BodyPublishers.ofString("payload")).build(),
            ClientResponse.BodyHandlers.ofString(),
        )
        assertEquals(201, response.statusCode())
        assertEquals("POST /orders/7/lines/a%20b {id=7, line=a b} {x=1, y=two words} t payload", response.body())
        assertEquals("{id=7, line=a b}", response.headers().firstValue("X-Echo").get())
    }

    @Test
    fun theAnswersOfTheServerItself() {
        val s = service()
        s.register("a/b", HttpRoute("GET", "/things"), ok("things"))
        s.register("a/c", HttpRoute("DELETE", "/things"), ok("deleted"))
        assertEquals(404, get(s, "/nothing").statusCode())
        val wrong = get(s, "/things", "POST", "x")
        assertEquals(405, wrong.statusCode())
        assertEquals("DELETE, GET", wrong.headers().firstValue("Allow").get())
        assertEquals("things", get(s, "/things").body())
        assertTrue(get(s, "/nothing").body().contains("\"error\""), "an error is JSON")
    }

    @Test
    fun theMoreSpecificRouteWinsAndARouteIsUnique() {
        val s = service()
        s.register("a/p", HttpRoute("GET", "/items/{id}"), ok("param"))
        s.register("a/l", HttpRoute("GET", "/items/new"), ok("literal"))
        assertEquals("literal", get(s, "/items/new").body())
        assertEquals("param", get(s, "/items/5").body())
        val e = assertThrows<HttpRouteException> { s.register("a/x", HttpRoute("get", "/items/{other}"), ok("again")) }
        assertTrue(e.message!!.contains("already served for a/p"), e.message)
        assertThrows<HttpRouteException> { s.register("a/x", HttpRoute("GET", "no-slash"), ok("")) }
        assertThrows<HttpRouteException> { s.register("a/x", HttpRoute("GET", "/a/{b}/{b}"), ok("")) }
        assertThrows<HttpRouteException> { s.register("a/x", HttpRoute("GET", "/a/x{b}"), ok("")) }
        // the same path with another method is another route
        s.register("a/x", HttpRoute("PUT", "/items/{id}"), ok("put"))
        assertEquals("put", get(s, "/items/5", "PUT", "").body())
    }

    @Test
    fun closingARegistrationFreesTheRoute() {
        val s = service()
        val first = s.register("a/b", HttpRoute("GET", "/x"), ok("one"))
        assertEquals("one", get(s, "/x").body())
        first.close()
        assertEquals(404, get(s, "/x").statusCode())
        s.register("c/d", HttpRoute("GET", "/x"), ok("two"))
        assertEquals("two", get(s, "/x").body())
    }

    @Test
    fun aFailingSlowOrUnconnectedHandlerIsAnswered() {
        val s = service()
        s.register("a/f", HttpRoute("GET", "/fail")) { throw RuntimeException("secret detail") }
        s.register("a/n", HttpRoute("GET", "/open")) { throw IllegalStateException("port 'handler' of block 'e' is not connected in the blueprint") }
        s.register("a/s", HttpRoute("GET", "/slow", timeout = Duration.ofMillis(100))) { delay(5_000); HttpResponse(200) }
        val failed = get(s, "/fail")
        assertEquals(500, failed.statusCode())
        assertTrue(!failed.body().contains("secret"), "the detail stays in the log")
        assertEquals(503, get(s, "/open").statusCode())
        assertEquals(504, get(s, "/slow").statusCode())
    }

    @Test
    fun aBodyAboveTheLimitIsRefused() {
        val s = service()
        s.register("a/b", HttpRoute("POST", "/upload")) { r -> HttpResponse(200, body = r.body.length.toString()) }
        assertEquals("1000", get(s, "/upload", "POST", "x".repeat(1000)).body())
        assertEquals(413, get(s, "/upload", "POST", "x".repeat(HttpService.MAX_BODY + 1)).statusCode())
    }

    @Test
    fun aProtectedRouteNeedsTheTokenOfAUserWhoMayUseApplications() {
        val s = service()
        s.register("a/b", HttpRoute("GET", "/private", auth = true)) { r -> HttpResponse(200, body = "hello ${r.user?.name}") }
        s.register("a/o", HttpRoute("GET", "/public"), ok("open"))
        assertEquals("open", get(s, "/public").body(), "an open route needs no token")
        val none = get(s, "/private")
        assertEquals(401, none.statusCode())
        assertEquals("Bearer", none.headers().firstValue("WWW-Authenticate").get())
        assertEquals(401, get(s, "/private", token = "nonsense").statusCode())
        assertEquals(403, get(s, "/private", token = "viewer").statusCode(), "a viewer may not use applications")
        assertEquals("hello ada", get(s, "/private", token = "end-user").body())
        // without user management nobody gets in
        val closed = service(users = null)
        closed.register("a/b", HttpRoute("GET", "/private", auth = true), ok("secret"))
        assertEquals(401, get(closed, "/private", token = "end-user").statusCode())
    }

    @Test
    fun httpsUsesACertificateWithHostNames() {
        val identity = Identity.loadOrCreate(dir.resolve("engine"), ComponentKind.ENGINE.commonName("e1"))
        val s = service(identity = identity)
        s.register("a/b", HttpRoute("GET", "/secure"), ok("tls"))
        val cert = Files.newInputStream(dir.resolve("engine").resolve("certs").resolve("web.crt")).use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val store = KeyStore.getInstance("PKCS12").also { it.load(null, null); it.setCertificateEntry("api", cert) }
        val context = SSLContext.getInstance("TLSv1.3").also {
            it.init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).also { f -> f.init(store) }.trustManagers, null)
        }
        val verifying = HttpClient.newBuilder().sslContext(context).sslParameters(SSLParameters().also { it.endpointIdentificationAlgorithm = "HTTPS" }).build()
        assertEquals("tls", get(s, "/secure", scheme = "https", http = verifying).body())
        // a client that does not trust the certificate is refused
        assertThrows<java.io.IOException> { get(s, "/secure", scheme = "https") }
    }

    @Test
    fun aPortThatIsTakenIsReportedWithAHint() {
        val first = service()
        first.register("a/b", HttpRoute("GET", "/x"), ok("x"))
        val second = service(port = first.boundPort)
        val e = assertThrows<HttpRouteException> { second.register("c/d", HttpRoute("GET", "/y"), ok("y")) }
        assertTrue(e.message!!.contains("api.port"), e.message)
    }
}
