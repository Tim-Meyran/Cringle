// SPDX-License-Identifier: Apache-2.0

package cringle.testkit

import cringle.contract.BlockDefinition
import cringle.contract.BlockPorts
import cringle.contract.Driver
import cringle.contract.DriverSet
import cringle.contract.DriverType
import cringle.contract.DwhDriver
import cringle.contract.DwhEntry
import cringle.contract.IsolationLevel
import cringle.contract.Tether
import cringle.contract.TetherByteStream
import cringle.contract.TetherStream
import cringle.contract.TetherType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

/**
 * A tether that stays in memory. It records everything the block under test sends and answers requests with a
 * scripted [requestHandler]. Operations that are not valid for its [type] throw [IllegalStateException], like a real
 * tether would.
 */
public class InMemoryTether(override val type: TetherType) : Tether {
    private val messages = CopyOnWriteArrayList<Any>()
    private val requestList = CopyOnWriteArrayList<Any>()
    private val streamList = CopyOnWriteArrayList<InMemoryTetherStream>()
    private val byteStreamList = CopyOnWriteArrayList<InMemoryTetherByteStream>()

    /** Messages sent through this tether, in order. */
    public val sentMessages: List<Any> get() = messages.toList()

    /** Requests sent through this tether, in order. */
    public val requests: List<Any> get() = requestList.toList()

    /** Streams opened by the block, in order. */
    public val streams: List<InMemoryTetherStream> get() = streamList.toList()

    /** Byte streams opened by the block, in order. */
    public val byteStreams: List<InMemoryTetherByteStream> get() = byteStreamList.toList()

    /** Computes the response of a request; by default every request fails. */
    public var requestHandler: suspend (Any) -> Any = { error("no response scripted for request $it") }

    private fun require(expected: TetherType, operation: String) {
        check(type == expected) { "$operation is not valid for a $type tether" }
    }

    override suspend fun send(message: Any) {
        require(TetherType.MESSAGE, "send")
        messages += message
    }

    override suspend fun request(request: Any): Any {
        require(TetherType.REQUEST_RESPONSE, "request")
        requestList += request
        return requestHandler(request)
    }

    override suspend fun openStream(): TetherStream {
        require(TetherType.STREAM, "openStream")
        return InMemoryTetherStream().also { streamList += it }
    }

    override suspend fun openByteStream(): TetherByteStream {
        require(TetherType.BYTE_STREAM, "openByteStream")
        return InMemoryTetherByteStream().also { byteStreamList += it }
    }
}

/** A stream held in memory: the test feeds [incoming] items with [feed] and reads what the block sent in [sent]. */
public class InMemoryTetherStream : TetherStream {
    private val channel = Channel<Any>(Channel.UNLIMITED)
    private val sentItems = CopyOnWriteArrayList<Any>()

    @Volatile
    private var isClosed = false

    override val incoming: Flow<Any> = channel.receiveAsFlow()

    /** Items the block sent, in order. */
    public val sent: List<Any> get() = sentItems.toList()

    /** Whether the block closed the stream. */
    public val closed: Boolean get() = isClosed

    /** Makes [item] arrive at the block. */
    public fun feed(item: Any) {
        check(channel.trySend(item).isSuccess) { "stream is closed" }
    }

    /** Ends the incoming flow. */
    public fun finish() {
        channel.close()
    }

    override suspend fun send(item: Any) {
        check(!isClosed) { "stream is closed" }
        sentItems += item
    }

    override suspend fun close() {
        isClosed = true
        channel.close()
    }
}

/** Byte stream analogue of [InMemoryTetherStream]. */
public class InMemoryTetherByteStream : TetherByteStream {
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    private val written = CopyOnWriteArrayList<ByteArray>()

    @Volatile
    private var isClosed = false

    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()

    /** Chunks the block wrote, in order. */
    public val sent: List<ByteArray> get() = written.toList()

    /** Whether the block closed the stream. */
    public val closed: Boolean get() = isClosed

    /** Makes [bytes] arrive at the block. */
    public fun feed(bytes: ByteArray) {
        check(channel.trySend(bytes).isSuccess) { "stream is closed" }
    }

    /** Ends the incoming flow. */
    public fun finish() {
        channel.close()
    }

