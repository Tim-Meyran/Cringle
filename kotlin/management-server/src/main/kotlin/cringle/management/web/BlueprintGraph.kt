// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.BlockDefinition
import cringle.contract.PortDefinition
import cringle.contract.PortDirection
import cringle.contract.TetherType
import cringle.packaging.Blueprint
import cringle.packaging.BlueprintBlock
import cringle.packaging.DeliveryPolicy
import cringle.packaging.Endpoint
import cringle.packaging.RecordConfig
import cringle.packaging.TetherDef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The graph of the editor is not a blueprint the editor can represent (a block the repository does not know, a VarArg port, a malformed export). */
internal class GraphException(message: String) : RuntimeException(message)

/**
 * The two directions between a [Blueprint] and the export of Drawflow (`editor.export()`), which the browser sends and loads. Only blocks and the
 * tethers between two local ports are drawn: a node is a block (`data` = `{id, block, config}`), its inputs are the `IN` ports and its outputs the `OUT` ports
 * of the definition, in the order of the definition; an edge is a tether. Options of an edge (`delivery`, `record`) travel in a separate object keyed
 * `fromBlock.port>toBlock.port`.
 */
internal object BlueprintGraph {
    private val typeOrder = listOf(TetherType.MESSAGE, TetherType.REQUEST_RESPONSE, TetherType.STREAM, TetherType.BYTE_STREAM, TetherType.TCP, TetherType.SERIAL)

    fun inputs(definition: BlockDefinition): List<PortDefinition> = definition.ports.filter { it.direction == PortDirection.IN }

    fun outputs(definition: BlockDefinition): List<PortDefinition> = definition.ports.filter { it.direction == PortDirection.OUT }

    /** The tether type for a tether from [out] to [input]: the first they both support, `null` if there is none. */
    fun commonType(out: PortDefinition, input: PortDefinition): TetherType? = typeOrder.firstOrNull { it in out.tetherTypes && it in input.tetherTypes }

    /** The key of the options of a tether. */
    fun key(from: Endpoint, to: Endpoint): String = "${from.block}.${from.port}>${to.block}.${to.port}"

    /** The content of a node: the block id, the block, and the names of its ports (Drawflow draws the ports as dots without labels). */
    fun nodeHtml(id: String, block: String, ins: List<PortDefinition>, outs: List<PortDefinition>): String =
        "<div class=\"node-title\">" + esc(id) + "</div><small>" + esc(block) + "</small><small class=\"ports\">in: " + esc(ins.joinToString(", ") { it.name }) +
            " | out: " + esc(outs.joinToString(", ") { it.name }) + "</small>"

    /** The tethers that the graph shows: both ends are local ports and the type is a plain one. The others are kept aside by the caller. */
    fun drawable(t: TetherDef): Boolean = t.from != null && t.to != null && t.remote == null && t.service == null && t.from!!.index == null && t.to!!.index == null

