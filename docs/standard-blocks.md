# Standard blocks (the plugin `cringle-std`)

Applications are assembled from blocks, and a new installation has none. The plugin **`cringle-std`** brings a library of blocks that every installation has: time, text, flow, and later files, math and logic. They show up in the palette of the blueprint editor under `cringle-std` and in `cringle repo list`.

## How it reaches the repository

The management server carries the plugin (the jar of the module `kotlin/stdblocks` is a resource of the program). At every start it asks the repository whether `cringle-std` in the version of the program (`StdBlockProvider.VERSION`) exists; if not, it builds the package, publishes it and marks the plugin **`trusted`**. Details:

- It is done in the background and tried again for about two minutes, because the repository of the installed service may start after the management server. A failure is a warning in the log (`cringle.management.std`), never a reason to stop.
- It is done **once per version**: when you mark the plugin `untrusted` later (`cringle repo trust cringle-std untrusted`), the next start leaves that alone. A version that exists is never replaced (versions are immutable).
- A project uses it like any plugin: `dependency("cringle-std", "^1.0.0")` in the build or, in the editor, by adding a block; the editor adds the dependency itself.

## Rules of the library

- **Block names** are `<theme>.<name>` in lower case: `time.timer-trigger`, `flow.log`. The themes are `time`, `flow`, `text` and `file`; `math` and `logic` follow.
- **Fixed types.** A block works on one type: counters are `Int`, texts are `String`, conditions will be `Boolean`, numbers `Double`. **Converter blocks** (`text.to-string` so far) join them. There is no type that stands for any value; a block that only passes values on (delay, buffer, merge) therefore exists for `String` until that is decided (`docs/status.md`).
- Every block has a configuration schema in the namespace `cringle.stdblocks` (`kotlin/stdblocks/src/main/resources/cringle/stdblocks/schema.json`) when it needs a configuration; the editor and the engine check it.
- A block never reaches outside through anything but a driver: `flow.log` uses the logging driver, the `file` blocks the driver `filesystem-fabric` (below).

## The blocks

| Block | Ports | Configuration | What it does |
|---|---|---|---|
| `time.timer-trigger` | OUT `tick` (`MESSAGE`, `Int`) | `intervalMs` (≥ 1), `initialDelayMs` (default 0), `count` (optional, ≥ 1) | Sends a counter from 1 every `intervalMs`, after `initialDelayMs`; stops after `count` ticks if that is set. |
| `flow.constant` | IN `trigger` (`Int`), OUT `out` (`String`) | `value` | Sends `value` for every message at `trigger`. |
| `text.to-string` | IN `in` (`Int`), OUT `out` (`String`) | – | The decimal text of the integer. |
| `flow.log` | IN `in` (`String`) | `level` (`DEBUG`, `INFO` default, `WARN`, `ERROR`), `prefix` (optional) | Writes `prefix + text` to the log of the fabric (`cringle logs`). |

### Files: `file.*`

The blocks of the theme `file` work on **one folder per fabric** that all its blocks share: the driver `filesystem-fabric` (`<engine>/fabrics/<fabric>/shared`, removed with the fabric). It is the same interface as the driver `filesystem` (relative paths only, no `..`, no link out), only the root differs: `filesystem` is the working folder of the block alone. Another fabric has its own folder. A path is a `String` relative to the shared folder; text is UTF-8.

| Block | Ports | Configuration | What it does |
|---|---|---|---|
| `file.temp-dir` | IN `create` (`Int`), OUT `path` | `prefix` (default `tmp`) | A fresh folder `tmp/<prefix>-<uuid>` for every message at `create`; its path goes out. The folders it made are removed (with their content) when the block is destroyed. |
| `file.write` | IN `path`, IN `in`, OUT `done` | `name` (optional), `append` (default false) | Writes the text at `in` to a file and sends its path. The file is `name` in the directory that last arrived at `path`; without `name` the text at `path` is the file. A text at `in` before any path is an error. |
| `file.read` | IN `path`, OUT `text` | `name` (optional) | Reads the file (`name` below the path, if configured) and sends its text. |
| `file.list` | IN `path`, OUT `name` | – | One message per entry of the directory, with the directory in front. |
| `file.delete` | IN `path`, OUT `deleted` | – | Deletes a file or a folder with everything in it and sends the path; nothing if it was not there. The shared folder itself is never deleted. |
| `file.exists` | IN `path`, OUT `yes`, OUT `no` | – | The path goes out on `yes` or on `no`. |
| `file.watch` | OUT `created`, OUT `removed` | `path` (directory, default the shared folder), `intervalMs` (≥ 50, default 1000), `reportExisting` (default false) | Looks at the directory every `intervalMs`; sends the paths of entries that appeared and that went away. It sees names, not changes of content. |

A failing operation (a path that leaves the folder, a file that is not there) is an error of the block: the engine reports it and restarts the block as its policy says. Example, a file from a temporary folder (`StdBlocksEndToEndTest`):

```
timer ─tick→ file.temp-dir ─path→ file.write{name: note.txt} ─done→ file.read ─text→ flow.log
                                         ↑ in
           flow.constant{value: "hello file"} ←trigger─ timer (later)
```

Example: a timer that logs three ticks (it is the test `StdBlocksEndToEndTest`):

```
time.timer-trigger{intervalMs: 30, count: 3} --tick--> text.to-string --out--> flow.log{prefix: "std-tick: "}
```

## Writing a block for the library

1. A class under `kotlin/stdblocks/src/main/kotlin/cringle/stdblocks/<theme>/`, internal, implementing `Block`; its definition and its creation in `StdBlockProvider`; a configuration type in `schema.json`.
2. A test with `BlockTestHarness` (timers with a test dispatcher).
3. **Raise `StdBlockProvider.VERSION`**: a version in a repository never changes, so a changed block needs a new version, and the program publishes the new one at its next start.
4. A line in the table above.
