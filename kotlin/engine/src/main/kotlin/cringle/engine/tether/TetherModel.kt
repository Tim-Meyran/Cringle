// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import cringle.contract.SerialDriver
import cringle.contract.TcpDriver
import cringle.contract.TetherType
import cringle.packaging.Endpoint
import cringle.packaging.RemoteEndpoint
import cringle.schema.SchemaRegistry
import java.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Thrown when the tethers of a blueprint cannot be wired; the message lists every problem. */
public class TetherWiringException(message: String) : RuntimeException(message)

/** Thrown when a value does not match the schema of its port. [problems] lists the violations. */
public class TetherValidationException(message: String, public val problems: List<String>) : RuntimeException(message)

/** Thrown when a request gets no response within the configured time. */
public class TetherTimeoutException(message: String) : RuntimeException(message)

/** Thrown when the receiving block cannot take a message (for example because it is not running). */
public class TetherDeliveryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Static description of one tether of a blueprint. */
public data class TetherInfo(
    val id: String,
    val type: TetherType,
    val from: Endpoint,
    val to: Endpoint,
    /**
     * Set for a tether that ends on another engine: the end that is not local ([from] or [to]) then names the port on the
     * other engine by the ids of this object.
     */
    val remote: RemoteEndpoint? = null,
    /** The recording definition of the tether in the blueprint (#193), or `null`. */
    val record: cringle.packaging.RecordConfig? = null,
)

/** What kind of traffic a hook sees. */
public enum class TrafficKind { MESSAGE, REQUEST, RESPONSE, STREAM_OPENED, STREAM_ITEM, BYTES }

/**
 * Hook point for recording (DWH recording mode, #17): called for every value that crosses a tether, after schema
 * validation. Must not block. Interface only; the engine ships no implementation yet.
 */
public fun interface TetherObserver {
    /** Called for one value of [kind] on [tether]. */
    public fun observe(tether: TetherInfo, kind: TrafficKind, payload: Any?)
}

/**
 * Hook point for breakpoints: called before a value is handed to the receiver and may suspend to hold it back.
 * Interface only; the engine ships no implementation yet.
 */
public fun interface TetherInterceptor {
    /** Called before [payload] of [kind] on [tether] is delivered. */
    public suspend fun beforeDelivery(tether: TetherInfo, kind: TrafficKind, payload: Any?)
}

/** Settings of the in-process tether implementation. */
public class TetherConfig(
    /** Registry to validate values against port schemas; `null` disables schema validation. */
    public val schemas: SchemaRegistry? = null,
    /** Capacity of every buffer (message queue, stream direction). A full buffer suspends the sender. */
    public val bufferCapacity: Int = 64,
    /** How long a request waits for its response. */
    public val requestTimeout: Duration = Duration.ofSeconds(30),
    /** See [TetherObserver]. */
    public val observer: TetherObserver? = null,
    /** See [TetherInterceptor]. */
    public val interceptor: TetherInterceptor? = null,
    /**
     * Supplies the TCP driver of a block, used by tethers of type [TetherType.TCP]; the network closes the drivers it
     * asked for. `null` means the fabric has no TCP tethers.
     */
    public val tcp: ((blockId: String) -> TcpDriver)? = null,
    /**
     * Supplies the serial driver of a block, used by tethers of type [TetherType.SERIAL]; the network closes the
     * drivers it asked for. `null` means the fabric has no serial tethers.
     */
    public val serial: ((blockId: String) -> SerialDriver)? = null,
    /** Dispatcher the network runs its delivery coroutines on. */
    public val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Runs the tethers that end on another engine (bound to the fabric); `null` means this engine cannot run them. */
    public val remote: RemoteTetherPorts? = null,
    /** Public key fingerprints of the engines that may call the provided service ports of the blueprint (#177); see [TetherNetwork.setServiceCallers]. */
    public val serviceCallers: List<String> = emptyList(),
) {
    init {
        require(bufferCapacity >= 1) { "bufferCapacity must be at least 1" }
        require(!requestTimeout.isNegative && !requestTimeout.isZero) { "requestTimeout must be positive" }
    }
}
