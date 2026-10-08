// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import cringle.contract.BlockDefinition
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Provided services and abstract service dependencies of a blueprint (#170). */
class BlueprintServiceTest {
    private val plugins = listOf(Fixtures.pluginPackage())

    private fun problems(blueprint: Blueprint): List<PackageProblem> =
        PackageValidator.validateProject(Fixtures.projectPackage(listOf(blueprint)), plugins)

    private fun provider(vararg provides: ProvidedService): Blueprint = Fixtures.main.copy(provides = provides.toList())

    /** `source.out` sends to the service `orders`. */
    private fun consumer(change: (TetherDef) -> TetherDef = { it }): Blueprint = Fixtures.main.copy(
        tethers = listOf(change(TetherDef(TetherType.MESSAGE, Endpoint("source", "out"), null, service = "orders"))),
    )

    private val service = ProvidedService("orders", "sink", "in")

    @Test
    fun providedServicesAndAServiceRemoteAreWrittenAndReadBackUnchanged() {
        val bp = provider(service).copy(tethers = consumer().tethers)
        val text = ManifestJson.encode(bp)
        val back = ManifestJson.parseBlueprint(text, "f.json")
        assertEquals(listOf(service), back.provides)
        assertEquals("orders", back.tethers.single().service)
        assertNull(back.tethers.single().remote)
        assertEquals(text, ManifestJson.encode(back))
        assertTrue("\"remote\": {\n" in text && "\"service\": \"orders\"" in text, text)
        assertEquals(emptyList<PackageProblem>(), problems(bp))
    }

    @Test
    fun anOrdinaryBlueprintIsWrittenWithoutProvides() {
        assertTrue("provides" !in ManifestJson.encode(Fixtures.main))
        assertEquals(emptyList<ProvidedService>(), ManifestJson.parseBlueprint(Fixtures.blueprint, "f.json").provides)
    }

    @Test
    fun aServiceRemoteWithAnyOtherKeyIsRejected() {
        val json = """
            { "name": "main", "blocks": [], "tethers": [
              { "type": "MESSAGE", "from": { "block": "a", "port": "out" },
                "remote": { "service": "orders", "address": "10.0.0.7:7443" } } ] }
        """.trimIndent()
        val e = assertThrows<PackageFormatException> { ManifestJson.parseBlueprint(json, "f.json") }
        assertTrue("address" in e.message!!, e.message)
        val mixed = json.replace("\"address\": \"10.0.0.7:7443\"", "\"fingerprint\": \"${"ab".repeat(32)}\"")
        assertThrows<PackageFormatException> { ManifestJson.parseBlueprint(mixed, "f.json") }
    }

    @Test
    fun aTetherCannotHaveAConcreteRemoteAndAService() {
        val both = consumer { it.copy(remote = RemoteEndpoint(null, "ab".repeat(32), "shop", "sink", "in")) }
        assertTrue(problems(both).any { "not both" in it.message }, problems(both).toString())
    }

    @Test
    fun aServiceTetherHasExactlyOneLocalEndpointAndARemoteTetherType() {
        val twoEnds = consumer { it.copy(to = Endpoint("sink", "in")) }
        assertTrue(problems(twoEnds).any { "exactly one local endpoint" in it.message }, problems(twoEnds).toString())
        val tcp = consumer { it.copy(type = TetherType.TCP, port = 1234) }
        assertTrue(problems(tcp).any { "cannot have a 'remote'" in it.message }, problems(tcp).toString())
    }

    @Test
    fun theServiceNameFollowsThePackageNameGrammar() {
        val bad = consumer { it.copy(service = "Not A Name") }
        assertTrue(problems(bad).any { it.path.endsWith("$.tethers[0].remote.service") }, problems(bad).toString())
        val badProvided = problems(provider(ProvidedService("Not A Name", "sink", "in")))
        assertTrue(badProvided.any { it.path.endsWith("$.provides[0].service") }, badProvided.toString())
    }

    @Test
    fun theProvidedPortMustExistAndBeAnInPort() {
        val noBlock = problems(provider(ProvidedService("orders", "nope", "in")))
        assertTrue(noBlock.any { it.path.endsWith("$.provides[0].block") && "unknown block id 'nope'" in it.message }, noBlock.toString())
        val noPort = problems(provider(ProvidedService("orders", "sink", "nope")))
        assertTrue(noPort.any { it.path.endsWith("$.provides[0].port") && "no port 'nope'" in it.message }, noPort.toString())
        val outPort = problems(provider(ProvidedService("orders", "source", "out")))
        assertTrue(outPort.any { it.path.endsWith("$.provides[0].port") && "must be IN" in it.message }, outPort.toString())
    }

    @Test
    fun aServiceNameIsProvidedOnlyOnce() {
        val p = problems(provider(service, ProvidedService("orders", "sink", "in")))
        assertTrue(p.any { it.path.endsWith("$.provides[1].service") && "duplicate service 'orders'" in it.message }, p.toString())
    }

    @Test
    fun theServiceTypeIsReadWrittenAndChecked() {
        val typed = provider(ProvidedService("orders", "sink", "in", TetherType.MESSAGE))
        val back = ManifestJson.parseBlueprint(ManifestJson.encode(typed), "f.json")
        assertEquals(TetherType.MESSAGE, back.provides.single().type)
        assertEquals(emptyList<PackageProblem>(), problems(typed))
        val unsupported = problems(provider(ProvidedService("orders", "sink", "in", TetherType.STREAM)))
        assertTrue(unsupported.any { it.path.endsWith("$.provides[0].type") && "does not support STREAM" in it.message }, unsupported.toString())
        val local = problems(provider(ProvidedService("orders", "sink", "in", TetherType.TCP)))
        assertTrue(local.any { it.path.endsWith("$.provides[0].type") && "cannot end on another engine" in it.message }, local.toString())
    }

    @Test
    fun aPortWithSeveralRemoteTypesNeedsTheServiceType() {
        val string = SchemaRef("cringle.std", "String")
        val multi = BlockDefinition("multi", emptyList(), listOf(PortDefinition("in", PortDirection.IN, setOf(TetherType.MESSAGE, TetherType.REQUEST_RESPONSE), string)), emptyList())
        val plugin = Fixtures.pluginPackage().let { it.copy(manifest = it.manifest.copy(blocks = it.manifest.blocks + multi)) }
        fun check(vararg provides: ProvidedService): List<PackageProblem> {
            val bp = Blueprint("main", listOf(BlueprintBlock("m", "acme-orders/multi")), emptyList(), provides.toList())
            return PackageValidator.validateProject(Fixtures.projectPackage(listOf(bp)), listOf(plugin))
        }
        val missing = check(ProvidedService("orders", "m", "in"))
        assertTrue(missing.any { it.path.endsWith("$.provides[0].type") && "give the 'type'" in it.message }, missing.toString())
        assertEquals(emptyList<PackageProblem>(), check(ProvidedService("orders", "m", "in", TetherType.REQUEST_RESPONSE)))
    }
}
