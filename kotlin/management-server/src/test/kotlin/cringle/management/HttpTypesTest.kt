// SPDX-License-Identifier: Apache-2.0

package cringle.management

import cringle.contract.TetherType
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.Endpoint
import cringle.packaging.PackageValidator
import cringle.packaging.TetherDef
import cringle.schema.SchemaParser
import cringle.schema.SchemaRegistry
import cringle.stdblocks.StdBlockProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The typed HTTP API of the standard blocks: what the validator accepts and refuses (#421). */
class HttpTypesTest {
    private val provider = StdBlockProvider()
    private val registry = SchemaRegistry().also {
        it.add(SchemaParser.parse(StdBlockProvider::class.java.getResourceAsStream("/cringle/stdblocks/schema.json")!!.readBytes().decodeToString(), "std"))
    }

    private fun block(id: String, name: String, vararg config: Pair<String, String>) =
        BlueprintBlock(id, "cringle-std/$name", JsonObject(config.associate { (k, v) -> k to JsonPrimitive(v) }))

    private fun link(from: String, port: String, to: String, input: String) = TetherDef(TetherType.REQUEST_RESPONSE, Endpoint(from, port), Endpoint(to, input))

    private fun problems(blocks: List<BlueprintBlock>, tethers: List<TetherDef>): List<String> =
        PackageValidator.validateBlueprint(Blueprint("t", blocks, tethers), { ref -> provider.definitions.firstOrNull { "cringle-std/${it.name}" == ref } }, registry, "t").map { it.message }

    @Test
    fun anEndpointIsAnsweredWithAnHttpResponse() {
        val endpoint = block("e", "http.endpoint", "path" to "/x")
        // a text or a JSON value is not a response: it goes through http.response
        val direct = problems(listOf(endpoint, block("v", "input.text", "value" to "hello")), listOf(link("e", "handler", "v", "get")))
        assertTrue(direct.single().contains("response schema"), direct.toString())
        assertEquals(
            emptyList<String>(),
            problems(
                listOf(endpoint, block("r", "http.response"), block("v", "input.text", "value" to "hello")),
                listOf(link("e", "handler", "r", "request"), TetherDef(TetherType.PULL, Endpoint("v", "out"), Endpoint("r", "body"))),
            ),
        )
    }

    @Test
    fun aWrongConfigurationIsFoundWhenTheBlueprintIsChecked() {
        fun problems(vararg config: Pair<String, String>) = problems(listOf(block("e", "http.endpoint", *config)), emptyList())
        // the path of the endpoint, found at design time instead of when the block starts
        assertTrue(problems("path" to "test").single().contains("a path that starts with '/'"))
        assertTrue(problems("path" to "/a b").single().isNotEmpty())
        assertTrue(problems("path" to "/a//b").single().isNotEmpty())
        assertTrue(problems("path" to "/orders?x=1").single().isNotEmpty())
        assertEquals(emptyList<String>(), problems("path" to "/orders/{id}/lines"))
        assertEquals(emptyList<String>(), problems("path" to "/", "method" to "POST"))
        assertTrue(problems("path" to "/x", "method" to "FETCH").single().contains("unknown enum value"))
        assertTrue(problems("path" to "/x", "contentType" to "csv").single().contains("media type"))
        val response = problems(listOf(block("r", "http.response", "contentType" to "text")), emptyList())
        assertTrue(response.single().contains("media type"), response.toString())
        val field = problems(listOf(block("f", "http.request-field", "field" to "params.")), emptyList())
        assertTrue(field.single().contains("params.<name>"), field.toString())
    }

    @Test
    fun theTypedPortsOfTheResponseCanBeAskedAndTheSlotsOfAFormatFollowItsNames() {
        fun pull(from: String, port: String, to: String, input: String) = TetherDef(TetherType.PULL, Endpoint(from, port), Endpoint(to, input))
        val status = BlueprintBlock("n", "cringle-std/input.number", JsonObject(mapOf("value" to JsonPrimitive(1.0))))
        // a Double value cannot be the status (an Int port)
        val wrong = problems(listOf(block("r", "http.response"), status), listOf(pull("n", "out", "r", "status")))
        assertTrue(wrong.single().contains("not assignable"), wrong.toString())
        // a pull needs ports that support it: a timer cannot be asked
        val timer = problems(listOf(block("r", "http.response"), BlueprintBlock("t", "cringle-std/time.timer-trigger", JsonObject(mapOf("intervalMs" to JsonPrimitive(10))))), listOf(pull("t", "tick", "r", "body")))
        assertTrue(timer.any { it.contains("does not support PULL") }, timer.toString())
        // the number of the slots is the number of the names
        val format = BlueprintBlock(
            "f", "cringle-std/text.format",
            JsonObject(mapOf("template" to JsonPrimitive("{a}"), "variables" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))),
            varArgCounts = mapOf("args" to 3),
        )
        val slots = problems(listOf(format), emptyList())
        assertTrue(slots.single().contains("3 slots but 'variables' names 2"), slots.toString())
        assertEquals(emptyList<String>(), problems(listOf(format.copy(varArgCounts = mapOf("args" to 2))), emptyList()))
    }

    @Test
    fun theFieldOfTheRequestIsTypedByItsTypeParameter() {
        val chain = listOf(block("e", "http.endpoint", "path" to "/x/{id}"), block("f", "http.request-field", "field" to "params.id"), block("r", "http.response"))
        val links = listOf(link("e", "handler", "f", "request"), link("f", "next", "r", "request"))
        assertEquals(emptyList<String>(), problems(chain, links))
        // the block behind `next` has to answer an HttpResponse too
        val bad = problems(chain.dropLast(1) + block("v", "input.text", "value" to "x"), listOf(links[0], link("f", "next", "v", "get")))
        assertTrue(bad.single().contains("response schema"), bad.toString())
    }
}
