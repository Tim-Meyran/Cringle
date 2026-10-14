// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.management.ManagementException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/** What the MCP server of the ManagementServer offers (`--mcp`). */
public enum class McpMode {
    /** No `/mcp` endpoint. */
    OFF,

    /** Only the tools that read. */
    READ_ONLY,

    /** All tools. */
    ON,
    ;

    public companion object {
        /** The mode for the text of `--mcp` (`on`, `read-only`, `off`); throws [IllegalArgumentException] for anything else. */
        public fun parse(text: String): McpMode = when (text.trim().lowercase()) {
            "on" -> ON
            "read-only", "readonly" -> READ_ONLY
            "off" -> OFF
            else -> throw IllegalArgumentException("--mcp is on, read-only or off, not '$text'")
        }
    }
}

/**
 * The MCP server (Model Context Protocol, revision 2025-03-26) of the ManagementServer: JSON-RPC 2.0 over *Streamable HTTP*, one `POST` per message, the answer is
 * the JSON of the response (there are no server-initiated messages, so no event stream). It offers tools only ([McpTools]); the user is the one of the bearer
 * token, and every tool checks the permission for the object it touches. See `docs/mcp.md`.
 */
internal class McpServer(private val tools: McpTools, private val mode: McpMode, private val version: String) {
    private val log = LoggerFactory.getLogger("cringle.management.mcp")
    private val json = Json { encodeDefaults = true }

    private fun visible(): List<McpTool> = tools.all.filter { mode == McpMode.ON || !it.write }

    /** Answers the JSON-RPC message in [body] for [session]. */
    suspend fun handle(body: ByteArray, session: Session): WebResponse {
        val message = try {
            json.parseToJsonElement(body.decodeToString())
        } catch (e: SerializationException) {
            return reply(error(JsonNull, -32700, "parse error: the body is not JSON"))
        }
        if (message is JsonArray) {
            // a batch: the answers of the requests (notifications have none)
            val answers = message.mapNotNull { one -> if (one is JsonObject) call(one, session) else error(JsonNull, -32600, "invalid request") }
            return if (answers.isEmpty()) WebResponse(202, ByteArray(0)) else reply(JsonArray(answers))
        }
        val request = message as? JsonObject ?: return reply(error(JsonNull, -32600, "invalid request"))
        val answer = call(request, session) ?: return WebResponse(202, ByteArray(0))
        return reply(answer)
    }

    private fun reply(element: JsonElement) = WebResponse(200, json.encodeToString(JsonElement.serializer(), element).toByteArray(), "application/json")

    private fun error(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject { put("code", code); put("message", message) })
    }

    private fun result(id: JsonElement, value: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", value)
    }

    /** One request or notification; `null` for a notification (and a response from the client), which has no answer. */
    private suspend fun call(request: JsonObject, session: Session): JsonObject? {
        val id = request["id"]
        val method = (request["method"] as? JsonPrimitive)?.contentOrNull
        if (method == null) return if (id != null && (request.containsKey("result") || request.containsKey("error"))) null else error(id ?: JsonNull, -32600, "invalid request")
        if (id == null) return null
        val params = (request["params"] as? JsonObject) ?: JsonObject(emptyMap())
        return try {
            when (method) {
                "initialize" -> result(id, initialize(params))
                "ping" -> result(id, JsonObject(emptyMap()))
                "tools/list" -> result(id, buildJsonObject { put("tools", buildJsonArray { visible().filter { it.allowed(session) }.forEach { add(it.describe()) } }) })
                "tools/call" -> result(id, callTool(params, session))
                else -> error(id, -32601, "method not found: $method")
            }
        } catch (e: IllegalArgumentException) {
            error(id, -32602, e.message ?: "invalid params")
        }
    }

    private fun initialize(params: JsonObject): JsonElement {
        // the version of the client if this server speaks it, else the latest one it knows
        val asked = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        val protocol = if (asked in SUPPORTED) asked!! else SUPPORTED.first()
        return buildJsonObject {
            put("protocolVersion", protocol)
            put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", false) }) })
            put("serverInfo", buildJsonObject { put("name", "cringle-management-server"); put("version", version) })
            put(
                "instructions",
                "Cringle: applications are built from blocks that exchange messages over tethers. A blueprint describes blocks and tethers, a project packages blueprints, " +
                    "engines run them as fabrics on machines. Read with list_*/get_*/query_logs; change with the other tools. Every tool checks the rights of your token.",
            )
        }
    }

    private suspend fun callTool(params: JsonObject, session: Session): JsonElement {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull ?: throw IllegalArgumentException("tools/call needs a name")
        val tool = visible().firstOrNull { it.name == name } ?: throw IllegalArgumentException("unknown tool '$name'")
        if (!tool.allowed(session)) return toolResult(JsonPrimitive("PERMISSION_DENIED: the tool '$name' needs the role ${tool.permission.name.lowercase()}"), true)
        val arguments = (params["arguments"] as? JsonObject) ?: JsonObject(emptyMap())
        return try {
            toolResult(tool.run(session, McpArgs(arguments)), false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            toolResult(JsonPrimitive(e.message ?: "invalid arguments"), true)
        } catch (e: Exception) {
            if (e !is ManagementException && e !is DraftException && e !is DraftConflictException && e !is io.grpc.StatusException) log.warn("tool {} failed", name, e)
            toolResult(JsonPrimitive(describe(e)), true)
        }
    }

    private fun toolResult(value: JsonElement, isError: Boolean): JsonElement = buildJsonObject {
        put(
            "content",
            buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", if (value is JsonPrimitive && value.isString) value.content else json.encodeToString(JsonElement.serializer(), value)) })
            },
        )
        put("isError", isError)
    }

    internal companion object {
        /** The protocol revisions this server speaks, the newest first. */
        val SUPPORTED = listOf("2025-03-26", "2024-11-05")
    }
}
