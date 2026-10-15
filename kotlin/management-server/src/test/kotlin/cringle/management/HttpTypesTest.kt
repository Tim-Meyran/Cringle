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
                listOf(link("e", "handler", "r", "request"), link("r", "source", "v", "get")),
            ),
        )
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
