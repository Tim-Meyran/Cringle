// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.packaging.RemoteEndpoint
import kotlinx.serialization.json.JsonElement

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

/** The receiving end of a tether that ends on another engine: who may deliver where. */
public class RemoteReceiver(
    /** Id of the tether in the network, for messages. */
    public val tetherId: String,
    /** Block, port and VarArg index of the local port the messages are delivered to. */
    public val block: String,
    public val port: String,
    public val index: Int?,
    /** Public key fingerprint of the engine that may deliver (`remote.fingerprint` of the blueprint). */
    public val senderFingerprint: String,
    /**
     * Takes one value from the wire. Throws [TetherValidationException] or [TetherDeliveryException] if the value cannot
     * be taken; both are answered to the sender as an ERROR frame.
     */
    public val deliver: suspend (JsonElement) -> Unit,
)

/** The sending end of a tether that ends on another engine. */
public class RemoteSender(
    /** Id of the tether in the network, for messages. */
    public val tetherId: String,
    /** Where the receiving end is. */
    public val remote: RemoteEndpoint,
    /** Namespace of the schema of the local port, written into every frame. */
    public val schemaNamespace: String,
    /** The other engine could not take a value; the exception is a [TetherValidationException] or [TetherDeliveryException]. */
    public val onError: (Throwable) -> Unit,
    /** The call ended (the connection was lost or the other engine ended the call); not called after [RemoteCall.close]. */
    public val onClosed: (Throwable?) -> Unit,
)

/** An open call of a sending tether. */
public interface RemoteCall : AutoCloseable {
    /** Sends [value] as a `MESSAGE` frame; suspends while the connection cannot take more, and fails once the call has ended. */
    public suspend fun send(value: JsonElement)

    /** Ends the call. */
    override fun close()
}