    override suspend fun write(bytes: ByteArray) {
        check(!isClosed) { "stream is closed" }
        written += bytes
    }

    override suspend fun close() {
        isClosed = true
        channel.close()
    }
}

/** Ports of a block under test, each backed by an [InMemoryTether]. */
public class TestBlockPorts : BlockPorts {
    private val plain = LinkedHashMap<String, InMemoryTether>()
    private val varArg = LinkedHashMap<String, List<InMemoryTether>>()

    /** Adds a plain port and returns its tether. */
    public fun addPort(name: String, type: TetherType = TetherType.MESSAGE): InMemoryTether =
        InMemoryTether(type).also { plain[name] = it }

    /** Adds a VarArg port of fixed [size] and returns its tethers. */
    public fun addVarArgPort(name: String, size: Int, type: TetherType = TetherType.MESSAGE): List<InMemoryTether> =
        List(size) { InMemoryTether(type) }.also { varArg[name] = it }

    /** The tether of plain port [name]; fails if there is none. */
    public fun tether(name: String): InMemoryTether = plain[name] ?: throw IllegalArgumentException("No plain port '$name'")

    override fun port(name: String): Tether = tether(name)

    override fun varArgPort(name: String): List<Tether> = varArg[name] ?: throw IllegalArgumentException("No VarArg port '$name'")

    /** The in-memory tethers of VarArg port [name]. */
    public fun varArgTethers(name: String): List<InMemoryTether> =
        varArg[name] ?: throw IllegalArgumentException("No VarArg port '$name'")

    public companion object {
        /**
         * Creates the ports of [definition]. A port's tether type is taken from [tetherTypes] or else the first
         * type it supports; [varArgSizes] gives the size of every VarArg port (default 1).
         */
        public fun forDefinition(
            definition: BlockDefinition,
            varArgSizes: Map<String, Int> = emptyMap(),
            tetherTypes: Map<String, TetherType> = emptyMap(),
        ): TestBlockPorts = TestBlockPorts().also { ports ->
            for (p in definition.ports) {
                val type = tetherTypes[p.name] ?: p.tetherTypes.first()
                require(type in p.tetherTypes) { "Port '${p.name}' does not support $type" }
                if (p.varArg) ports.addVarArgPort(p.name, varArgSizes[p.name] ?: 1, type) else ports.addPort(p.name, type)
            }
        }
    }
}

/** A driver that records its lifecycle calls in [calls] (`"start"`, `"stop"`). Base class for fakes. */
public open class RecordingDriver(override val type: DriverType) : Driver {
    private val callList = CopyOnWriteArrayList<String>()

    /** Lifecycle calls in order. */
    public val calls: List<String> get() = callList.toList()

    override suspend fun start() {
        callList += "start"
    }

    override suspend fun stop() {
        callList += "stop"
    }
}

/** A DWH driver that keeps all written entries in [entries]. */
public class InMemoryDwhDriver : RecordingDriver(DriverType("dwh", IsolationLevel.SHARED)), DwhDriver {
    private val entryList = CopyOnWriteArrayList<DwhEntry>()

    /** Entries written so far, in order. */
    public val entries: List<DwhEntry> get() = entryList.toList()

    override suspend fun write(entry: DwhEntry) {
        entryList += entry
    }
}

/** A [DriverSet] that hands out registered fake drivers by their interface. */
public class TestDriverSet : DriverSet {
    private val drivers = LinkedHashMap<KClass<*>, Driver>()

    /** Registers [driver] under [type]. */
    public fun <T : Driver> add(type: KClass<T>, driver: T): TestDriverSet = also { drivers[type] = driver }

    /** Registers [driver] under its static type. */
    public inline fun <reified T : Driver> add(driver: T): TestDriverSet = add(T::class, driver)

    @Suppress("UNCHECKED_CAST")
    override fun <T : Driver> get(type: KClass<T>): T =
        (drivers[type] ?: throw IllegalArgumentException("No driver registered for ${type.simpleName}")) as T

    /** Starts all registered drivers, like the engine does before blocks start. */
    public suspend fun startAll() {
        drivers.values.distinct().forEach { it.start() }
    }

    /** Stops all registered drivers in reverse order. */
    public suspend fun stopAll() {
        drivers.values.distinct().reversed().forEach { it.stop() }
    }
}
