# Standard blocks (the plugin `cringle-std`)

Applications are assembled from blocks, and a new installation has none. The plugin **`cringle-std`** brings a library of blocks that every installation has: time, text, flow, and later files, math and logic. They show up in the palette of the blueprint editor under `cringle-std` and in `cringle repo list`.

## How it reaches the repository

The management server carries the plugin (the jar of the module `kotlin/stdblocks` is a resource of the program). At every start it asks the repository whether `cringle-std` in the version of the program (`StdBlockProvider.VERSION`) exists; if not, it builds the package, publishes it and marks the plugin **`trusted`**. Details:

- It is done in the background and tried again for about two minutes, because the repository of the installed service may start after the management server. A failure is a warning in the log (`cringle.management.std`), never a reason to stop.
- It is done **once per version**: when you mark the plugin `untrusted` later (`cringle repo trust cringle-std untrusted`), the next start leaves that alone. A version that exists is never replaced (versions are immutable).
- A project uses it like any plugin: `dependency("cringle-std", "^1.0.0")` in the build or, in the editor, by adding a block; the editor adds the dependency itself.

## Rules of the library

- **Block names** are `<theme>.<name>` in lower case: `time.timer-trigger`, `flow.log`. The themes are `time`, `flow`, `text`, `file`, `math` and `logic`.
- **Fixed types.** A block works on one type: counters are `Int`, texts are `String`, conditions are `Boolean`, numbers `Double`. **Converter blocks** (`text.to-string`, `text.parse-int`, `math.int-to-double`, …) join them. The type **`cringle.std/Any`** (`spec/schema.md`) stands for a value of any type: every typed port can feed an `Any` port, and the `data.as-*` blocks turn an `Any` value back into a typed one after checking it. Blocks that only pass values on (delay, buffer, merge, …) work on `Any`.
- Every block has a configuration schema in the namespace `cringle.stdblocks` (`kotlin/stdblocks/src/main/resources/cringle/stdblocks/schema.json`) when it needs a configuration; the editor and the engine check it.
- A block never reaches outside through anything but a driver: `flow.log` uses the logging driver, the `file` blocks the driver `filesystem-fabric` (below).

## The blocks

| Block | Ports | Configuration | What it does |
|---|---|---|---|
| `time.timer-trigger` | OUT `tick` (`MESSAGE`, `Int`) | `intervalMs` (≥ 1), `initialDelayMs` (default 0), `count` (optional, ≥ 1) | Sends a counter from 1 every `intervalMs`, after `initialDelayMs`; stops after `count` ticks if that is set. |
| `flow.constant` | IN `trigger` (`Int`), OUT `out` (`String`) | `value` | Sends `value` for every message at `trigger`. |
| `text.to-string` | IN `in` (`Int`), OUT `out` (`String`) | – | The decimal text of the integer. |
| `flow.log` | IN `in` (`String`) | `level` (`DEBUG`, `INFO` default, `WARN`, `ERROR`), `prefix` (optional) | Writes `prefix + text` to the log of the fabric (`cringle logs`). |

Two rules apply to all blocks with more than one input or output:

- **Two inputs `a` and `b`** (`text.concat`, `math.add`, `logic.compare`, …): the block keeps the **latest value of each** and sends a result whenever one of them arrives, as soon as both have a value.
- **An output port can start several `MESSAGE` tethers** (`spec/tether.md`): connect it to every block that needs the value; there are no copy blocks. A port that sends requests or streams has one tether.
- A bad input (a text that is no number, a division by zero, no match) is sent on a port `error` or `nomatch` with the reason or the input; it never throws.

### Flow and data (generic, on `Any`)

| Block | Ports | Configuration | What it does |
|---|---|---|---|
| `flow.delay` | IN `in`, OUT `out` (`Any`) | `delayMs` (≥ 0) | Every value leaves after the delay, in order; values that still wait are dropped when the block stops. |
| `flow.debounce` | IN `in`, OUT `out` | `quietMs` (≥ 1) | The last value after that long without a new one. |
| `flow.buffer` | IN `in`, `flush` (`Int`), OUT `batch` | `size` (optional, ≥ 1) | Collects values and sends them as one list when `flush` arrives or `size` is reached; an empty buffer sends nothing. |
| `flow.merge` | IN `a`, `b`, OUT `out` | – | Every value of either input passes. |
| `flow.throttle` | IN `in`, OUT `out`, `dropped` | `intervalMs` (≥ 1) | At most one value per interval on `out`, the others on `dropped`. |
| `flow.once` | IN `in`, `reset` (`Int`), OUT `out` | – | Only the first value passes; `reset` lets the next one pass. |
| `flow.log-any` | IN `in` | `level`, `prefix` | Like `flow.log`, the value as JSON text. |
| `logic.if-any`, `logic.filter-any` | as `logic.if`, `logic.filter` | – | For values of any type. |
| `data.as-string`, `-number`, `-int`, `-boolean` | IN `in` (`Any`), OUT `out` (typed), `error` | – | The value as that type, or the reason on `error` when it is of another kind (`as-int` takes whole numbers only). |

