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

    /** A port as a node of the graph draws it: a plain port once, a VarArg port once per slot ([index] counts from 0). */
    data class Slot(val port: PortDefinition, val index: Int?) {
        val label: String get() = if (index == null) port.name else "${port.name}[$index]"
    }

    /** The slots of the ports of [definition] in [direction], in the order of the definition; a VarArg port has [counts] slots (at least one). */
    fun slots(definition: BlockDefinition, direction: PortDirection, counts: Map<String, Int>): List<Slot> =
        definition.ports.filter { it.direction == direction }.flatMap { p ->
            if (p.varArg) (0 until (counts[p.name] ?: 1).coerceAtLeast(1)).map { Slot(p, it) } else listOf(Slot(p, null))
        }

    /** The counts of the VarArg ports of [definition]: an entry for each, from [counts] or 1. */
    fun countsFor(definition: BlockDefinition?, counts: Map<String, Int>): Map<String, Int> =
        definition?.ports?.filter { it.varArg }?.associate { it.name to (counts[it.name] ?: 1).coerceAtLeast(1) } ?: emptyMap()

    private fun endpointKey(e: Endpoint): String = "${e.block}.${e.port}" + (e.index?.let { "[$it]" } ?: "")

    /** The key of the options of a tether. */
    fun key(from: Endpoint, to: Endpoint): String = "${endpointKey(from)}>${endpointKey(to)}"

    /** The content of a node: the block id, the block, and the names of its ports (Drawflow draws the ports as dots without labels). */
    fun nodeHtml(id: String, block: String, ins: List<Slot>, outs: List<Slot>): String =
        "<div class=\"node-title\">" + esc(id) + "</div><small>" + esc(block) + "</small><small class=\"ports\">in: " + esc(ins.joinToString(", ") { it.label }) +
            " | out: " + esc(outs.joinToString(", ") { it.label }) + "</small>"

    /** True for a tether to a port on another engine or to a service: one local end and an external end (`remote` or `service`). */
    fun external(t: TetherDef): Boolean = (t.remote != null || t.service != null) && (t.from != null) != (t.to != null)

    /** The tethers that the graph shows: between two local ports, or between a local port and an external end. The others are kept aside by the caller. */
    fun drawable(t: TetherDef): Boolean = (t.from != null && t.to != null && t.remote == null && t.service == null) || external(t)

    /** The positions in [blueprint] of the drawable tethers whose ends are not a port of a known block (a block or a port that its plugin no longer has). */
    private fun unresolvedIndexes(blueprint: Blueprint, definition: (String) -> BlockDefinition?): Set<Int> {
        fun resolves(e: Endpoint, direction: PortDirection): Boolean {
            val block = blueprint.blocks.firstOrNull { it.id == e.block } ?: return false
            val def = definition(block.block) ?: return false
            return slots(def, direction, block.varArgCounts).any { it.port.name == e.port && it.index == e.index }
        }
        return blueprint.tethers.withIndex().filter { (_, t) ->
            drawable(t) && !((t.from == null || resolves(t.from!!, PortDirection.OUT)) && (t.to == null || resolves(t.to!!, PortDirection.IN)))
        }.map { it.index }.toSet()
    }

    /** The tethers that look drawable but cannot be drawn because a block or a port is unknown; the editor keeps them as they are on save. */
    fun unresolved(blueprint: Blueprint, definition: (String) -> BlockDefinition?): List<TetherDef> =
        unresolvedIndexes(blueprint, definition).sorted().map { blueprint.tethers[it] }

    /** The ids of the external nodes: `ext1`, `ext2`, ... in the order of the tethers that have an external end; the key is the position in [tethers]. */
    private fun externalIds(tethers: List<TetherDef>): Map<Int, String> {
        val ids = HashMap<Int, String>()
        var n = 0
        tethers.forEachIndexed { i, t -> if (external(t)) ids[i] = "ext${++n}" }
        return ids
    }

    private fun keyOf(t: TetherDef, externalId: String?): String =
        (t.from?.let { endpointKey(it) } ?: externalId.orEmpty()) + ">" + (t.to?.let { endpointKey(it) } ?: externalId.orEmpty())

    private fun externalData(id: String, t: TetherDef): JsonObject = buildJsonObject {
        put("kind", "external")
        put("id", id)
        put("type", t.type.name)
        put("send", t.from != null)
        val r = t.remote
        if (r != null) {
            put("mode", "remote")
            put("fingerprint", r.fingerprint)
            put("fabric", r.fabric)
            put("block", r.block)
            put("port", r.port)
            r.address?.let { put("address", it) }
            r.index?.let { put("index", it) }
        } else {
            put("mode", "service")
            put("name", t.service.orEmpty())
        }
    }

    private fun externalHtml(id: String, data: JsonObject): String {
        val what = if (data["mode"]?.jsonPrimitive?.content == "service") {
            "service " + data["name"]?.jsonPrimitive?.content.orEmpty()
        } else {
            "remote " + data["fabric"]?.jsonPrimitive?.content.orEmpty() + "/" + data["block"]?.jsonPrimitive?.content.orEmpty() + "." + data["port"]?.jsonPrimitive?.content.orEmpty()
        }
        return "<div class=\"node-title\">" + esc(id) + "</div><small>" + esc(what) + "</small>"
    }

    /** The Drawflow export for [blueprint]; [positions] maps a block id to `[x, y]`. */
    fun toGraph(blueprint: Blueprint, positions: Map<String, Pair<Double, Double>>, definition: (String) -> BlockDefinition?): JsonObject {
        val nodes = LinkedHashMap<String, JsonElement>()
        val numbers = blueprint.blocks.mapIndexed { i, b -> b.id to (i + 1) }.toMap()
        fun slotsOf(blockId: String, direction: PortDirection): List<Slot> {
            val block = blueprint.blocks.firstOrNull { it.id == blockId } ?: return emptyList()
            val def = definition(block.block) ?: return emptyList()
            return slots(def, direction, block.varArgCounts)
        }

        // an edge joins slot [fromSlot] of node [fromNode] to slot [toSlot] of node [toNode] (the numbers of Drawflow, slots from 1)
        class Edge(val fromNode: Int, val fromSlot: Int, val toNode: Int, val toSlot: Int)
        val ids = externalIds(blueprint.tethers)
        val skipped = unresolvedIndexes(blueprint, definition)
        val externals = ArrayList<Pair<String, TetherDef>>()
        val edges = ArrayList<Edge>()
        blueprint.tethers.forEachIndexed { i, t ->
            if (!drawable(t) || i in skipped) return@forEachIndexed
            fun slot(e: Endpoint, direction: PortDirection) = slotsOf(e.block, direction).indexOfFirst { it.port.name == e.port && it.index == e.index } + 1
            if (external(t)) {
                externals += ids.getValue(i) to t
                val ext = blueprint.blocks.size + externals.size
                if (t.from != null) {
                    val node = numbers[t.from!!.block] ?: return@forEachIndexed
                    edges += Edge(node, slot(t.from!!, PortDirection.OUT), ext, 1)
                } else {
                    val node = numbers[t.to!!.block] ?: return@forEachIndexed
                    edges += Edge(ext, 1, node, slot(t.to!!, PortDirection.IN))
                }
            } else {
                val a = numbers[t.from!!.block] ?: return@forEachIndexed
                val b = numbers[t.to!!.block] ?: return@forEachIndexed
                edges += Edge(a, slot(t.from!!, PortDirection.OUT), b, slot(t.to!!, PortDirection.IN))
            }
        }
        fun inputsOf(node: Int, count: Int) = JsonObject(
            (1..count).associate { k ->
                "input_$k" to buildJsonObject {
                    put("connections", JsonArray(edges.filter { it.toNode == node && it.toSlot == k }.map { e -> buildJsonObject { put("node", e.fromNode.toString()); put("input", "output_${e.fromSlot}") } }))
                }
            },
        )
        fun outputsOf(node: Int, count: Int) = JsonObject(
            (1..count).associate { k ->
                "output_$k" to buildJsonObject {
                    put("connections", JsonArray(edges.filter { it.fromNode == node && it.fromSlot == k }.map { e -> buildJsonObject { put("node", e.toNode.toString()); put("output", "input_${e.toSlot}") } }))
                }
            },
        )
        for ((i, b) in blueprint.blocks.withIndex()) {
            val def = definition(b.block)
            val ins = slotsOf(b.id, PortDirection.IN)
            val outs = slotsOf(b.id, PortDirection.OUT)
            val x = positions[b.id]?.first ?: (40.0 + 220 * (i % 4))
            val y = positions[b.id]?.second ?: (40.0 + 140 * (i / 4))
            nodes[(i + 1).toString()] = buildJsonObject {
                put("id", i + 1)
                put("name", b.block)
                put(
                    "data",
                    buildJsonObject {
                        put("id", b.id)
                        put("block", b.block)
                        put("config", b.config)
                        put("isolation", b.isolation.name)
                        put("varArgCounts", JsonObject(countsFor(def, b.varArgCounts).mapValues { JsonPrimitive(it.value) }))
                    },
                )
                put("class", "cringle-block")
                put("html", nodeHtml(b.id, b.block, ins, outs))
                put("typenode", false)
                put("inputs", inputsOf(i + 1, ins.size))
                put("outputs", outputsOf(i + 1, outs.size))
                put("pos_x", x)
                put("pos_y", y)
            }
        }
        for ((n, pair) in externals.withIndex()) {
            val (id, t) = pair
            val number = blueprint.blocks.size + n + 1
            val data = externalData(id, t)
            nodes[number.toString()] = buildJsonObject {
                put("id", number)
                put("name", "external")
                put("data", data)
                put("class", "cringle-external")
                put("html", externalHtml(id, data))
                put("typenode", false)
                put("inputs", inputsOf(number, if (t.from != null) 1 else 0))
                put("outputs", outputsOf(number, if (t.from != null) 0 else 1))
                put("pos_x", positions[id]?.first ?: (40.0 + 220 * (n % 4)))
                put("pos_y", positions[id]?.second ?: (400.0 + 140 * (n / 4)))
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

    private fun isExternalNode(node: JsonElement): Boolean = node.jsonObject["data"]?.jsonObject?.get("kind")?.jsonPrimitive?.content == "external"

    /**
     * The blueprint [name] for [graph]; [options] holds the options of the edges, [kept] are the tethers and the services the graph cannot show, [assertions] those of the draft (the editor cannot edit them, but must not lose them).
     * The type of a tether between two ports is the first one they share, an edge whose ports share none is an error; the type of a tether to an external end is the one of the external node.
     */
    fun toBlueprint(
        name: String,
        graph: JsonObject,
        options: JsonObject,
        kept: List<TetherDef>,
        provides: List<cringle.packaging.ProvidedService>,
        assertions: List<cringle.packaging.Assertion> = emptyList(),
        previousBlocks: List<BlueprintBlock> = emptyList(),
        definition: (String) -> BlockDefinition?,
    ): Blueprint {
        val nodes = nodes(graph)
        val byNumber = HashMap<String, BlueprintBlock>()
        val externals = HashMap<String, JsonObject>()
        for ((number, n) in nodes) {
            val data = n.jsonObject["data"]?.jsonObject ?: throw GraphException("node $number has no data")
            if (isExternalNode(n)) {
                externals[number] = data
                continue
            }
            val id = data["id"]?.jsonPrimitive?.content?.trim().orEmpty()
            val block = data["block"]?.jsonPrimitive?.content.orEmpty()
            val config = data["config"] as? JsonObject ?: JsonObject(emptyMap())
            val before = previousBlocks.firstOrNull { it.id == id && it.block == block }
            val isolation = data["isolation"]?.jsonPrimitive?.content?.let { name ->
                cringle.contract.IsolationLevel.entries.firstOrNull { it.name == name } ?: throw GraphException("unknown isolation '$name'")
            } ?: before?.isolation ?: cringle.contract.IsolationLevel.SHARED
            val given = (data["varArgCounts"] as? JsonObject)?.mapValues { (k, v) -> v.jsonPrimitive.content.toIntOrNull() ?: throw GraphException("the count of '$k' is not a number") }
                ?: before?.varArgCounts.orEmpty()
            byNumber[number] = BlueprintBlock(id, block, config, isolation, countsFor(definition(block), given))
        }
        fun outSlot(number: String, className: String): Slot {
            val block = byNumber.getValue(number)
            val def = definition(block.block) ?: throw GraphException("unknown block '${block.block}'")
            return slots(def, PortDirection.OUT, block.varArgCounts).getOrNull(className.removePrefix("output_").toIntOrNull()?.minus(1) ?: -1)
                ?: throw GraphException("'${block.block}' has no output $className")
        }
        fun inSlot(number: String, className: String): Slot {
            val block = byNumber.getValue(number)
            val def = definition(block.block) ?: throw GraphException("unknown block '${block.block}'")
            return slots(def, PortDirection.IN, block.varArgCounts).getOrNull(className.removePrefix("input_").toIntOrNull()?.minus(1) ?: -1)
                ?: throw GraphException("'${block.block}' has no input $className")
        }
        fun tether(type: TetherType, a: Endpoint?, b: Endpoint?, o: JsonObject?, ext: JsonObject?): TetherDef = TetherDef(
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
            remote = ext?.takeIf { it["mode"]?.jsonPrimitive?.content != "service" }?.let { remoteOf(it) },
            service = if (ext != null && ext["mode"]?.jsonPrimitive?.content == "service") ext["name"]?.jsonPrimitive?.content?.trim().orEmpty() else null,
        )
        fun externalType(ext: JsonObject, port: PortDefinition, owner: String): TetherType {
            val name = ext["type"]?.jsonPrimitive?.content.orEmpty()
            val type = TetherType.entries.firstOrNull { it.name == name } ?: throw GraphException("the external end ${ext["id"]?.jsonPrimitive?.content} has no tether type")
            if (type !in port.tetherTypes) throw GraphException("'${port.name}' of $owner does not support $type")
            return type
        }

        val built = ArrayList<Pair<String, TetherDef>>()
        for ((number, n) in nodes) {
            for ((outName, out) in n.jsonObject["outputs"]?.jsonObject.orEmpty()) {
                for (c in out.jsonObject["connections"]?.jsonArray.orEmpty()) {
                    val target = c.jsonObject["node"]?.jsonPrimitive?.content
                    val targetClass = c.jsonObject["output"]?.jsonPrimitive?.content.orEmpty()
                    if (target == null || target !in nodes) throw GraphException("a connection leads to a node that does not exist")
                    val fromExt = externals[number]
                    val toExt = externals[target]
                    when {
                        fromExt != null && toExt != null -> throw GraphException("two external ends cannot be connected to each other")
                        fromExt != null -> {
                            val slot = inSlot(target, targetClass)
                            val to = byNumber.getValue(target)
                            val b = Endpoint(to.id, slot.port.name, slot.index)
                            val id = fromExt["id"]?.jsonPrimitive?.content.orEmpty()
                            val key = id + ">" + endpointKey(b)
                            built += key to tether(externalType(fromExt, slot.port, to.id), null, b, options[key] as? JsonObject, fromExt)
                        }
                        toExt != null -> {
                            val slot = outSlot(number, outName)
                            val from = byNumber.getValue(number)
                            val a = Endpoint(from.id, slot.port.name, slot.index)
                            val id = toExt["id"]?.jsonPrimitive?.content.orEmpty()
                            val key = endpointKey(a) + ">" + id
                            built += key to tether(externalType(toExt, slot.port, from.id), a, null, options[key] as? JsonObject, toExt)
                        }
                        else -> {
                            val from = byNumber.getValue(number)
                            val to = byNumber.getValue(target)
                            val outS = outSlot(number, outName)
                            val inS = inSlot(target, targetClass)
                            val type = commonType(outS.port, inS.port) ?: throw GraphException("${from.id}.${outS.port.name} and ${to.id}.${inS.port.name} share no tether type")
                            val a = Endpoint(from.id, outS.port.name, outS.index)
                            val b = Endpoint(to.id, inS.port.name, inS.index)
                            val key = key(a, b)
                            built += key to tether(type, a, b, options[key] as? JsonObject, null)
                        }
                    }
                }
            }
        }
        // the blocks in the order of the graph numbers, so that saving twice gives the same blueprint
        val blocks = byNumber.entries.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }.map { it.value }
        return Blueprint(name, blocks, built.sortedBy { it.first }.map { it.second } + kept, provides, assertions)
    }

    private fun remoteOf(o: JsonObject): cringle.packaging.RemoteEndpoint {
        fun text(key: String) = o[key]?.jsonPrimitive?.content?.trim().orEmpty()
        val index = text("index").takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: throw GraphException("the index of a remote end is not a number: $it") }
        return cringle.packaging.RemoteEndpoint(text("address").takeIf { it.isNotEmpty() }, text("fingerprint"), text("fabric"), text("block"), text("port"), index)
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
    fun options(blueprint: Blueprint): JsonObject {
        val ids = externalIds(blueprint.tethers)
        return JsonObject(
            blueprint.tethers.withIndex().filter { (_, t) ->
                drawable(t) && (t.delivery == DeliveryPolicy.BUFFER || t.record != null || t.bufferCapacity != null || t.requestTimeout != null || t.retry != null)
            }.associate { (i, t) ->
                keyOf(t, ids[i]) to buildJsonObject {
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
}
