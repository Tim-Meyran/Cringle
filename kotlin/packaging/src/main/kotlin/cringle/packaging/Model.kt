// SPDX-License-Identifier: Apache-2.0

package cringle.packaging

import cringle.contract.BlockDefinition
import cringle.contract.IsolationLevel
import cringle.contract.Parity
import cringle.contract.TetherType
import kotlinx.serialization.json.JsonObject

/** Thrown when a package file (manifest, blueprint, block definition) is not valid. [path] is a JSON path or ZIP entry. */
public class PackageFormatException(public val path: String, problem: String) : RuntimeException("$path: $problem")

/** One finding of a validator: where ([path]) and what ([message]). */
public data class PackageProblem(public val path: String, public val message: String)

/** The two kinds of package. */
public enum class PackageKind(public val manifestFile: String, public val jsonName: String) {
    /** A project package (`cringle-project.json`). */
    PROJECT("cringle-project.json", "project"),

    /** A plugin package (`cringle-plugin.json`). */
    PLUGIN("cringle-plugin.json", "plugin"),
}

/** Optional plugin processors that migrate persisted state on update or downgrade; values are class names. */
public data class ProcessorSet(public val update: String? = null, public val downgrade: String? = null)

/**
 * Deployment instruction: run [instances] copies of the blueprint named [blueprint] on engines that have all
 * [roles] and all [labels]. Engines are never referenced by ID.
 */
public data class FabricConfig(
    public val blueprint: String,
    public val instances: Int,
    public val roles: List<String>,
    public val labels: Map<String, String>,
)

/** Manifest of a plugin package. Version ranges in [dependencies] are opaque strings here (parsed by #6). */
public data class PluginManifest(
    public val name: String,
    public val version: String,
    public val dependencies: Map<String, String> = emptyMap(),
    public val providers: List<String> = emptyList(),
    public val drivers: List<String> = emptyList(),
    public val blocks: List<BlockDefinition> = emptyList(),
    public val libs: List<String> = emptyList(),
    public val schemas: List<String> = emptyList(),
    public val processors: ProcessorSet = ProcessorSet(),
)

/** Manifest of a project package. */
public data class ProjectManifest(
    public val name: String,
    public val version: String,
    public val dependencies: Map<String, String> = emptyMap(),
    public val blueprints: List<String> = emptyList(),
    public val schemas: List<String> = emptyList(),
    public val fabrics: List<FabricConfig> = emptyList(),
)

/** One end of a tether. [index] selects the slot of a VarArg port and must be `null` for plain ports. */
public data class Endpoint(public val block: String, public val port: String, public val index: Int? = null)

/**
 * The far end of a cross-engine tether: a port of a block of a fabric on another engine at a fixed [address]
 * (`host:port`), or on the engine that the registry names for the [fabric] when there is no address. [fingerprint] is the SHA-256 of the public key of that engine in lowercase hex; the connection is
 * trusted by this key only, never on first use. [fabric], [block], [port] and [index] name the remote port in the
 * blueprint that is deployed there. The blueprint only names it; checking the remote schema and the connection are
 * later work (#146).
 */
public data class RemoteEndpoint(
    /**
     * `host:port` of the tether service of the engine, or `null` to resolve the engine at run time through the registry of
     * the router from the [fabric] id (#148).
     */
    public val address: String?,
    public val fingerprint: String,
    public val fabric: String,
    public val block: String,
    public val port: String,
    public val index: Int? = null,
    /**
     * Run time only, never in a blueprint file: further instances of the same service in the order of preference, tried
     * when this one is not reachable (#173). Each has its own [address], [fingerprint], [fabric], [block] and [port].
     */
    public val alternatives: List<RemoteEndpoint> = emptyList(),
    /** Run time only: the name of the service this end was bound from (#178), or `null` for a concrete `remote` of a blueprint. */
    public val service: String? = null,
)

/** What the engine does with a message, request or stream opening when the receiving block is not running. */
public enum class DeliveryPolicy {
    /** Drop it and log the event (at most once). The default. */
    DROP,

    /** Keep it in the tether's bounded buffer and deliver it when the receiver runs again; the sender suspends when the buffer is full. */
    BUFFER,
}

/** How the delay between retry attempts grows. */
public enum class Backoff {
    /** The same delay before every attempt. */
    FIXED,

    /** The delay doubles with every attempt, capped by [RetryConfig.maxBackoffMs]. */
    EXPONENTIAL,
}

/**
 * Retry configuration of a tether for a receiver that is not running. Only used with delivery `BUFFER`; a `retry`
 * object on a `DROP` tether is a validation error.
 */
public data class RetryConfig(
    /** Maximum number of delivery attempts; `null` means unlimited (retry until the receiver runs). */
    public val maxAttempts: Int? = null,
    /** Base delay between attempts in milliseconds. */
    public val backoffMs: Long = 50,
    /** How the delay grows between attempts. */
    public val backoff: Backoff = Backoff.FIXED,
    /** Upper bound of the delay in milliseconds (used by [Backoff.EXPONENTIAL]). */
    public val maxBackoffMs: Long = 5000,
)

