// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks

import cringle.contract.BuiltinDriverTypes
import cringle.contract.DriverType
import cringle.contract.HttpDriver
import cringle.contract.HttpRegistration
import cringle.contract.HttpRequest
import cringle.contract.HttpResponse
import cringle.contract.HttpRoute
import cringle.testkit.BlockTestHarness
import cringle.testkit.TestDriverSet
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HttpAnswerBlocksTest {
    private val unconnected: suspend (Any) -> Any = { throw IllegalStateException("port 'source' of block 'r' is not connected in the blueprint") }

    private fun block(name: String, config: Map<String, Any?>) = BlockTestHarness.forProvider(StdBlockProvider(), name, TestDriverSet(), config)

    private val endpointRequest = mapOf(
        "method" to "POST", "path" to "/orders/7", "params" to mapOf("id" to "7"), "query" to mapOf("q" to "x y"), "headers" to mapOf("x-key" to "k1"),
        "body" to """{"item":"pen","qty":2}""",
    )

    @Test
    fun inputJsonAnswersWithTheStructure() = runTest {
        block("input.json", mapOf("value" to """{"a":[1,2,{"b":true}],"c":"d"}""")).runLifecycle {
            assertEquals(mapOf("a" to listOf(1L, 2L, mapOf("b" to true)), "c" to "d"), request("get", "?"))
            sendMessage("trigger", 1L)
            assertEquals(1, ports.tether("out").sentMessages.size)
        }
        assertThrows<IllegalArgumentException> { block("input.json", emptyMap()).init() }
        assertThrows<IllegalArgumentException> { block("input.json", mapOf("value" to "{nope")).init() }
    }

    @Test
    fun theResponseBlockUsesTheSourceOrTheConfiguredBody() = runTest {
        block("http.response", mapOf("status" to 201L, "contentType" to "text/csv", "headers" to mapOf("X-Id" to "5"), "body" to "a,b")).runLifecycle {
            ports.tether("source").requestHandler = unconnected
            val fixed = request("request", endpointRequest) as Map<*, *>
            assertEquals(201L, fixed["status"])
            assertEquals("a,b", fixed["body"])
            assertEquals(mapOf("X-Id" to "5", "Content-Type" to "text/csv"), fixed["headers"])

            ports.tether("source").requestHandler = { mapOf("ok" to true) }
            val dynamic = request("request", endpointRequest) as Map<*, *>
            assertEquals(mapOf("ok" to true), dynamic["body"], "the answer of the source is the body")
            assertEquals(endpointRequest, ports.tether("source").requests.last(), "the source gets the request")
        }
        block("http.response", emptyMap()).runLifecycle {
            ports.tether("source").requestHandler = unconnected
            val defaults = request("request", endpointRequest) as Map<*, *>
            assertEquals(200L, defaults["status"])
            assertEquals("", defaults["body"])
        }
        block("http.response", mapOf("body" to "Hello {}!")).runLifecycle {
            ports.tether("source").requestHandler = unconnected
            assertEquals("Hello Ada!", (request("request", "Ada") as Map<*, *>)["body"], "a text request goes into the template")
            assertEquals("Hello {\"a\":1}!", (request("request", mapOf("a" to 1L)) as Map<*, *>)["body"])
        }
        assertThrows<IllegalArgumentException> { block("http.response", mapOf("status" to 99L)).init() }
    }

    @Test
    fun aFailureOfTheSourceIsNotHidden() = runTest {
        block("http.response", emptyMap()).runLifecycle {
            ports.tether("source").requestHandler = { throw IllegalStateException("the source failed") }
            assertThrows<IllegalStateException> { request("request", endpointRequest) }
        }
    }

    @Test
    fun theRequestFieldTakesOnePartOfTheRequest() = runTest {
        val cases = mapOf(
            "body" to endpointRequest["body"], "method" to "POST", "path" to "/orders/7", "params.id" to "7", "query.q" to "x y", "headers.X-Key" to "k1",
            "params.missing" to "", "user" to "",
        )
        for ((field, expected) in cases) {
            block("http.request-field", mapOf("field" to field)).runLifecycle {
                ports.tether("next").requestHandler = { "got:$it" }
                assertEquals("got:$expected", request("request", endpointRequest), field)
            }
        }
        block("http.request-field", mapOf("field" to "body", "json" to true)).runLifecycle {
            ports.tether("next").requestHandler = { it }
            assertEquals(mapOf("item" to "pen", "qty" to 2L), request("request", endpointRequest), "the body parsed as JSON")
        }
        block("http.request-field", mapOf("field" to "query.q", "json" to true)).runLifecycle {
            ports.tether("next").requestHandler = { it }
            assertThrows<IllegalArgumentException> { request("request", endpointRequest) }
        }
        for (bad in listOf("", "bodies", "params.", "headers")) assertThrows<IllegalArgumentException>(bad) { block("http.request-field", mapOf("field" to bad)).init() }
    }

    @Test
    fun theEndpointSetsTheContentTypeOfATextAnswer() = runTest {
        val http = object : HttpDriver {
            override val type: DriverType = BuiltinDriverTypes.HTTP
            var handler: (suspend (HttpRequest) -> HttpResponse)? = null

            override suspend fun register(route: HttpRoute, handler: suspend (HttpRequest) -> HttpResponse): HttpRegistration {
                this.handler = handler
                return object : HttpRegistration {
                    override val route: HttpRoute = route

                    override fun close() {}
                }
            }
        }
        val h = BlockTestHarness.forProvider(StdBlockProvider(), "http.endpoint", TestDriverSet().add(HttpDriver::class, http), mapOf("path" to "/x", "contentType" to "text/csv"))
        h.runLifecycle {
            val tether = ports.tether("handler")
            val request = HttpRequest("GET", "/x", emptyMap(), emptyMap(), emptyMap(), "", null)
            tether.requestHandler = { "a,b" }
            assertEquals("text/csv", http.handler!!(request).headers["Content-Type"])
            tether.requestHandler = { mapOf("a" to 1L) }
            assertEquals("application/json; charset=utf-8", http.handler!!(request).headers["Content-Type"], "a JSON answer keeps JSON")
            tether.requestHandler = { mapOf("body" to "x", "headers" to mapOf("content-type" to "text/html")) }
            assertEquals(null, http.handler!!(request).headers["Content-Type"], "an own header wins")
        }
    }
}
