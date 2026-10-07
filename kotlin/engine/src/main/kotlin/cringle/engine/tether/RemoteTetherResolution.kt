// SPDX-License-Identifier: Apache-2.0

package cringle.engine.tether

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/**
 * Time for the tethers between engines, injectable so that tests need no sleeps: [nowMillis] is a monotonic clock for
 * the age of a cache entry, [delay] waits (for the backoff and the interval of the health check).
 */
public interface TetherClock {
    /** Milliseconds on a monotonic clock; only differences mean something. */
    public fun nowMillis(): Long

    /** Suspends for [millis] milliseconds. */
    public suspend fun delay(millis: Long)

    /** The system clock. */
    public companion object {
        /** The real time. */
        public val SYSTEM: TetherClock = object : TetherClock {
            override fun nowMillis(): Long = System.nanoTime() / 1_000_000

            override suspend fun delay(millis: Long) {
                kotlinx.coroutines.delay(millis)
            }
        }
    }
}

/** Settings of the resolution and the supervision of remote tethers (#148). All values are the simplest ones, `[Zu bestätigen]`. */
public class RemoteTetherOptions(
    /** How long the address of a fabric is kept before the registry is asked again. */
    public val resolutionTtl: Duration = Duration.ofSeconds(30),
    /** Wait before the first new attempt to resolve and connect; every further failure doubles it up to [backoffCap]. */
    public val backoffStart: Duration = Duration.ofMillis(250),
    /** The longest wait between two attempts. */
    public val backoffCap: Duration = Duration.ofSeconds(10),
    /** How often a connection is checked. */
    public val healthInterval: Duration = Duration.ofSeconds(5),
    /** How many checks in a row have to fail before the connection counts as lost. */
    public val healthFailures: Int = 3,
    /** Time. */
    public val clock: TetherClock = TetherClock.SYSTEM,
) {
    init {
        require(!resolutionTtl.isNegative) { "resolutionTtl must not be negative" }
        require(!backoffStart.isNegative && !backoffStart.isZero) { "backoffStart must be positive" }
        require(backoffCap >= backoffStart) { "backoffCap must not be smaller than backoffStart" }
        require(!healthInterval.isNegative && !healthInterval.isZero) { "healthInterval must be positive" }
        require(healthFailures >= 1) { "healthFailures must be at least 1" }
    }

    /** The wait before attempt number [failed] + 1 after [failed] attempts in a row failed (0 = the first wait). */
    internal fun backoff(failed: Int): Long {
        var wait = backoffStart.toMillis()
        repeat(failed) { wait = minOf(wait * 2, backoffCap.toMillis()) }
        return minOf(wait, backoffCap.toMillis())
    }
}

/** Finds where a fabric runs: the address of the tether service of its engine. */
public fun interface FabricResolver {
    /** Returns `host:port` of the tether service of the engine that runs [fabricId]; throws [FabricNotResolvedException] if it cannot be found. */
    public suspend fun resolve(fabricId: String): String
}

/** A fabric cannot be found (the registry does not know it, has no router, or the engine has no tether service). */
public class FabricNotResolvedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * A [FabricResolver] that asks [delegate] at most once per [ttl] for a fabric. A failed resolution is not kept. [invalidate]
 * drops an entry, as a lost connection does.
 */
public class CachingFabricResolver(
    private val delegate: FabricResolver,
    private val ttl: Duration,
    private val clock: TetherClock = TetherClock.SYSTEM,
) : FabricResolver {
    private class Entry(val address: String, val resolvedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    override suspend fun resolve(fabricId: String): String {
        val now = clock.nowMillis()
        entries[fabricId]?.takeIf { now - it.resolvedAt < ttl.toMillis() }?.let { return it.address }
        val address = delegate.resolve(fabricId)
        entries[fabricId] = Entry(address, clock.nowMillis())
        return address
    }

    /** Forgets the address of [fabricId]; the next [resolve] asks the registry. */
    public fun invalidate(fabricId: String) {
        entries.remove(fabricId)
    }
}

/**
 * A tether target as the blueprint names it (design time, the abstract dependency [expected]: the fabric id in the
 * `remote` of the tether) and the concrete fabric instance it is bound to at deploy time ([bound]).
 */
public data class TetherBinding(val expected: String, val bound: String)

/**
 * Hook for the binding of abstract tether targets to concrete fabric instances at deploy time (Architecture chapter 13).
 * Interface only: the engine uses [IDENTITY]; a ManagementServer implementation and a discovery model are not part of
 * this (`[Offen]`).
 */
public fun interface BindingResolver {
    /** Returns the binding of the target [expected] of the tether [tetherId]. */
    public fun bind(tetherId: String, expected: String): TetherBinding

    /** Binds every target to the fabric of the same id. */
    public companion object {
        /** Every target is bound to the fabric it names. */
        public val IDENTITY: BindingResolver = BindingResolver { _, expected -> TetherBinding(expected, expected) }
    }
}

/** A [BindingResolver] with a fixed table from the expected fabric id to the bound one; the targets not in it are bound to themselves. For tests. */
public class InMemoryBindingResolver(bindings: Map<String, String> = emptyMap()) : BindingResolver {
    private val table = ConcurrentHashMap(bindings)

    /** Binds [expected] to [bound] from now on. */
    public fun bindTo(expected: String, bound: String) {
        table[expected] = bound
    }

    override fun bind(tetherId: String, expected: String): TetherBinding = TetherBinding(expected, table[expected] ?: expected)
}

/**
 * The target of a sending tether is not connected at the moment (it is being resolved again, or the connection is
 * interrupted). A sender gets it for a send; with the delivery policy `BUFFER` the message is kept and tried again, with
 * `DROP` it is dropped and logged, as for a receiver that is not running.
 */
public class RemoteUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The supervision of one connection (#148): every [interval] the connection is checked with [probe]; [failures] failed
 * checks in a row mark it down ([onDown]), and the first success after that marks it up again ([onUp]). Runs until its
 * coroutine is cancelled; it holds nothing else.
 */
internal suspend fun superviseHealth(
    clock: TetherClock,
    interval: Duration,
    failures: Int,
    probe: suspend () -> Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit = {},
) {
    var failed = 0
    while (true) {
        clock.delay(interval.toMillis())
        val ok = try {
            probe()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (ok) {
            if (failed >= failures) onUp()
            failed = 0
        } else {
            failed++
            if (failed == failures) onDown()
        }
    }
}