/** The serial line of a [TetherType.SERIAL] tether: the device and its line settings. */
public data class SerialTetherConfig(
    /** The serial device name (e.g. `/dev/ttyUSB0` or `COM1`). */
    public val device: String,
    /** Baud rate in bits per second. */
    public val baudRate: Int = 9600,
    /** Number of data bits (5 to 8). */
    public val dataBits: Int = 8,
    /** Parity. */
    public val parity: Parity = Parity.NONE,
    /** Number of stop bits (1 or 2). */
    public val stopBits: Int = 1,
)

/** A tether of fixed [type] from an OUT port to an IN port, with a [delivery] policy for a receiver that is not running. */
public data class TetherDef(
    public val type: TetherType,
    /**
     * The sending end of a tether between two local ports. `null` only in a tether with a [remote] whose far end sends
     * (then [to] is set); a valid tether has [from] and [to], or exactly one of them and a [remote].
     */
    public val from: Endpoint?,
    /** The receiving end; see [from]. `null` only in a tether with a [remote] that receives (then [from] is set). */
    public val to: Endpoint?,
    public val delivery: DeliveryPolicy = DeliveryPolicy.DROP,
    /** The TCP port of a [TetherType.TCP] tether; `null` for every other type. */
    public val port: Int? = null,
    /** Buffer capacity of this tether; `null` uses the global [cringle.engine.tether.TetherConfig] value. */
    public val bufferCapacity: Int? = null,
    /** How long a request waits for its response; `null` uses the global value. */
    public val requestTimeout: java.time.Duration? = null,
    /** Retry configuration; `null` uses the default (unlimited, fixed 50 ms). */
    public val retry: RetryConfig? = null,
    /** The serial line of a [TetherType.SERIAL] tether; `null` for every other type. */
    public val serial: SerialTetherConfig? = null,
    /** The far end on another engine; `null` for a tether between two local ports. See [RemoteEndpoint]. */
    public val remote: RemoteEndpoint? = null,
    /**
     * The far end on another engine as an abstract dependency: the name of a service that a blueprint of another
     * fabric provides (see [ProvidedService]). Bound to a concrete [remote] at deploy time (#171, #172); `null` if the
     * tether is local or has a [remote]. At most one of [remote] and [service] is set.
     */
    public val service: String? = null,
    /** Marks the tether for recording in the data warehouse of the engine (#193), with the retention of its partition. `null`: not recorded by the tether's own definition. */
    public val record: RecordConfig? = null,
)

/**
 * What to keep of a recorded tether: records older than [maxAge] and, beyond [maxBytes], the oldest ones are removed. `null` means no limit.
 * An empty [RecordConfig] records without limits.
 */
public data class RecordConfig(public val maxAge: java.time.Duration? = null, public val maxBytes: Long? = null)

/**
 * A port that a blueprint offers to the tethers of other projects under the name [service] (Architecture chapter
 * 13.1): the `IN` port [port] of the block [block] of this blueprint.
 */
public data class ProvidedService(
    public val service: String,
    public val block: String,
    public val port: String,
    /**
     * The tether type of the calls to the port: `MESSAGE`, `REQUEST_RESPONSE`, `STREAM` or `BYTE_STREAM`. `null` means the
     * one remote-capable type that the port supports; a port that supports several needs it (#177).
     */
    public val type: TetherType? = null,
)

/**
 * A block instance in a blueprint. [block] is `pluginName/blockName`; [varArgCounts] gives the fixed size of every
 * VarArg port of the block.
 */
public data class BlueprintBlock(
    public val id: String,
    public val block: String,
    public val config: JsonObject = JsonObject(emptyMap()),
    public val isolation: IsolationLevel = IsolationLevel.SHARED,
    public val varArgCounts: Map<String, Int> = emptyMap(),
)

/** A blueprint. It has no version of its own and no engine placement; it always runs in one engine. */
public data class Blueprint(
    public val name: String,
    public val blocks: List<BlueprintBlock>,
    public val tethers: List<TetherDef>,
    /** The services this blueprint provides; empty for an ordinary blueprint. */
    public val provides: List<ProvidedService> = emptyList(),
)

/** A read project package. [schemas] maps ZIP entry to schema text; [files] lists every entry name, sorted. */
public data class ProjectPackage(
    public val manifest: ProjectManifest,
    public val blueprints: List<Blueprint>,
    public val schemas: Map<String, String>,
    public val files: List<String>,
)

/** A read plugin package. [schemas] maps ZIP entry to schema text; [files] lists every entry name, sorted. */
public data class PluginPackage(
    public val manifest: PluginManifest,
    public val schemas: Map<String, String>,
    public val files: List<String>,
)