    /** The Drawflow export for [blueprint]; [positions] maps a block id to `[x, y]`. */
    fun toGraph(blueprint: Blueprint, positions: Map<String, Pair<Double, Double>>, definition: (String) -> BlockDefinition?): JsonObject {
        val nodes = LinkedHashMap<String, JsonElement>()
        val numbers = blueprint.blocks.mapIndexed { i, b -> b.id to (i + 1) }.toMap()
        for ((i, b) in blueprint.blocks.withIndex()) {
            val def = definition(b.block)
            val ins = def?.let(::inputs).orEmpty()
            val outs = def?.let(::outputs).orEmpty()
            val x = positions[b.id]?.first ?: (40.0 + 220 * (i % 4))
            val y = positions[b.id]?.second ?: (40.0 + 140 * (i / 4))
            nodes[(i + 1).toString()] = buildJsonObject {
                put("id", i + 1)
                put("name", b.block)
                put("data", buildJsonObject { put("id", b.id); put("block", b.block); put("config", b.config) })
                put("class", "cringle-block")
                put("html", nodeHtml(b.id, b.block, ins, outs))
                put("typenode", false)
                put(
                    "inputs",
                    JsonObject(
                        ins.indices.associate { n ->
                            "input_${n + 1}" to buildJsonObject {
                                put(
                                    "connections",
                                    JsonArray(
                                        blueprint.tethers.filter { drawable(it) && it.to!!.block == b.id && it.to!!.port == ins[n].name }.map { t ->
                                            val fromDef = blueprint.blocks.firstOrNull { it.id == t.from!!.block }?.block?.let(definition)
                                            val index = fromDef?.let(::outputs)?.indexOfFirst { it.name == t.from!!.port } ?: -1
                                            buildJsonObject { put("node", numbers.getValue(t.from!!.block).toString()); put("input", "output_${index + 1}") }
                                        },
                                    ),
                                )
                            }
                        },
                    ),
                )
                put(
                    "outputs",
                    JsonObject(
                        outs.indices.associate { n ->
                            "output_${n + 1}" to buildJsonObject {
                                put(
                                    "connections",
                                    JsonArray(
                                        blueprint.tethers.filter { drawable(it) && it.from!!.block == b.id && it.from!!.port == outs[n].name }.map { t ->
                                            val toDef = blueprint.blocks.firstOrNull { it.id == t.to!!.block }?.block?.let(definition)
                                            val index = toDef?.let(::inputs)?.indexOfFirst { it.name == t.to!!.port } ?: -1
                                            buildJsonObject { put("node", numbers.getValue(t.to!!.block).toString()); put("output", "input_${index + 1}") }
                                        },
                                    ),
                                )
                            }
                        },
                    ),
                )
                put("pos_x", x)
                put("pos_y", y)
            }
        }
        return buildJsonObject { put("drawflow", buildJsonObject { put("Home", buildJsonObject { put("data", JsonObject(nodes)) }) }) }
    }

