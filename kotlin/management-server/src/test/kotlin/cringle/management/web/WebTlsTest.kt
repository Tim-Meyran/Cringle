// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.management.ManagementStore
import cringle.management.test.ManagementTls
import cringle.repository.PackageRepository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Tag("integration")
class WebTlsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun aClientThatTrustsTheWebCertificateConnectsByNameWithHostNameVerification() {
        val tls = ManagementTls(dir.resolve("tls"))
        val repository = tls.startRepository(PackageRepository(dir.resolve("repo")))
        val core = tls.core(ManagementStore(dir.resolve("state.json")), "127.0.0.1:${repository.port}")
        val web = WebServer(core, null, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        try {
            val found = Files.walk(dir).use { s -> s.filter { it.fileName.toString() == "web.crt" }.findFirst().get() }
            val cert = Files.newInputStream(found).use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val store = KeyStore.getInstance("PKCS12").also { it.load(null, null); it.setCertificateEntry("web", cert) }
            val context = SSLContext.getInstance("TLSv1.3").also {
                it.init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).also { f -> f.init(store) }.trustManagers, null)
            }
            // the default of the client: the host name is checked against the names of the certificate
            val params = javax.net.ssl.SSLParameters().also { it.endpointIdentificationAlgorithm = "HTTPS" }
            val client = HttpClient.newBuilder().sslContext(context).sslParameters(params).build()
            for (host in listOf("localhost", "127.0.0.1")) {
                val response = client.send(HttpRequest.newBuilder(URI.create("https://$host:${web.port}/static/app.css")).build(), HttpResponse.BodyHandlers.ofString())
                assertEquals(200, response.statusCode(), host)
            }
        } finally {
            web.close()
            core.close()
            repository.stop()
        }
    }
}
