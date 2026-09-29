// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import cringle.contract.IsolationLevel
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ManifestJsonTest {
    private fun bad(text: String, parse: (String) -> Any = ManifestJson::parsePlugin): PackageFormatException =
        assertThrows { parse(text) }

    private fun pluginWith(fragment: String) = """{"format":1,"kind":"plugin","name":"p","version":"1.0.0"$fragment}"""

    @Test
    fun specPluginParses() {
        val p = Fixtures.plugin
        assertEquals("acme-orders", p.name)
        assertEquals(mapOf("cringle-core" to "^1.0.0"), p.dependencies)
        assertEquals(3, p.blocks.size)
        val sink = p.blocks[1]
        assertEquals(SchemaRef("acme.orders", "SinkConfig"), sink.configSchema)
        assertEquals(listOf("logging"), sink.requiredDrivers)
        assertTrue(sink.ports[1].varArg)
        assertEquals(PortDirection.IN, sink.ports[0].direction)
        assertEquals(setOf(TetherType.MESSAGE, TetherType.STREAM), p.blocks[0].ports[0].tetherTypes)
        assertEquals(ProcessorSet("com.acme.orders.Migrate", "com.acme.orders.Rollback"), p.processors)
    }

    @Test
    fun specProjectAndBlueprintParse() {
        assertEquals(FabricConfig("main", 2, listOf("edge"), mapOf("zone" to "a")), Fixtures.project.fabrics.single())
        val sink = Fixtures.main.blocks[1]
        assertEquals(IsolationLevel.PROCESS, sink.isolation)
        assertEquals(mapOf("replicas" to 2), sink.varArgCounts)
        assertEquals(IsolationLevel.SHARED, Fixtures.main.blocks[0].isolation)
        assertEquals(1, Fixtures.main.tethers[1].to.index)
        assertEquals(null, Fixtures.main.tethers[0].to.index)
    }

    @Test
    fun roundTrips() {
        assertEquals(Fixtures.plugin, ManifestJson.parsePlugin(ManifestJson.encode(Fixtures.plugin)))
        assertEquals(Fixtures.project, ManifestJson.parseProject(ManifestJson.encode(Fixtures.project)))
        assertEquals(Fixtures.main, ManifestJson.parseBlueprint(ManifestJson.encode(Fixtures.main), "b"))
    }

    @Test
    fun minimalManifestsUseDefaults() {
        val p = ManifestJson.parsePlugin(pluginWith(""))
        assertEquals(PluginManifest("p", "1.0.0"), p)
        val project = ManifestJson.parseProject("""{"format":1,"kind":"project","name":"x","version":"0.0.1"}""")
        assertEquals(ProjectManifest("x", "0.0.1"), project)
    }

    @Test
    fun rejectsMalformedAndDuplicateKeys() {
        assertTrue(bad("{").message!!.contains("malformed JSON"))
        val e = bad("""{"format":1,"format":1,"kind":"plugin","name":"p","version":"1.0.0"}""")
        assertTrue(e.message!!.contains("duplicate key 'format'"), e.message)
        assertTrue(bad("[]").message!!.contains("JSON object"))
    }

    @Test
    fun rejectsUnknownKeyWithPath() {
        val e = bad(pluginWith(""","engine":"e1""""))
        assertEquals("cringle-plugin.json.engine", e.path)
    }

    @Test
    fun rejectsWrongFormatOrKind() {
        assertTrue(bad("""{"format":2,"kind":"plugin","name":"p","version":"1.0.0"}""").message!!.contains("unsupported format 2"))
        assertTrue(bad("""{"kind":"plugin","name":"p","version":"1.0.0"}""").message!!.contains("missing key 'format'"))
        assertTrue(bad("""{"format":1,"kind":"project","name":"p","version":"1.0.0"}""").message!!.contains("must be 'plugin'"))
    }

    @Test
    fun rejectsBadNamesAndVersions() {
        for (name in listOf("Upper", "1abc", "a_b", "a..b", "-a", "")) {
            val e = bad("""{"format":1,"kind":"plugin","name":"$name","version":"1.0.0"}""")
            assertTrue(e.message!!.contains("invalid name"), "$name: ${e.message}")
        }
        for (v in listOf("1", "1.0", "01.0.0", "1.0.0.0", "v1.0.0", "1.0.0-")) {
            val e = bad("""{"format":1,"kind":"plugin","name":"p","version":"$v"}""")
            assertTrue(e.message!!.contains("invalid version"), "$v: ${e.message}")
        }
        ManifestJson.parsePlugin("""{"format":1,"kind":"plugin","name":"p","version":"1.0.0-rc.1"}""")
    }

    @Test
    fun rejectsBadDependencies() {
        assertTrue(bad(pluginWith(""","dependencies":{"Bad":"1"}""")).message!!.contains("invalid name"))
        assertTrue(bad(pluginWith(""","dependencies":{"a":" "}""")).message!!.contains("must not be blank"))
        assertTrue(bad(pluginWith(""","dependencies":{"a":1}""")).message!!.contains("must be a string"))
    }

    @Test
    fun rejectsBadFabric() {
        val head = """{"format":1,"kind":"project","name":"x","version":"1.0.0","fabrics":"""
        val e = bad("""$head[{"blueprint":"b","instances":0}]}""", ManifestJson::parseProject)
        assertEquals("$.fabrics[0].instances", e.path)
        assertTrue(bad("""$head[{"blueprint":"b","engine":"e1"}]}""", ManifestJson::parseProject).path.endsWith("engine"))
        assertTrue(bad("""$head[{"instances":1}]}""", ManifestJson::parseProject).message!!.contains("missing key 'blueprint'"))
    }

    @Test
    fun rejectsBadBlueprints() {
        fun blueprint(body: String) = bad("""{"name":"m",$body}""") { ManifestJson.parseBlueprint(it, "f.json") }
        assertTrue(blueprint(""""blocks":[{"id":"a","block":"p/b","isolation":"DOCKER"}]""").message!!.contains("unknown value 'DOCKER'"))
        assertTrue(blueprint(""""blocks":[{"id":"a","block":"p/b","engine":"x"}]""").message!!.contains("unknown key 'engine'"))
        assertTrue(blueprint(""""blocks":[{"id":"a","block":"p/b","varArgCounts":{"x":-1}}]""").message!!.contains("non-negative"))
        assertTrue(blueprint(""""tethers":[{"type":"PIPE","from":{"block":"a","port":"p"},"to":{"block":"b","port":"q"}}]""").message!!.contains("unknown value 'PIPE'"))
        assertTrue(blueprint(""""tethers":[{"type":"MESSAGE","from":{"block":"a","port":"p"}}]""").message!!.contains("missing key 'to'"))
        assertTrue(blueprint(""""tethers":[{"type":"MESSAGE","from":{"block":"a","port":"p","index":-1},"to":{"block":"b","port":"q"}}]""").message!!.contains("must not be negative"))
        assertTrue(blueprint(""""blocks":{}""").message!!.contains("must be an array"))
    }

    @Test
    fun blockDefinitionErrorsCarryPaths() {
        val e = bad(pluginWith(""","blocks":[{"name":"b","ports":[{"name":"p","direction":"SIDEWAYS","tetherTypes":["MESSAGE"],"schema":"cringle.std/String"}]}]"""))
        assertEquals("$.blocks[0].ports[0].direction", e.path)
        val empty = bad(pluginWith(""","blocks":[{"name":"b","ports":[{"name":"p","direction":"IN","tetherTypes":[],"schema":"cringle.std/String"}]}]"""))
        assertTrue(empty.message!!.contains("at least one tether type"), empty.message)
        val ref = bad(pluginWith(""","blocks":[{"name":"b","schemas":["nonsense"]}]"""))
        assertEquals("$.blocks[0].schemas[0]", ref.path)
    }

    @Test
    fun tetherDeliveryPolicyDefaultsToDropAndRoundTrips() {
        val text = """{"name":"m","blocks":[],"tethers":[
            {"type":"MESSAGE","from":{"block":"a","port":"p"},"to":{"block":"b","port":"q"}},
            {"type":"MESSAGE","from":{"block":"a","port":"p2"},"to":{"block":"b","port":"q2"},"delivery":"BUFFER"}]}"""
        val blueprint = ManifestJson.parseBlueprint(text, "f.json")
        assertEquals(listOf(DeliveryPolicy.DROP, DeliveryPolicy.BUFFER), blueprint.tethers.map { it.delivery })
        val encoded = ManifestJson.encode(blueprint)
        assertEquals(1, Regex("\"delivery\"").findAll(encoded).count(), "the default is not written")
        assertEquals(blueprint, ManifestJson.parseBlueprint(encoded, "f.json"))
    }

    @Test
    fun rejectsUnknownDeliveryPolicy() {
        val e = bad("""{"name":"m","blocks":[],"tethers":[{"type":"MESSAGE","from":{"block":"a","port":"p"},"to":{"block":"b","port":"q"},"delivery":"RETRY"}]}""") {
            ManifestJson.parseBlueprint(it, "f.json")
        }
        assertTrue(e.message!!.contains("unknown value 'RETRY'"), e.message)
        assertTrue(e.path.endsWith("delivery"), e.path)
    }

    @Test
    fun tcpTetherPortRoundTrips() {
        val text = """{"name":"m","blocks":[],"tethers":[{"type":"TCP","from":{"block":"a","port":"p"},"to":{"block":"b","port":"q"},"port":9000}]}"""
        val blueprint = ManifestJson.parseBlueprint(text, "f.json")
        assertEquals(9000, blueprint.tethers.single().port)
        assertEquals(blueprint, ManifestJson.parseBlueprint(ManifestJson.encode(blueprint), "f.json"))
    }
}
