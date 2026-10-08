// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.common.PublicKeyFingerprint
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager
import org.junit.jupiter.api.Assertions.assertEquals

/** A browser stand-in for the tests of the pages: trusts exactly the key [serverKey], keeps one session cookie and the CSRF token. */
class WebTestClient(private val port: Int, serverKey: String) {
    private val client: HttpClient

    private var cookie: String? = null
    private var csrf: String? = null

    init {
        val manager = object : X509ExtendedTrustManager() {
            private fun check(chain: Array<X509Certificate>) {
                check(PublicKeyFingerprint.of(chain[0]) == serverKey) { "unexpected server key" }
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

    /** Logs in with [token]; later requests carry the session. */
    fun login(token: String): WebTestClient {
        val response = post("/login", mapOf("token" to token), withCsrf = false)
        assertEquals(303, response.statusCode(), response.body())
        cookie = response.headers().firstValue("Set-Cookie").get().substringBefore(';')
        csrf = Regex("\"X-CSRF-Token\": \"([^\"]+)\"").find(get("/").body())!!.groupValues[1]
        return this
    }

    fun get(path: String): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create("https://localhost:$port$path"))
        cookie?.let { b.header("Cookie", it) }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun post(path: String, form: Map<String, String> = emptyMap(), withCsrf: Boolean = true): HttpResponse<String> {
        val body = form.entries.joinToString("&") { URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(it.value, Charsets.UTF_8) }
        val b = HttpRequest.newBuilder(URI.create("https://localhost:$port$path")).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body))
        cookie?.let { b.header("Cookie", it) }
        if (withCsrf) csrf?.let { b.header("X-CSRF-Token", it) }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** Posts a file as `multipart/form-data` in the field [field]. */
    fun postFile(path: String, field: String, filename: String, data: ByteArray): HttpResponse<String> {
        val boundary = "----cringle-test-boundary"
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$filename\"\r\nContent-Type: application/octet-stream\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        val body = head.toByteArray() + data + tail.toByteArray()
        val b = HttpRequest.newBuilder(URI.create("https://localhost:$port$path")).header("Content-Type", "multipart/form-data; boundary=$boundary").POST(HttpRequest.BodyPublishers.ofByteArray(body))
        cookie?.let { b.header("Cookie", it) }
        csrf?.let { b.header("X-CSRF-Token", it) }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }
}
