// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import cringle.contract.IsolationLevel
import cringle.contract.Parity
import cringle.contract.PortDirection
import cringle.contract.SchemaRef
import cringle.contract.TetherType
import cringle.packaging.Backoff
import cringle.packaging.RetryConfig
import cringle.packaging.SerialTetherConfig
import java.time.Duration
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
        assertEquals(1, Fixtures.main.tethers[1].to?.index)
        assertEquals(null, Fixtures.main.tethers[0].to?.index)
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
        val ref = bad("""$head[{"blueprint":"../b"}]}""", ManifestJson::parseProject)
        assertTrue(ref.path.endsWith("$.fabrics[0].blueprint") && ref.message!!.contains("invalid identifier"), "${ref.path} ${ref.message}")
    }

    @Test
    fun rejectsIdentifiersThatAreNoNames() {
        fun blueprint(body: String) = bad("""{"name":"m",$body}""") { ManifestJson.parseBlueprint(it, "f.json") }
        for (id in listOf("../x", "a/b", "", "x".repeat(65), ".hidden", "trailing-", "con")) {
            val e = blueprint(""""blocks":[{"id":"$id","block":"p/b"}]""")
            val message = e.message!!
            assertTrue(
                e.path.endsWith("$.blocks[0].id") &&
                    (message.contains("invalid identifier '$id'") || message.contains("reserved on Windows")),
                "$id: ${e.path} $message",
            )
        }
        val backslash = blueprint(""""blocks":[{"id":"a\\b","block":"p/b"}]""")
        assertTrue(
            backslash.path.endsWith("$.blocks[0].id") && backslash.message!!.contains("invalid identifier 'a\\b'"),
            "${backslash.path} ${backslash.message}",
        )
        val port = blueprint(""""tethers":[{"type":"MESSAGE","from":{"block":"a","port":"../p"},"to":{"block":"b","port":"q"}}]""")
        assertTrue(port.path.endsWith("$.tethers[0].from.port") && port.message!!.contains("invalid identifier"), "${port.path} ${port.message}")
        val ref = blueprint(""""tethers":[{"type":"MESSAGE","from":{"block":"../a","port":"p"},"to":{"block":"b","port":"q"}}]""")
        assertTrue(ref.path.endsWith("$.tethers[0].from.block"), ref.path)
        val named = bad("""{"name":"nul","blocks":[],"tethers":[]}""") { ManifestJson.parseBlueprint(it, "f.json") }
        assertTrue(named.message!!.contains("'nul' is reserved on Windows"), named.message)
        val definition = bad(pluginWith(""","blocks":[{"name":"b","ports":[{"name":"com1","direction":"IN","tetherTypes":["MESSAGE"],"schema":"cringle.std/String"}]}]"""))
        assertTrue(definition.path.endsWith("$.blocks[0].ports[0].name"), definition.path)
    }

    @Test
    fun rejectsBadBlueprints() {
        fun blueprint(body: String) = bad("""{"name":"m",$body}""") { ManifestJson.parseBlueprint(it, "f.json") }
        assertTrue(blueprint(""""blocks":[{"id":"a","block":"p/b","isolation":"DOCKER"}]""").message!!.contains("unknown value 'DOCKER'"))
        assertTrue(blueprint(""""blocks":[{"id":"a","block":"p/b","engine":"x"}]""").message!!.contains("unknown key 'engine'"))
        assertTrue(blueprint(""""blocks":[{"id":"a","block":"p/b","varArgCounts":{"x":-1}}]""").message!!.contains("non-negative"))
        assertTrue(blueprint(""""tethers":[{"type":"PIPE","from":{"block":"a","port":"p"},"to":{"block":"b","port":"q"}}]""").message!!.contains("unknown value 'PIPE'"))
        // a missing endpoint is no format error any more: with a `remote` it is valid, without it the PackageValidator rejects it (#145)
        assertEquals(null, ManifestJson.parseBlueprint("""{"name":"m","tethers":[{"type":"MESSAGE","from":{"block":"a","port":"p"}}]}""", "f.json").tethers.single().to)
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

    @Test
    fun perTetherOptionsRoundTrip() {
        val text = """{"name":"m","blocks":[],"tethers":[
            {"type":"MESSAGE","from":{"block":"a","port":"p"},"to":{"block":"b","port":"q"},"delivery":"BUFFER","bufferCapacity":8,"requestTimeout":250,"retry":{"maxAttempts":3,"backoffMs":10,"backoff":"EXPONENTIAL","maxBackoffMs":100}},
            {"type":"SERIAL","from":{"block":"a","port":"p2"},"to":{"block":"b","port":"q2"},"serial":{"device":"/dev/ttyUSB0","baudRate":115200,"dataBits":7,"parity":"EVEN","stopBits":2}}]}"""
        val blueprint = ManifestJson.parseBlueprint(text, "f.json")
        val first = blueprint.tethers[0]
        assertEquals(8, first.bufferCapacity)
        assertEquals(Duration.ofMillis(250), first.requestTimeout)
        assertEquals(RetryConfig(3, 10, Backoff.EXPONENTIAL, 100), first.retry)
        val second = blueprint.tethers[1]
        assertEquals(SerialTetherConfig("/dev/ttyUSB0", 115200, 7, Parity.EVEN, 2), second.serial)
        assertEquals(blueprint, ManifestJson.parseBlueprint(ManifestJson.encode(blueprint), "f.json"))
    }

    @Test
    fun rejectsBadPerTetherOptions() {
        fun blueprint(body: String) = bad("""{"name":"m",$body}""") { ManifestJson.parseBlueprint(it, "f.json") }
        assertTrue(blueprint(""""tethers":[{"type":"MESSAGE","from":{"block":"a","port":"p"},"to":{"block":"b","port":"q"},"retry":{"backoff":"SIDEWAYS"}}]""").message!!.contains("unknown value 'SIDEWAYS'"))
        assertTrue(blueprint(""""tethers":[{"type":"SERIAL","from":{"block":"a","port":"p"},"to":{"block":"b","port":"q"},"serial":{"baudRate":9600}}]""").message!!.contains("missing key 'device'"))
        assertTrue(blueprint(""""tethers":[{"type":"SERIAL","from":{"block":"a","port":"p"},"to":{"block":"b","port":"q"},"serial":{"device":"x","parity":"MAYBE"}}]""").message!!.contains("unknown value 'MAYBE'"))
        assertTrue(blueprint(""""tethers":[{"type":"MESSAGE","from":{"block":"a","port":"p"},"to":{"block":"b","port":"q"},"bufferCapacity":"big"}]""").message!!.contains("must be an integer"))
        assertTrue(blueprint(""""tethers":[{"type":"MESSAGE","from":{"block":"a","port":"p"},"to":{"block":"b","port":"q"},"retry":{"nope":1}}]""").message!!.contains("unknown key 'nope'"))
    }

    private val remoteBlueprint = """
        {
          "name": "m",
          "blocks": [ { "id": "a", "block": "p/b" } ],
          "tethers": [
            { "type": "MESSAGE", "from": { "block": "a", "port": "out" },
              "remote": { "address": "10.0.0.7:7443", "fingerprint": "${"ab".repeat(32)}", "fabric": "shop", "block": "sink", "port": "in", "index": 1 } },
            { "type": "STREAM", "to": { "block": "a", "port": "in", "index": 0 }, "delivery": "BUFFER",
              "remote": { "address": "[::1]:7444", "fingerprint": "${"01".repeat(32)}", "fabric": "shop", "block": "source", "port": "out" } }
          ]
        }
    """.trimIndent()

    @Test
    fun remoteTetherIsReadAndWrittenBackUnchanged() {
        val blueprint = ManifestJson.parseBlueprint(remoteBlueprint, "f.json")
        val receives = blueprint.tethers[0]
        assertEquals(Endpoint("a", "out"), receives.from)
        assertEquals(null, receives.to)
        assertEquals(RemoteEndpoint("10.0.0.7:7443", "ab".repeat(32), "shop", "sink", "in", 1), receives.remote)
        val sends = blueprint.tethers[1]
        assertEquals(null, sends.from)
        assertEquals(Endpoint("a", "in", 0), sends.to)
        assertEquals(RemoteEndpoint("[::1]:7444", "01".repeat(32), "shop", "source", "out"), sends.remote)
        assertEquals(DeliveryPolicy.BUFFER, sends.delivery)

        val written = ManifestJson.encode(blueprint)
        assertEquals(blueprint, ManifestJson.parseBlueprint(written, "f.json"))
        assertEquals(written, ManifestJson.encode(ManifestJson.parseBlueprint(written, "f.json")))
        assertTrue("\"from\"" !in written.substringAfter("\"STREAM\""), written)
        assertTrue("\"remote\"" in written)
    }

    @Test
    fun remoteTetherWithoutAddressIsReadAndWrittenBackWithoutOne() {
        val text = """
            {"name":"m","tethers":[{"type":"MESSAGE","from":{"block":"a","port":"out"},
              "remote":{"fingerprint":"${"ab".repeat(32)}","fabric":"shop","block":"sink","port":"in"}}]}
        """.trimIndent()
        val remote = ManifestJson.parseBlueprint(text, "f.json").tethers.single().remote
        assertEquals(null, remote?.address)
        assertEquals("shop", remote?.fabric)
        val written = ManifestJson.encode(ManifestJson.parseBlueprint(text, "f.json"))
        assertTrue("\"address\"" !in written, written)
        assertEquals(remote, ManifestJson.parseBlueprint(written, "f.json").tethers.single().remote)
    }

    @Test
    fun localTetherWritesNoRemote() {
        assertTrue("remote" !in ManifestJson.encode(Fixtures.main))
        assertEquals(null, Fixtures.main.tethers[0].remote)
    }

    @Test
    fun rejectsBadRemoteObjects() {
        fun remote(body: String) = bad("""{"name":"m","tethers":[{"type":"MESSAGE","from":{"block":"a","port":"p"},"remote":$body}]}""") { ManifestJson.parseBlueprint(it, "f.json") }
        val ok = """"address":"h:1","fingerprint":"x","fabric":"f","block":"b","port":"p""""
        // the address is optional (#148); the other keys are not
        assertTrue(remote("""{"address":"h:1","fabric":"f","block":"b","port":"p"}""").message!!.contains("missing key 'fingerprint'"))
        assertTrue(remote("""{$ok,"nope":1}""").message!!.contains("unknown key 'nope'"))
        assertTrue(remote("""{$ok,"index":-1}""").path.endsWith("remote.index"))
        assertTrue(remote("""[]""").path.endsWith("remote"))
    }

    @Test
    fun projectProcessorsRoundTrip() {
        val text = """{"format":1,"kind":"project","name":"x","version":"1.0.0","processors":{"update":"com.acme.Up","downgrade":"com.acme.Down"}}"""
        val project = ManifestJson.parseProject(text)
        assertEquals(ProcessorSet("com.acme.Up", "com.acme.Down"), project.processors)
        assertEquals(project, ManifestJson.parseProject(ManifestJson.encode(project)))
    }

    @Test
    fun invalidProcessorClassNameIsRejected() {
        val text = """{"format":1,"kind":"project","name":"x","version":"1.0.0","processors":{"update":"../evil"}}"""
        assertTrue(bad(text, ManifestJson::parseProject).message!!.contains("processors"))
    }
}
