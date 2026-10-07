// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.management.test.ManagementTls
import cringle.contract.UserRole
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import cringle.user.v1.UserServiceGrpcKt.UserServiceCoroutineStub
import cringle.user.v1.WhoAmIRequest
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.MetadataUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class BootstrapTokenTest {
    @TempDir
    lateinit var dir: Path

    private val tokenFile get() = dir.resolve("bootstrap-token")

    private fun users() = UserManager(FileUserStore(dir.resolve("users.json")))

    @Test
    fun theTokenIsInTheFileWhenTheOutputIsLost() {
        // the process dies right after the user was stored and before the token was printed: `shown` is never seen
        val shown = users().bootstrap(BootstrapTokenFile(tokenFile)::write)!!
        assertTrue(Files.exists(tokenFile))
        assertEquals(shown, Files.readString(tokenFile).trim())

        // after the restart nothing new is created, the file is still there, and the token works
        val restarted = users()
        assertNull(restarted.bootstrap(BootstrapTokenFile(tokenFile)::write))
        assertEquals(shown, Files.readString(tokenFile).trim())
        assertEquals("admin", restarted.authenticate(shown)?.name)
    }

    @Test
    fun theFileHoldsTheTokenBeforeTheUserIsStored() {
        // if storing the user fails, what was written for the token is overwritten by the next attempt, never stale
        val failing = object : cringle.router.users.UserStore {
            var failures = 1
            val real = FileUserStore(dir.resolve("users.json"))

            override fun load() = real.load()

            override fun save(data: cringle.router.users.UserData) {
                if (failures-- > 0) throw java.io.IOException("disk full")
                real.save(data)
            }
        }
        val file = BootstrapTokenFile(tokenFile)
        val first = UserManager(failing)
        assertThrows<java.io.IOException> { first.bootstrap(file::write) }
        val staleToken = Files.readString(tokenFile).trim()
        assertNull(users().authenticate(staleToken))

        val second = UserManager(failing)
        val token = second.bootstrap(file::write)!!
        assertEquals(token, Files.readString(tokenFile).trim())
        assertEquals("admin", second.authenticate(token)?.name)
    }

    @Test
    fun theFileIsDeletedAtTheFirstLoginWithTheTokenAndOnlyThen(): Unit = runBlocking {
        val shown = users().bootstrap(BootstrapTokenFile(tokenFile)::write)!!
        // a restart: the file is picked up again
        val manager = users()
        val other = manager.createToken(manager.listUsers().single().user.id, "other", null).secret
        val viewer = manager.createToken(manager.createUser("v", setOf(UserRole.VIEWER)).user.id, "v", null).secret
        val file = BootstrapTokenFile(tokenFile)
        val core = ManagementTls(dir.resolve("tls")).core(ManagementStore(dir.resolve("state.json")))
        val server = ManagementServer(core, users = manager, recoverOnStart = false, onAuthenticated = file::used).start()
        val channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
        fun whoAmI(token: String?): String = runBlocking {
            var stub = UserServiceCoroutineStub(channel)
            if (token != null) {
                val headers = Metadata().apply { put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer $token") }
                stub = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
            }
            stub.whoAmI(WhoAmIRequest.getDefaultInstance()).user.name
        }
        try {
            // wrong, missing and other tokens do not touch the file
            assertEquals(Status.Code.UNAUTHENTICATED, assertThrows<StatusException> { whoAmI("crt_wrong") }.status.code)
            assertEquals(Status.Code.UNAUTHENTICATED, assertThrows<StatusException> { whoAmI(null) }.status.code)
            assertEquals("v", whoAmI(viewer))
            assertEquals("admin", whoAmI(other))
            assertTrue(Files.exists(tokenFile), "only the bootstrap token itself removes the file")

            assertEquals("admin", whoAmI(shown))
            assertFalse(Files.exists(tokenFile), "the file is gone after the first login")
            // the token itself keeps working
            assertEquals("admin", whoAmI(shown))
        } finally {
            channel.shutdownNow()
            server.close()
            core.close()
        }
    }
}
