// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.packaging.RemoteEndpoint
import cringle.wire.TetherMode
import cringle.wire.WireFrame
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a [TetherNetwork] needs from the engine to run the tethers that end on another engine (`remote` in the blueprint,
 * `spec/tether.md`, "Tethers between engines"). It is bound to one fabric. Implemented by [RemoteTetherDriver].
 */
public interface RemoteTetherPorts {
    /**
     * Lets the callers named in [receivers] deliver to this fabric until the returned handle is closed. Called when the
     * fabric is deployed; the handle is closed when the fabric is removed.
     */
    public fun register(receivers: List<RemoteReceiver>): AutoCloseable

    /**
     * Opens the call of a tether whose local end sends. Returns when the other engine has accepted the call; throws
     * [TetherWiringException] if it is not reachable, does not have the expected key or refuses the call.
     */
    public suspend fun connect(sender: RemoteSender): RemoteCall
}

/** The receiving end of a tether that ends on another engine: who may deliver where, and what happens with what arrives. */
public class RemoteReceiver(
    /** Id of the tether in the network, for messages. */
    public val tetherId: String,
    /** Block, port and VarArg index of the local port the traffic is delivered to. */
    public val block: String,
    public val port: String,
    public val index: Int?,
    /** Public key fingerprint of the engine that may deliver (`remote.fingerprint` of the blueprint). */
    public val senderFingerprint: String,
    /** The wire mode of the tether: typed frames, or bytes. */
    public val mode: TetherMode,
    /** Called when a call of the allowed engine has been accepted; returns who takes the frames of that call. */
    public val accept: (RemoteSession) -> RemoteInbound,
)

/** The receiving end of one accepted call. */
public interface RemoteSession {
    /** Sends [frame] to the sender of the call; suspends while the connection cannot take more. */
    public suspend fun reply(frame: WireFrame)

    /** Ends the call after the frames sent so far; the sender then sees the end of its call. */
    public fun end()
}

/** Takes the frames of one call. */
public interface RemoteInbound {
    /**
     * Takes one frame, in the order the frames were sent. May suspend, which holds back the call (flow control). A
     * [TetherValidationException] or [TetherDeliveryException] is answered to the other side as an `ERROR` frame with the
     * correlation or stream ID of [frame].
     */
    public suspend fun onFrame(frame: WireFrame)

    /** The call has ended: by the other engine, by [RemoteSession.end] or because the connection was lost ([cause]). */
    public fun onEnded(cause: Throwable?)
}

/** The sending end of a tether that ends on another engine. */
public class RemoteSender(
    /** Id of the tether in the network, for messages. */
    public val tetherId: String,
    /** Where the receiving end is. */
    public val remote: RemoteEndpoint,
    /** Namespace of the schema of the local port, for the typed frames the sender writes. */
    public val schemaNamespace: String,
    /** The wire mode of the tether: typed frames, or bytes. */
    public val mode: TetherMode,
    /** Takes what the other engine sends back (responses, stream items, errors); [RemoteInbound.onEnded] is not called after [RemoteCall.close]. */
    public val inbound: RemoteInbound,
)

/** An open call of a sending tether. */
public interface RemoteCall : AutoCloseable {
    /** Sends [frame]; suspends while the connection cannot take more, and fails once the call has ended. */
    public suspend fun send(frame: WireFrame)

    /** Ends the call. */
    override fun close()
}

/** The `ERROR` frames of the tethers between engines: codes, the value of the frame and the exceptions it stands for. */
internal object RemoteErrors {
    const val UNKNOWN_TARGET = "unknown-target"
    const val VALIDATION = "validation"
    const val DELIVERY = "delivery"
    const val TIMEOUT = "timeout"
    const val STOPPING = "stopping"
    const val UNSUPPORTED = "unsupported"
    const val FORMAT = "format"

    /** The value (`cringle.std/Error`) for an error with [code] and [message]; [problems] go to `details`. */
    fun value(code: String, message: String, problems: List<String> = emptyList()): JsonObject = JsonObject(
        buildMap {
            put("code", JsonPrimitive(code))
            put("message", JsonPrimitive(message))
            if (problems.isNotEmpty()) put("details", JsonObject(problems.withIndex().associate { (i, p) -> "problem$i" to JsonPrimitive(p) }))
        },
    )

    /** The value for [e], as a receiver answers a frame it could not take. */
    fun value(e: Throwable): JsonObject = when (e) {
        is TetherValidationException -> value(VALIDATION, e.message.orEmpty(), e.problems)
        is TetherTimeoutException -> value(TIMEOUT, e.message.orEmpty())
        else -> value(DELIVERY, e.message.orEmpty())
    }

    fun code(value: JsonElement): String = (value as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull.orEmpty()

    fun message(value: JsonElement): String = (value as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()

    /** The exception that the sender of the failed frame gets for the error [value] it received. */
    fun exception(value: JsonElement): Throwable {
        val message = message(value)
        val problems = ((value as? JsonObject)?.get("details") as? JsonObject)?.values?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        return when (code(value)) {
            VALIDATION -> TetherValidationException(message, problems)
            TIMEOUT -> TetherTimeoutException(message)
            else -> TetherDeliveryException(message)
        }
    }
}