    /** The positions in [graph], by block id. */
    fun positions(graph: JsonObject): Map<String, Pair<Double, Double>> = nodes(graph).values.associate { n ->
        n.jsonObject["data"]!!.jsonObject["id"]!!.jsonPrimitive.content to ((n.jsonObject["pos_x"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) to (n.jsonObject["pos_y"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0))
    }

    private fun nodes(graph: JsonObject): Map<String, JsonElement> =
        graph["drawflow"]?.jsonObject?.get("Home")?.jsonObject?.get("data")?.jsonObject ?: throw GraphException("the graph has no nodes element")

    /**
     * The blueprint [name] for [graph]; [options] holds the options of the edges, [kept] are the tethers and the services the graph cannot show, [assertions] those of the draft (the editor cannot edit them, but must not lose them).
     * The type of a tether is the first one its two ports share; an edge whose ports share none is an error.
     */
    fun toBlueprint(name: String, graph: JsonObject, options: JsonObject, kept: List<TetherDef>, provides: List<cringle.packaging.ProvidedService>, assertions: List<cringle.packaging.Assertion> = emptyList(), previousBlocks: List<BlueprintBlock> = emptyList(), definition: (String) -> BlockDefinition?): Blueprint {
        val nodes = nodes(graph)
        val byNumber = HashMap<String, BlueprintBlock>()
        for ((number, n) in nodes) {
            val data = n.jsonObject["data"]?.jsonObject ?: throw GraphException("node $number has no data")
            val id = data["id"]?.jsonPrimitive?.content?.trim().orEmpty()
            val block = data["block"]?.jsonPrimitive?.content.orEmpty()
            val config = data["config"] as? JsonObject ?: JsonObject(emptyMap())
            val before = previousBlocks.firstOrNull { it.id == id && it.block == block }
            byNumber[number] = BlueprintBlock(id, block, config, before?.isolation ?: cringle.contract.IsolationLevel.SHARED, before?.varArgCounts ?: emptyMap())
        }
        val tethers = ArrayList<TetherDef>()
        for ((number, n) in nodes) {
            val from = byNumber.getValue(number)
            val fromDef = definition(from.block) ?: throw GraphException("unknown block '${from.block}'")
            val outs = outputs(fromDef)
            for ((outName, out) in n.jsonObject["outputs"]?.jsonObject.orEmpty()) {
                val outPort = outs.getOrNull(outName.removePrefix("output_").toIntOrNull()?.minus(1) ?: -1) ?: throw GraphException("'${from.block}' has no output $outName")
                for (c in out.jsonObject["connections"]?.jsonArray.orEmpty()) {
                    val to = byNumber[c.jsonObject["node"]?.jsonPrimitive?.content] ?: throw GraphException("a connection leads to a node that does not exist")
                    val toDef = definition(to.block) ?: throw GraphException("unknown block '${to.block}'")
                    val inPort = inputs(toDef).getOrNull(c.jsonObject["output"]?.jsonPrimitive?.content?.removePrefix("input_")?.toIntOrNull()?.minus(1) ?: -1)
                        ?: throw GraphException("'${to.block}' has no input ${c.jsonObject["output"]}")
                    val type = commonType(outPort, inPort) ?: throw GraphException("${from.id}.${outPort.name} and ${to.id}.${inPort.name} share no tether type")
                    val a = Endpoint(from.id, outPort.name)
                    val b = Endpoint(to.id, inPort.name)
                    val o = options[key(a, b)] as? JsonObject
                    tethers += TetherDef(
                        type, a, b,
                        delivery = if (o?.get("delivery")?.jsonPrimitive?.content == "BUFFER") DeliveryPolicy.BUFFER else DeliveryPolicy.DROP,
                        record = if (o?.get("record")?.jsonPrimitive?.boolean == true) {
                            RecordConfig(o.long("recordMaxAgeMs")?.let { java.time.Duration.ofMillis(it) }, o.long("recordMaxBytes"))
                        } else {
                            null
                        },
                        bufferCapacity = o?.long("bufferCapacity")?.toInt(),
                        requestTimeout = o?.long("requestTimeoutMs")?.let { java.time.Duration.ofMillis(it) },
                        retry = (o?.get("retry") as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { retryOf(it) },
                    )
                }
            }
        }
        // the blocks in the order of the graph numbers, so that saving twice gives the same blueprint
        val blocks = byNumber.entries.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }.map { it.value }
        return Blueprint(name, blocks, tethers.sortedBy { key(it.from!!, it.to!!) } + kept, provides, assertions)
    }

    private fun JsonObject.long(key: String): Long? {
        val v = this[key] as? JsonPrimitive ?: return null
        if (v.content.isBlank()) return null
        return v.content.toLongOrNull() ?: throw GraphException("'$key' of a tether is not a whole number: ${v.content}")
    }

    private fun retryOf(o: JsonObject): cringle.packaging.RetryConfig = cringle.packaging.RetryConfig(
        maxAttempts = o.long("maxAttempts")?.toInt(),
        backoffMs = o.long("backoffMs") ?: 50L,
        backoff = o["backoff"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { name ->
            cringle.packaging.Backoff.entries.firstOrNull { it.name == name } ?: throw GraphException("unknown backoff '$name'")
        } ?: cringle.packaging.Backoff.FIXED,
        maxBackoffMs = o.long("maxBackoffMs") ?: 5000L,
    )

    /** The edge options of [blueprint] in the form that the editor sends. */
    fun options(blueprint: Blueprint): JsonObject = JsonObject(
        blueprint.tethers.filter {
            drawable(it) && (it.delivery == DeliveryPolicy.BUFFER || it.record != null || it.bufferCapacity != null || it.requestTimeout != null || it.retry != null)
        }.associate { t ->
            key(t.from!!, t.to!!) to buildJsonObject {
                put("delivery", t.delivery.name)
                put("record", JsonPrimitive(t.record != null))
                t.record?.maxAge?.let { put("recordMaxAgeMs", JsonPrimitive(it.toMillis())) }
                t.record?.maxBytes?.let { put("recordMaxBytes", JsonPrimitive(it)) }
                t.bufferCapacity?.let { put("bufferCapacity", JsonPrimitive(it)) }
                t.requestTimeout?.let { put("requestTimeoutMs", JsonPrimitive(it.toMillis())) }
                t.retry?.let { r ->
                    put(
                        "retry",
                        buildJsonObject {
                            r.maxAttempts?.let { put("maxAttempts", JsonPrimitive(it)) }
                            put("backoffMs", JsonPrimitive(r.backoffMs))
                            put("backoff", JsonPrimitive(r.backoff.name))
                            put("maxBackoffMs", JsonPrimitive(r.maxBackoffMs))
                        },
                    )
                }
            }
        },
    )
}
