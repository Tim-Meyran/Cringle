// SPDX-License-Identifier: Apache-2.0

package cringle.common

import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Checks the certificate of [identity] when it is started and then every [interval], and renews it with [Identity.renewIfDue]. A running gRPC server keeps the
 * certificate it was built with (`[Zu bestätigen]`: no hot reload), so a renewal is logged with the hint that it takes effect at the next start of the component.
 */
public class CertificateWatcher(
    private val identity: Identity,
    private val clock: Clock = Clock.systemUTC(),
    private val interval: Duration = Duration.ofHours(24),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger("cringle.common.identity")

    @Volatile
    private var job: Job? = null

    private var ownScope: CoroutineScope? = null

    /** Starts the checks in [scope], or in a scope of its own that [close] ends; a second call does nothing. */
    public fun start(scope: CoroutineScope? = null): CertificateWatcher {
        if (job != null) return this
        val where = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also { ownScope = it }
        job = where.launch {
            while (isActive) {
                try {
                    if (identity.renewIfDue(clock = clock)) log.info("certificate renewed on disk; it takes effect when the component restarts")
                } catch (e: Exception) {
                    log.error("the certificate could not be renewed: {}", e.message)
                }
                delay(interval.toMillis())
            }
        }
        return this
    }

    /** Stops the checks. */
    override fun close() {
        job?.cancel()
        job = null
        ownScope?.cancel()
        ownScope = null
    }
}
