// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import cringle.contract.AuthenticatedUser
import cringle.contract.BuiltinDriverTypes
import cringle.contract.DriverType
import cringle.contract.HttpDriver
import cringle.contract.HttpRegistration
import cringle.contract.HttpRequest
import cringle.contract.HttpResponse
import cringle.contract.HttpRoute
import cringle.contract.HttpRouteException
import cringle.contract.UserRole
import cringle.testkit.BlockTestHarness
import cringle.testkit.TestDriverSet
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class HttpEndpointBlockTest {
    private class FakeHttp : HttpDriver {
        override val type: DriverType = BuiltinDriverTypes.HTTP
        var route: HttpRoute? = null
        var handler: (suspend (HttpRequest) -> HttpResponse)? = null
        var failWith: HttpRouteException? = null

        override suspend fun register(route: HttpRoute, handler: suspend (HttpRequest) -> HttpResponse): HttpRegistration {
            failWith?.let { throw it }
            this.route = route
            this.handler = handler
            return object : HttpRegistration {
                override val route: HttpRoute = route

                override fun close() {
                    this@FakeHttp.handler = null
                }
            }
        }
    }

    private fun harness(http: FakeHttp, config: Map<String, Any?>) =
        BlockTestHarness.forProvider(StdBlockProvider(), "http.endpoint", TestDriverSet().add(HttpDriver::class, http), config)

    private fun request(user: AuthenticatedUser? = null) =
        HttpRequest("GET", "/orders/7", mapOf("id" to "7"), mapOf("q" to "1"), mapOf("x-a" to "b"), "body", user)

    @Test
    fun theDefinitionDeclaresTheApiPortAsExclusive() {
        val definition = StdBlockProvider().definitions.single { it.name == "http.endpoint" }
        assertEquals(listOf(cringle.contract.ExclusiveResource(cringle.contract.ExclusiveKind.PORT, "api")), definition.exclusiveResources)
    }

    @Test
    fun theRouteIsServedFromStartToStop() = runTest {
        val http = FakeHttp()
        val h = harness(http, mapOf("path" to "/orders/{id}", "method" to "post", "auth" to true, "timeoutMs" to 5000L))
        h.runLifecycle {
            assertEquals(HttpRoute("POST", "/orders/{id}", true, Duration.ofMillis(5000)), http.route)
            assertTrue(http.handler != null)
        }
        assertNull(http.handler, "the route ends with the block")
    }

    @Test
    fun theDefaultsAreAGetRouteWithoutTokens() = runTest {
        val http = FakeHttp()
        harness(http, mapOf("path" to "/ping")).runLifecycle { assertEquals(HttpRoute("GET", "/ping", false, Duration.ofSeconds(30)), http.route) }
    }

    @Test
    fun theRequestGoesToTheHandlerAndItsAnswerBecomesTheResponse() = runTest {
        val http = FakeHttp()
        harness(http, mapOf("path" to "/orders/{id}")).runLifecycle {
            val tether = ports.tether("handler")
            tether.requestHandler = { mapOf("status" to 201L, "headers" to mapOf("X-A" to "b"), "body" to mapOf("ok" to true)) }
            val response = http.handler!!(request(AuthenticatedUser("u", "ada", setOf(UserRole.END_USER))))
            val sent = tether.requests.single() as Map<*, *>
            assertEquals(mapOf("id" to "7"), sent["params"])
            assertEquals("body", sent["body"])
            assertEquals("ada", sent["user"])
            assertEquals(201, response.status)
            assertEquals("""{"ok":true}""", response.body)
            assertEquals("b", response.headers["X-A"])
            assertEquals("application/json; charset=utf-8", response.headers["Content-Type"])

            tether.requestHandler = { "just text" }
            val plain = http.handler!!(request())
            assertEquals(200, plain.status)
            assertEquals("just text", plain.body)
            assertFalse(plain.headers.containsKey("Content-Type"))
            assertFalse((tether.requests.last() as Map<*, *>).containsKey("user"), "no user for an open route")

            tether.requestHandler = { listOf(1L, 2L) }
            assertEquals("[1,2]", http.handler!!(request()).body)
        }
    }

    @Test
    fun aBadConfigurationAndATakenRouteFailTheBlock() = runTest {
        assertThrows<IllegalArgumentException> { harness(FakeHttp(), emptyMap()).init() }
        assertThrows<IllegalArgumentException> { harness(FakeHttp(), mapOf("path" to "/x", "timeoutMs" to 0L)).init() }
        val taken = FakeHttp().also { it.failWith = HttpRouteException("the route GET /x is already served") }
        val e = assertThrows<HttpRouteException> { harness(taken, mapOf("path" to "/x")).runLifecycle { } }
        assertTrue(e.message!!.contains("already served"))
    }
}
