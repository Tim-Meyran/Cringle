// SPDX-License-Identifier: Apache-2.0

package cringle.stdblocks.file

import cringle.contract.Block
import cringle.contract.BlockContext
import cringle.contract.FilesystemDriver
import cringle.contract.Tether
import cringle.contract.TetherEvent
import cringle.stdblocks.requireLong
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The blocks of the theme `file` work on the folder that the blocks of a fabric share (driver `filesystem-fabric`); a path is relative to it. */

/** `base/name` with one slash between them; either may be missing. */
internal fun joinPath(base: String?, name: String?): String {
    val b = base?.trim('/')?.takeIf { it.isNotEmpty() }
    val n = name?.trim('/')?.takeIf { it.isNotEmpty() }
    return when {
        b != null && n != null -> "$b/$n"
        b != null -> b
        n != null -> n
        else -> ""
    }
}

private fun TetherEvent.textAt(port: String): String? = if (this is TetherEvent.Message && this.port.name == port) value as String else null

/** Creates a fresh folder `tmp/<prefix>-<uuid>` for every message at `create` and sends its path on `path`; removes the folders it made when it is destroyed. */
internal class TempDir(private val fs: FilesystemDriver) : Block {
    private var prefix = "tmp"
    private lateinit var out: Tether
    private val created = ArrayList<String>()

    override suspend fun init(context: BlockContext) {
        (context.config["prefix"] as? String)?.let {
            require(Regex("[A-Za-z0-9._-]{1,32}").matches(it)) { "the configuration 'prefix' is 1 to 32 letters, digits, '.', '_' or '-'" }
            prefix = it
        }
        out = context.ports.port("path")
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        if (event is TetherEvent.Message && event.port.name == "create") {
            val path = "tmp/$prefix-${UUID.randomUUID()}"
            fs.createDirectories(path)
            created += path
            out.send(path)
        }
    }

    override suspend fun destroy() {
        for (path in created) fs.deleteRecursively(path)
        created.clear()
    }
}

/**
 * Writes the text at `in` to a file and sends the path on `done`. The file is `name` (configuration) in the directory that last arrived at `path`; without `name` the
 * text at `path` is the file itself. `append` adds to the file instead of replacing it.
 */
internal class FileWrite(private val fs: FilesystemDriver) : Block {
    private var name: String? = null
    private var append = false
    private var target: String? = null
    private lateinit var done: Tether

    override suspend fun init(context: BlockContext) {
        name = context.config["name"] as? String
        append = context.config["append"] as? Boolean ?: false
        done = context.ports.port("done")
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        event.textAt("path")?.let { target = it }
        val text = event.textAt("in") ?: return
        val path = if (name != null) joinPath(target, name) else target ?: throw IllegalStateException("no path yet: send a path to 'path' before the text, or configure 'name'")
        if (append) fs.append(path, text.toByteArray(Charsets.UTF_8)) else fs.writeText(path, text)
        done.send(path)
    }
}

/** Reads the file at the path that arrives at `path` (with `name` below it, if configured) and sends its text on `text`. */
internal class FileRead(private val fs: FilesystemDriver) : Block {
    private var name: String? = null
    private lateinit var text: Tether

    override suspend fun init(context: BlockContext) {
        name = context.config["name"] as? String
        text = context.ports.port("text")
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        val base = event.textAt("path") ?: return
        text.send(fs.readText(joinPath(base, name)))
    }
}

/** Sends the entries of the directory at `path` on `name`, one message each, with the directory in front. */
internal class FileList(private val fs: FilesystemDriver) : Block {
    private lateinit var out: Tether

    override suspend fun init(context: BlockContext) {
        out = context.ports.port("name")
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        val dir = event.textAt("path") ?: return
        for (entry in fs.list(dir.trim('/'))) out.send(joinPath(dir, entry))
    }
}

/** Deletes the file, or the folder with all it holds, at `path` and sends the path on `deleted`; sends nothing if there was nothing. */
internal class FileDelete(private val fs: FilesystemDriver) : Block {
    private lateinit var deleted: Tether

    override suspend fun init(context: BlockContext) {
        deleted = context.ports.port("deleted")
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        val path = event.textAt("path") ?: return
        if (fs.deleteRecursively(path.trim('/'))) deleted.send(path)
    }
}

/** Sends the path at `path` on `yes` if a file or folder is there, on `no` if not. */
internal class FileExists(private val fs: FilesystemDriver) : Block {
    private lateinit var yes: Tether
    private lateinit var no: Tether

    override suspend fun init(context: BlockContext) {
        yes = context.ports.port("yes")
        no = context.ports.port("no")
    }

    override suspend fun onTetherEvent(event: TetherEvent) {
        val path = event.textAt("path") ?: return
        if (fs.exists(path.trim('/'))) yes.send(path) else no.send(path)
    }
}

/**
 * Looks at the directory `path` (default the shared folder itself) every `intervalMs` and sends the paths of entries that appeared on `created` and of
 * entries that went away on `removed`. What was there at the start is only reported with `reportExisting`.
 */
internal class FileWatch(private val fs: FilesystemDriver, private val dispatcher: CoroutineDispatcher = Dispatchers.Default) : Block {
    private var directory = ""
    private var interval = 1000L
    private var reportExisting = false
    private lateinit var created: Tether
    private lateinit var removed: Tether
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override suspend fun init(context: BlockContext) {
        directory = (context.config["path"] as? String).orEmpty().trim('/')
        interval = if (context.config["intervalMs"] == null) 1000L else context.config.requireLong("intervalMs", 50)
        reportExisting = context.config["reportExisting"] as? Boolean ?: false
        created = context.ports.port("created")
        removed = context.ports.port("removed")
    }

    private suspend fun names(): Set<String> = if (fs.exists(directory)) fs.list(directory).toSet() else emptySet()

    override suspend fun start() {
        val own = CoroutineScope(SupervisorJob() + dispatcher)
        scope = own
        job = own.launch {
            var known = names()
            if (reportExisting) for (n in known.sorted()) created.send(joinPath(directory, n))
            while (isActive) {
                delay(interval)
                val now = names()
                for (n in (now - known).sorted()) created.send(joinPath(directory, n))
                for (n in (known - now).sorted()) removed.send(joinPath(directory, n))
                known = now
            }
        }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
        scope?.coroutineContext?.get(Job)?.cancel()
        job = null
        scope = null
    }
}
