// SPDX-License-Identifier: Apache-2.0

package cringle.common

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Enters the keys of the other components of one Cringle home into a trust store when the local trust is on ([LocalTrust]): the
 * [components] (a folder below the home, a name and the kind of trust) and, with [engines], every engine in
 * `<home>/engines/<id>` as `ENGINE`. A component is entered as soon as its public key file exists. Nothing is created and
 * nothing is removed, and an entry that exists is left alone.
 *
 * [sync] is cheap (a few small files) and thread-safe; a call closer than [minInterval] to the last scan does nothing unless it
 * is forced, so it can run before every new connection or on a timer.
 */
public class LocalTrustScanner(
    private val home: Path,
    private val store: TrustStore,
    private val components: List<Component>,
    private val engines: Boolean,
    private val minInterval: Duration = Duration.ofMillis(500),
) {
    /** A component whose key is looked for in `<home>/[dir]`; `"."` is the home itself (the Gradle plugin keeps its identity there). */
    public data class Component(val dir: String, val name: String, val kind: TrustKind)

    private var lastScan = 0L

    /** Enters what is new and returns how many entries were added. */
    @Synchronized
    public fun sync(force: Boolean = false): Int {
        val now = System.nanoTime()
        if (!force && lastScan != 0L && now - lastScan < minInterval.toNanos()) return 0
        lastScan = now
        var added = 0
        fun trust(componentDir: Path, name: String, kind: TrustKind) {
            val fingerprint = LocalTrust.fingerprintOf(componentDir) ?: return
            if (LocalTrust.trust(store, fingerprint, name, kind)) added++
        }
        for (c in components) trust(home.resolve(c.dir), c.name, c.kind)
        val engineDirs = home.resolve("engines")
        if (engines && Files.isDirectory(engineDirs)) {
            Files.list(engineDirs).use { dirs -> dirs.filter { Files.isDirectory(it) }.forEach { trust(it, it.fileName.toString(), TrustKind.ENGINE) } }
        }
        return added
    }
}
