# Logging

Cringle's Kotlin processes (daemon, engine, management server) write structured logs to per-component files under the Cringle home directory. This is the logging infrastructure for the Cringle processes themselves — it is **not** the block logging driver (Architecture 16.1) and **not** the per-fabric/block log folder (Architecture chapter 15).

## Log location

Logs are written to `<home>/logs/<component>-<name>.log`, where:

- `<home>` is the Cringle home directory (`$CRINGLE_HOME` or `~/.cringle`).
- `<component>` is one of `daemon`, `engine`, `management`.
- `<name>` is a process name (currently always `main`).

For example, the daemon writes to `~/.cringle/logs/daemon-main.log`.

## Log levels

The root log level is `INFO` by default. Two mechanisms override it:

1. **Environment variable** `CRINGLE_LOG_LEVEL` sets the root level. Valid values: `DEBUG`, `INFO`, `WARN`, `ERROR` (case-insensitive). Invalid values are ignored with a warning, and the level falls back to `INFO`.

2. **Properties file** `<home>/logging.properties` sets per-logger levels. Each line has the form `level.<logger-prefix>=<LEVEL>`. For example:

   ```
   level.cringle.engine=DEBUG
   level.cringle.daemon.grpc=WARN
   ```

   Invalid level values are ignored with a warning.

## Log files of foreign processes (#189)

A block that starts a process with its own log files lets it write `*.log` files into the log folder of the block,
`BlockContext.logDirectory` (`<engine dir>/fabrics/<fabric>/logs/<block>/`). The engine reads these files when logs are queried
(`QueryLogs`, `cringle logs`): every line becomes an entry of that fabric and block with the file name as `source`. A line that starts
with an ISO-8601 instant (`2026-10-01T10:00:00Z`) has that time, any other line the modification time of the file; a line that starts
with `DEBUG`, `INFO`, `WARN` or `ERROR` (after the time, case-insensitive, optionally in brackets) has that level, any other line
`INFO`. The message is the whole line. `block.log` (the mirror of the entries of the logging driver) is not read again; at most 20
files per block and the last megabyte of each are read; file names outside `[A-Za-z0-9._-]+.log` and symbolic links are skipped.
`cringle logs` shows the source in brackets after fabric and block.

## Rotation

Log files are rotated by size and time:

- **Size limit**: 100 MB per file.
- **Time limit**: one file per day.
- **History**: keep 14 days of rotated files.
- **Total cap**: 2 GB across all rotated files.
- **Compression**: rotated files are gzipped (`.log.gz`).

The constants are exposed as `CringleLogging.MAX_FILE_SIZE_BYTES`, `CringleLogging.MAX_HISTORY_DAYS`, and `CringleLogging.TOTAL_SIZE_CAP_BYTES`.

## Secret masking

Secrets are masked in both log messages and stack traces. The following patterns are replaced with `***`:

- PEM private key blocks (`-----BEGIN ... PRIVATE KEY-----` ... `-----END ... PRIVATE KEY-----`).
- `Bearer <token>` (Authorization header value).
- `key=value` and `key: value` for keys: `password`, `passwd`, `secret`, `token`, `apikey`, `api-key`, `authorization` (case-insensitive).
- `"key":"value"` and `'key':'value'` (JSON-style) for the same keys.

Harmless text without these patterns is unchanged. The masking function is exposed as `CringleLogging.maskSecrets(text: String): String`.

## Getting a logger

Use SLF4J directly:

```kotlin
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("cringle.foo")

log.info("started on port {}", port)
log.warn("connection failed: {}", e.message)
log.error("operation failed", exception)
```

## Initialization

Call `CringleLogging.init(home, component, name)` after parsing the `--home` argument, so the log file lands in the same directory as the rest of the state. For example, in the daemon:

```kotlin
import cringle.common.logging.CringleLogging
import cringle.engine.CringleHome
import java.nio.file.Paths

fun main(args: Array<String>) {
    var home: java.nio.file.Path? = null
    // ... parse --home into `home` ...
    CringleLogging.init(CringleHome.resolve(home), "daemon", "main")
    // ... rest of main
}
```

The engine parses its arguments through `EngineArgs`, so the same pattern looks like:

```kotlin
val parsed = try {
    EngineArgs.parse(args.toList())
} catch (e: EngineArgsException) {
    System.err.println("error: ${e.message}")
    System.err.println(EngineArgs.USAGE)
    exitProcess(2)
}
CringleLogging.init(CringleHome.resolve(parsed.home), "engine", "main")
val engine = Engine.create(parsed)
```

The `init` call is idempotent: calling it again with the same arguments replaces the configuration. Calling it with different arguments resets and reconfigures.

## Logging collector (#195)

Logs are first kept by every engine itself. The LoggingCollector of a machine (part of its daemon) keeps them beyond the life of an engine:
`cringle engine collect <machine> <engine> on` switches it on for that engine (off by default; the choice is kept in
`<home>/daemon/collector.json`). Every 10 seconds the daemon asks each running engine it is switched on for for the entries of the logging
driver since the last one it has and appends them to `<home>/daemon/collected/<engine>/logs/engine.log` (the format of the engine's own store;
the file is cut to the newest 50 MB when it grows past 60 MB). Lines of foreign log files are not collected: they stay in the log folders on
the machine and are part of the log query while the engine runs.

`QueryLogs` (`cringle logs`) of a stopped engine, or of one that does not answer, returns the collected entries marked as collected (`(collected)`
in the text output, `"collected": true` in JSON) if the collector is on for it; without it the call fails as before. In the list of all engines
the collected entries of stopped engines are added. Entries of a running engine are always the live ones.
