// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import cringle.contract.TetherType
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PackageValidatorTest {
    private val plugins = listOf(Fixtures.pluginPackage())

    private fun problems(blueprint: Blueprint): List<PackageProblem> =
        PackageValidator.validateProject(Fixtures.projectPackage(listOf(blueprint)), plugins)

    private fun edit(json: String, from: String, to: String): Blueprint {
        assertTrue(from in json, "fixture no longer contains '$from'")
        return ManifestJson.parseBlueprint(json.replace(from, to), "blueprints/main.json")
    }

    private fun single(blueprint: Blueprint): PackageProblem = problems(blueprint).single()

    @Test
    fun specExampleIsValid() {
        assertEquals(emptyList<PackageProblem>(), PackageValidator.validateProject(Fixtures.projectPackage(), plugins))
        assertEquals(emptyList<PackageProblem>(), PackageValidator.validatePlugin(Fixtures.pluginPackage()))
    }

    @Test
    fun unknownBlock() {
        val p = single(edit(Fixtures.blueprint, "acme-orders/order-source", "acme-orders/nope"))
        assertEquals("blueprints/main.json $.blocks[0].block", p.path)
        assertTrue(p.message.contains("unknown block 'acme-orders/nope'"))
    }

    @Test
    fun duplicateBlockIds() {
        val p = problems(edit(Fixtures.blueprint, "\"id\": \"sink\"", "\"id\": \"source\""))
        assertTrue(p.any { it.message == "duplicate block id 'source'" && it.path.endsWith("$.blocks[1].id") }, p.toString())
    }

    /** The finding for the id of the first block of the example blueprint, whatever the id is. */
    private fun blockIdProblem(id: String): PackageProblem = problems(
        Fixtures.main.copy(blocks = listOf(Fixtures.main.blocks[0].copy(id = id)) + Fixtures.main.blocks[1]),
    ).single { it.path.endsWith("$.blocks[0].id") }

    @Test
    fun blockIdWithParentDirectoryIsRejected() {
        assertTrue(blockIdProblem("../x").message.startsWith("invalid identifier '../x'"), "wrong message")
    }

    @Test
    fun blockIdWithSlashIsRejected() {
        assertTrue(blockIdProblem("a/b").message.startsWith("invalid identifier 'a/b'"), "wrong message")
    }

    @Test
    fun blockIdWithBackslashIsRejected() {
        assertTrue(blockIdProblem("a\\b").message.startsWith("invalid identifier 'a\\b'"), "wrong message")
    }

    @Test
    fun blockIdReservedOnWindowsIsRejected() {
        assertTrue(blockIdProblem("con").message.contains("'con' is reserved on Windows"), "wrong message")
    }

    @Test
    fun emptyBlockIdIsRejected() {
        assertTrue(blockIdProblem("").message.startsWith("invalid identifier ''"), "wrong message")
    }

    @Test
    fun tooLongBlockIdIsRejected() {
        val long = "x".repeat(65)
        assertTrue(blockIdProblem(long).message.startsWith("invalid identifier '$long'"), "wrong message")
    }

    @Test
    fun identifiersMayUseDotsDashesAndUnderscores() {
        val renamed = Fixtures.main.copy(blocks = listOf(Fixtures.main.blocks[0].copy(id = "a.b-c_1")), tethers = emptyList())
        assertEquals(emptyList<PackageProblem>(), problems(renamed))
    }

    @Test
    fun unknownBlockIdAndPortInTether() {
        val p = single(edit(Fixtures.blueprint, "\"block\": \"source\", \"port\": \"out\" }, \"to\": { \"block\": \"sink\", \"port\": \"in\"", "\"block\": \"ghost\", \"port\": \"out\" }, \"to\": { \"block\": \"sink\", \"port\": \"in\""))
        assertTrue(p.path.endsWith("$.tethers[0].from.block"))
        val q = single(edit(Fixtures.blueprint, "\"port\": \"in\" }", "\"port\": \"inn\" }"))
        assertTrue(q.path.endsWith("$.tethers[0].to.port") && q.message.contains("no port 'inn'"), q.toString())
    }

    @Test
    fun directionIsChecked() {
        val bp = Fixtures.main.copy(
            tethers = listOf(Fixtures.main.tethers[0].copy(from = Endpoint("sink", "in"), to = Endpoint("source", "out"))),
        )
        val p = problems(bp)
        assertEquals(2, p.size)
        assertTrue(p.any { it.path.endsWith("$.tethers[0].from.port") && it.message.contains("is IN but must be OUT") })
        assertTrue(p.any { it.path.endsWith("$.tethers[0].to.port") && it.message.contains("is OUT but must be IN") })
    }

    @Test
    fun tetherTypeMustBeSupportedByBothPorts() {
        val bp = Fixtures.main.copy(tethers = listOf(Fixtures.main.tethers[0].copy(type = TetherType.STREAM)))
        val p = single(bp)
        assertEquals("blueprints/main.json $.tethers[0].type", p.path)
        assertEquals("port 'in' does not support STREAM", p.message)
        val bp2 = Fixtures.main.copy(tethers = listOf(Fixtures.main.tethers[0].copy(type = TetherType.BYTE_STREAM)))
        assertEquals(2, problems(bp2).size)
    }

    @Test
    fun schemasMustBeAssignable() {
        val bp = Fixtures.main.copy(
            blocks = Fixtures.main.blocks + BlueprintBlock("text", "acme-orders/text-sink"),
            tethers = listOf(
                Fixtures.main.tethers[0].copy(to = Endpoint("text", "in")),
            ),
        )
        val p = single(bp)
        assertTrue(p.message.contains("acme.orders/Order") && p.message.contains("cringle.std/String") && p.message.contains("not assignable"), p.message)
    }

    @Test
    fun varArgRules() {
        val noCount = Fixtures.main.copy(
            blocks = Fixtures.main.blocks.map { if (it.id == "sink") it.copy(varArgCounts = emptyMap()) else it },
        )
        assertTrue(problems(noCount).any { it.message == "missing size of VarArg port 'replicas'" })
        val extra = Fixtures.main.copy(
            blocks = Fixtures.main.blocks.map { if (it.id == "sink") it.copy(varArgCounts = mapOf("replicas" to 2, "in" to 1)) else it },
        )
        assertEquals("blueprints/main.json $.blocks[1].varArgCounts.in", single(extra).path)
        val tooHigh = edit(Fixtures.blueprint, "\"index\": 1", "\"index\": 2")
        assertEquals("index 2 is outside 0 until 2", single(tooHigh).message)
        val noIndex = edit(Fixtures.blueprint, ", \"index\": 1", "")
        assertEquals("VarArg port 'replicas' needs an index", single(noIndex).message)
        val plainWithIndex = edit(Fixtures.blueprint, "\"port\": \"in\" }", "\"port\": \"in\", \"index\": 0 }")
        assertEquals("port 'in' is not a VarArg port and takes no index", single(plainWithIndex).message)
    }

    @Test
    fun configIsValidatedAgainstTheConfigSchema() {
        val wrongType = edit(Fixtures.blueprint, "\"limit\": 10", "\"limit\": \"ten\"")
        val p = single(wrongType)
        assertEquals("blueprints/main.json $.blocks[1].config.limit", p.path)
        assertTrue(p.message.contains("integer"), p.message)
        val missing = edit(Fixtures.blueprint, "\"config\": { \"limit\": 10 },", "")
        assertTrue(single(missing).message.contains("limit"), single(missing).message)
        val unexpected = edit(Fixtures.blueprint, "\"id\": \"source\", \"block\": \"acme-orders/order-source\"", "\"id\": \"source\", \"block\": \"acme-orders/order-source\", \"config\": { \"x\": 1 }")
        assertEquals("'acme-orders/order-source' takes no configuration", single(unexpected).message)
    }

    @Test
    fun fabricMustReferenceExistingBlueprint() {
        val manifest = Fixtures.project.copy(fabrics = listOf(FabricConfig("missing", 1, emptyList(), emptyMap())))
        val p = PackageValidator.validateProject(Fixtures.projectPackage(manifest = manifest), plugins).single()
        assertEquals("$.fabrics[0].blueprint", p.path)
    }

    @Test
    fun blueprintsAreDefinedWithoutAnyEngineReference() {
        // A blueprint cannot name an engine, role or label, so it cannot span engines.
        val e = org.junit.jupiter.api.assertThrows<PackageFormatException> {
            ManifestJson.parseBlueprint("""{"name":"m","engine":"e1","blocks":[]}""", "b.json")
        }
        assertTrue(e.message!!.contains("unknown key 'engine'"))
    }

    @Test
    fun pluginSchemasMustResolveAndParse() {
        val broken = PluginPackage(Fixtures.plugin, emptyMap(), emptyList())
        val p = PackageValidator.validatePlugin(broken)
        assertTrue(p.any { it.message == "schema 'acme.orders/Order' does not resolve" && it.path == "$.blocks[0].schemas[0]" }, p.toString())
        val garbage = PluginPackage(Fixtures.plugin, mapOf("schemas/orders.json" to "{"), emptyList())
        assertTrue(PackageValidator.validatePlugin(garbage).any { it.path == "acme-orders@1.2.0:schemas/orders.json" && it.message.contains("malformed JSON") })
    }

    @Test
    fun schemasOfDependenciesAreUsed() {
        val onlyBlocks = PluginPackage(Fixtures.plugin, emptyMap(), emptyList())
        assertEquals(emptyList<PackageProblem>(), PackageValidator.validatePlugin(onlyBlocks, listOf(dependencyWithSchema())))
    }

    private fun dependencyWithSchema(): PluginPackage = PluginPackage(
        ManifestJson.parsePlugin("""{"format":1,"kind":"plugin","name":"dep","version":"1.0.0"}"""),
        mapOf("schemas/orders.json" to Fixtures.ordersSchema),
        emptyList(),
    )

    @Test
    fun conflictingSchemaNamespacesAreReported() {
        val twice = PluginPackage(Fixtures.plugin, mapOf("schemas/orders.json" to Fixtures.ordersSchema), emptyList())
        val p = PackageValidator.validatePlugin(twice, listOf(dependencyWithSchema()))
        assertTrue(p.any { it.message.contains("acme.orders") }, p.toString())
    }

    @Test
    fun pluginWithBlocksNeedsProviderAndUniqueNames() {
        val noProvider = PluginPackage(Fixtures.plugin.copy(providers = emptyList()), Fixtures.pluginPackage().schemas, emptyList())
        assertEquals("$.providers", PackageValidator.validatePlugin(noProvider).single().path)
        val dup = PluginPackage(Fixtures.plugin.copy(blocks = Fixtures.plugin.blocks + Fixtures.plugin.blocks[0]), Fixtures.pluginPackage().schemas, emptyList())
        assertEquals("duplicate block 'order-source'", PackageValidator.validatePlugin(dup).single().message)
    }

    @Test
    fun problemsAreCollectedNotThrown() {
        val bp = Fixtures.main.copy(
            blocks = listOf(BlueprintBlock("a", "acme-orders/nope"), BlueprintBlock("b", "nope/nope")),
            tethers = listOf(Fixtures.main.tethers[0]),
        )
        assertTrue(problems(bp).size >= 4)
    }

    @Test
    fun tcpPortRules() {
        val notTcp = problems(Fixtures.main.copy(tethers = listOf(Fixtures.main.tethers[0].copy(port = 80))))
        assertTrue(notTcp.any { it.path.endsWith("$.tethers[0].port") && it.message.contains("only TCP tethers have a 'port'") }, notTcp.toString())
        val tcp = Fixtures.main.tethers[0].copy(type = TetherType.TCP)
        val noPort = problems(Fixtures.main.copy(tethers = listOf(tcp)))
        assertTrue(noPort.any { it.path.endsWith("$.tethers[0].port") && it.message.contains("between 1 and 65535") }, noPort.toString())
        val buffered = problems(Fixtures.main.copy(tethers = listOf(tcp.copy(port = 9000, delivery = DeliveryPolicy.BUFFER))))
        assertTrue(buffered.any { it.path.endsWith("$.tethers[0].delivery") && it.message.contains("only supports delivery DROP") }, buffered.toString())
        val outOfRange = problems(Fixtures.main.copy(tethers = listOf(tcp.copy(port = 70000))))
        assertTrue(outOfRange.any { it.path.endsWith("$.tethers[0].port") }, outOfRange.toString())
    }

    @Test
    fun perTetherOptionsAreValidated() {
        val base = Fixtures.main.tethers[0]
        fun assertProblem(bp: Blueprint, field: String, message: String) {
            val p = problems(bp)
            assertTrue(p.any { it.path.endsWith("$.tethers[0].$field") && it.message == message }, p.toString())
        }

        assertProblem(
            Fixtures.main.copy(tethers = listOf(base.copy(bufferCapacity = 0))),
            "bufferCapacity",
            "bufferCapacity must be at least 1",
        )
        assertProblem(
            Fixtures.main.copy(tethers = listOf(base.copy(requestTimeout = Duration.ZERO))),
            "requestTimeout",
            "requestTimeout must be positive",
        )
        assertProblem(
            Fixtures.main.copy(tethers = listOf(base.copy(retry = RetryConfig()))),
            "retry",
            "a 'retry' is only allowed with delivery BUFFER",
        )
        assertProblem(
            Fixtures.main.copy(tethers = listOf(base.copy(delivery = DeliveryPolicy.BUFFER, retry = RetryConfig(maxAttempts = 0)))),
            "retry.maxAttempts",
            "maxAttempts must be at least 1",
        )
        assertProblem(
            Fixtures.main.copy(tethers = listOf(base.copy(delivery = DeliveryPolicy.BUFFER, retry = RetryConfig(backoffMs = 0)))),
            "retry.backoffMs",
            "backoffMs must be positive",
        )
        assertProblem(
            Fixtures.main.copy(tethers = listOf(base.copy(serial = SerialTetherConfig("x")))),
            "serial",
            "only SERIAL tethers have a 'serial' object",
        )
        assertProblem(
            Fixtures.main.copy(tethers = listOf(base.copy(type = TetherType.SERIAL))),
            "serial",
            "a SERIAL tether needs a 'serial' object",
        )
        assertProblem(
            Fixtures.main.copy(
                tethers = listOf(base.copy(type = TetherType.SERIAL, delivery = DeliveryPolicy.BUFFER, serial = SerialTetherConfig("x"))),
            ),
            "delivery",
            "a SERIAL tether only supports delivery DROP",
        )
        assertProblem(
            Fixtures.main.copy(tethers = listOf(base.copy(type = TetherType.SERIAL, serial = SerialTetherConfig("x", dataBits = 9)))),
            "serial.dataBits",
            "dataBits must be between 5 and 8",
        )
        assertProblem(
            Fixtures.main.copy(tethers = listOf(base.copy(type = TetherType.SERIAL, serial = SerialTetherConfig("x", stopBits = 3)))),
            "serial.stopBits",
            "stopBits must be 1 or 2",
        )
        assertProblem(
            Fixtures.main.copy(tethers = listOf(base.copy(type = TetherType.SERIAL, serial = SerialTetherConfig("x", baudRate = 0)))),
            "serial.baudRate",
            "baudRate must be positive",
        )
    }

    @Test
    fun sourceValidationNeedsNoResolvedDependency() {
        // The blocks of a project are named `plugin/block`, and the plugin is only known once the dependencies are
        // resolved at deploy time. The source validation therefore has to accept a project whose blocks it cannot
        // resolve, and only report what the project says about itself.
        val project = Fixtures.projectPackage(
            blueprints = listOf(edit(Fixtures.blueprint, "acme-orders/order-source", "acme-orders/does-not-exist")),
        )
        assertEquals(emptyList<PackageProblem>(), PackageValidator.validateProjectSources(project))
        // In contrast, the deploy-time validation cannot resolve that block and says so.
        assertTrue(PackageValidator.validateProject(project, plugins).any { it.message.contains("unknown block") })
    }

    @Test
    fun sourceValidationReportsFabricAndEndpointAndPortProblems() {
        val manifest = Fixtures.project.copy(fabrics = listOf(FabricConfig("missing", 1, emptyList(), emptyMap())))
        assertEquals(
            "unknown blueprint 'missing'",
            PackageValidator.validateProjectSources(Fixtures.projectPackage(manifest = manifest)).single().message,
        )

        val ghost = edit(Fixtures.blueprint, "\"from\": { \"block\": \"source\"", "\"from\": { \"block\": \"ghost\"")
        val p = PackageValidator.validateProjectSources(Fixtures.projectPackage(listOf(ghost)))
            .single { it.path.endsWith("$.tethers[0].from.block") }
        assertEquals("blueprints/main.json $.tethers[0].from.block", p.path)
        assertEquals("unknown block id 'ghost'", p.message)

        val tcp = edit(Fixtures.blueprint, "\"type\": \"MESSAGE\", \"from\": { \"block\": \"source\", \"port\": \"out\" }, \"to\": { \"block\": \"sink\", \"port\": \"in\" }", "\"type\": \"TCP\", \"from\": { \"block\": \"source\", \"port\": \"out\" }, \"to\": { \"block\": \"sink\", \"port\": \"in\" }")
        val q = PackageValidator.validateProjectSources(Fixtures.projectPackage(listOf(tcp)))
            .single { it.path.endsWith("$.tethers[0].port") }
        assertTrue(q.message.contains("a TCP tether needs a 'port' between 1 and 65535"), q.message)

        val duplicateIds = Fixtures.main.copy(
            blocks = listOf(Fixtures.main.blocks[0], Fixtures.main.blocks[0]),
        )
        val r = PackageValidator.validateProjectSources(Fixtures.projectPackage(listOf(duplicateIds)))
            .single { it.path.endsWith("$.blocks[1].id") }
        assertEquals("duplicate block id 'source'", r.message)
    }
}