### JSON and Base64: `data.*`

A JSON document is a value of the type `Any`: texts, numbers (a whole number that fits is an `Int`, every other one a `Double`), booleans, lists and objects; `null` is not a value. A **path** is written `user.name`, `items[0].id`, `[0][1]` or with a `$` in front (`$.a.b`).

| Block | Ports | Configuration | What it does |
|---|---|---|---|
| `data.json-parse` | IN `in` (`String`), OUT `out` (`Any`), `error` | – | Parses the text strictly (no `null`, no duplicate key, no text after the document, nesting up to 100); `error` has the reason and the position. |
| `data.json-stringify` | IN `in` (`Any`), OUT `out` (`String`) | – | The JSON text of the value. |
| `data.json-get` | IN `in`, OUT `found`, `missing` (`Any`) | `path` | The value at the path on `found`; the input on `missing` when the path leads nowhere. |
| `data.json-set` | IN `a` (the document), `b` (the value), OUT `out`, `error` | `path` | A copy of the latest document with the latest value at the path (`a` and `b` as for every block with two inputs); missing object keys are created, an index one past the end appends; `error` says why a path does not fit. The original stays as it was. |
| `data.base64-encode`, `data.base64-decode` | IN `in`, OUT `out` (`error` for decode) | – | Base64 of the UTF-8 bytes of a text and back. |
| `data.size` | IN `in` (`Any`), OUT `out` (`Int`), `error` | – | Characters of a text, entries of a list or an object. |

### Text, math and logic

| Block | Ports | Configuration | What it does |
|---|---|---|---|
| `text.concat` | IN `a`, `b`, OUT `out` | `separator` | `a + separator + b`. |
| `text.template` | IN `in`, OUT `out` | `template` (with `{}`) | The template with the text at `{}`. |
| `text.split` | IN `in`, OUT `part` | `separator` | One message per part. |
| `text.replace` | IN `in`, OUT `out` | `find`, `replacement`, `regex` | Replaces every occurrence (regular expression if `regex`). |
| `text.regex-match` | IN `in`, OUT `match`, `nomatch` | `pattern`, `group` (default 0) | The group of the first match, or the input on `nomatch`. |
| `text.trim`, `text.upper`, `text.lower` | IN `in`, OUT `out` | – | Trims, upper-cases, lower-cases. |
| `text.length` | IN `in`, OUT `out` (`Int`) | – | Number of characters. |
| `text.contains` | IN `in`, OUT `yes`, `no` | `needle` | The text goes out on `yes` or `no`. |
| `text.parse-int`, `-double`, `-boolean` | IN `in`, OUT `out` (`Int`, `Double`, `Boolean`), `error` | – | Converts; `error` has the reason. |
| `text.double-to-string`, `text.boolean-to-string` | IN `in`, OUT `out` | – | The text of a number or a boolean. |
| `math.constant` | IN `trigger` (`Int`), OUT `out` (`Double`) | `value` | Sends `value` for every trigger. |
| `math.add`, `subtract`, `multiply`, `min`, `max` | IN `a`, `b`, OUT `out` (`Double`) | – | The result of the latest values. |
| `math.divide`, `math.modulo` | IN `a`, `b`, OUT `out`, `error` | – | As above; `error` says `division by zero` when `b` is 0. |
| `math.abs`, `negate`, `floor`, `ceil` | IN `in`, OUT `out` | – | One-number functions. |
| `math.round` | IN `in`, OUT `out` | `decimals` (0 to 15, default 0) | Rounds half up. |
| `math.random` | IN `trigger`, OUT `out` | `min` (0), `max` (1) | A random number from `min` up to, not including, `max`. |
| `math.counter` | IN `in`, `reset` (`Int`), OUT `count` (`Int`) | `start` (0), `step` (1) | Adds `step` for every `in` and sends the count; `reset` goes back to `start`. |
| `math.accumulator` | IN `in` (`Double`), `reset`, OUT `sum` | – | The running sum; `reset` sets it to 0. |
| `math.int-to-double`, `math.to-int` | IN `in`, OUT `out` (`error` for `to-int`) | – | Converts (`to-int` rounds; `error` for a value out of range). |
| `logic.compare`, `logic.compare-text` | IN `a`, `b`, OUT `out` (`Boolean`) | `operator` (`EQ` default, `NE`, `LT`, `LE`, `GT`, `GE`) | Compares numbers or texts. |
| `logic.and`, `or`, `xor` | IN `a`, `b`, OUT `out` | – | Boolean operators. |
| `logic.not` | IN `in`, OUT `out` | – | Negation. |
| `logic.if`, `logic.if-number` | IN `condition`, `value`, OUT `then`, `else` | – | Sends a `value` on `then` or `else`, by the latest `condition` (none yet counts as false). |
| `logic.filter`, `logic.filter-number` | IN `condition`, `value`, OUT `out` | – | A `value` passes while the latest `condition` is true. |

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
