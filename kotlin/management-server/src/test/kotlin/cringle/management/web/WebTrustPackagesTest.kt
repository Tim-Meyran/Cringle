// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.UserRole
import cringle.management.ServiceTestBase
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import cringle.testkit.TestPluginBuilder
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class WebTrustPackagesTest : ServiceTestBase() {
    private lateinit var admin: WebTestClient
    private lateinit var viewer: WebTestClient
    private var webPort = 0

    private fun web() {
        val users = UserManager(FileUserStore(dir.resolve("users.json")))
        val token = users.bootstrap()!!
        val viewerToken = users.createToken(users.createUser("vera", setOf(UserRole.VIEWER)).user.id, "web", null).secret
        val server = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        closeables += server
        webPort = server.port
        val key = core.identity.publicKeyFingerprint
        admin = WebTestClient(server.port, key).login(token)
        viewer = WebTestClient(server.port, key).login(viewerToken)
    }

    @Test
    fun componentsAreTrustedAndRevoked() {
        web()
        val fingerprint = "ab".repeat(32)
        val added = admin.post("/trust/components", mapOf("fingerprint" to fingerprint, "name" to "other-daemon", "kind" to "COMPONENT", "address" to "10.0.0.5:9000")).body()
        assertTrue(added.contains(fingerprint) && added.contains("other-daemon"), added)
        assertTrue(admin.post("/trust/components", mapOf("fingerprint" to "xyz", "name" to "bad")).body().contains("INVALID_ARGUMENT"))
        val revoked = admin.post("/trust/revoke", mapOf("fingerprint" to fingerprint)).body()
        assertTrue(revoked.contains("Revoked") && !revoked.contains("other-daemon"), revoked)
        assertTrue(admin.post("/trust/revoke", mapOf("fingerprint" to fingerprint)).body().contains("NOT_FOUND"))
        // the daemon of the fixture is trusted from the start
        assertTrue(admin.get("/trust").body().contains("<td>"))
    }

    @Test
    fun aRouterIsTrustedInTwoSteps() {
        web()
        // the web layer itself is a TLS server with the key of the ManagementServer: it stands in for a router
        val probe = admin.post("/trust/probe", mapOf("address" to "localhost:$webPort")).body()
        assertTrue(probe.contains(core.identity.publicKeyFingerprint) && probe.contains("Trust this router"), probe)
        assertFalse(admin.get("/trust/list").body().contains("Trust this router"), "nothing is trusted by the probe")
        assertTrue(admin.post("/trust/probe", mapOf("address" to "nonsense")).body().contains("host:port"))
        // the fixture has no router: the second step reports it inline
        val confirmed = admin.post("/trust/routers", mapOf("address" to "localhost:$webPort", "fingerprint" to core.identity.publicKeyFingerprint)).body()
        assertTrue(confirmed.contains("class=\"error\""), confirmed)
        assertTrue(admin.post("/trust/routers", mapOf("address" to "localhost:$webPort", "fingerprint" to "short")).body().contains("INVALID_ARGUMENT"))
    }

    @Test
    fun packagesAreListedUploadedAndTheirTrustIsSet() {
        web()
        val list = admin.get("/packages").body()
        assertTrue(list.contains("<strong>acme-svc</strong>") && list.contains("trusted"), list)
        assertTrue(admin.post("/packages/acme-svc/trust", mapOf("trust" to "untrusted")).body().contains("untrusted"))
        assertTrue(admin.post("/packages/acme-svc/trust", mapOf("trust" to "trusted")).body().contains("badge ok\">trusted"))
        assertTrue(admin.post("/packages/acme-svc/trust", mapOf("trust" to "maybe")).body().contains("INVALID_ARGUMENT"))

        val file = TestPluginBuilder("acme-uploaded", "1.0.0").build(Files.createDirectories(dir.resolve("upload"))).file
        val uploaded = admin.postFile("/packages/upload", "file", "acme-uploaded.cringle", Files.readAllBytes(file)).body()
        assertTrue(uploaded.contains("Published acme-uploaded 1.0.0") && uploaded.contains("<strong>acme-uploaded</strong>"), uploaded)
        assertTrue(admin.postFile("/packages/upload", "file", "broken.cringle", "not a package".toByteArray()).body().contains("class=\"error\""))
    }

    @Test
    fun aViewerSeesBothPagesReadOnlyAndCannotPost() {
        web()
        val trust = viewer.get("/trust").body()
        assertFalse(trust.contains("hx-post=\"/trust"), trust)
        assertFalse(viewer.get("/packages").body().contains("hx-post=\"/packages"))
        assertEquals(403, viewer.post("/trust/components", mapOf("fingerprint" to "ab".repeat(32), "name" to "x")).statusCode())
        assertEquals(403, viewer.post("/trust/revoke", mapOf("fingerprint" to "ab".repeat(32))).statusCode())
        assertEquals(403, viewer.post("/packages/acme-svc/trust", mapOf("trust" to "untrusted")).statusCode())
        assertEquals(403, viewer.postFile("/packages/upload", "file", "x.cringle", ByteArray(3)).statusCode())
    }
}
