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
- **An output that nothing is connected to is harmless** (#412). A message that a block sends on a port without a tether goes nowhere; the block and the fabric keep running. So `out` of `dwh.write` or `log.write`, `error` of `text.parse` and every other output are optional parts of a flow. Every block of the library is wrapped for this (`QuietOutputs`); requests, streams and other failures are not affected.

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
| `flow.trigger`, `flow.trigger-string`, `-number`, `-int`, `-boolean` | IN `trigger` (`Any`), VarArg IN `in` (`Any`), VarArg OUT `request` (`REQUEST_RESPONSE`, `Any`), VarArg OUT `out` (`Any`), OUT `done` (`Int`) | – | Collects values for one trigger. Every slot `i` has `in[i]` (values that arrive on their own, the block holds the latest), `request[i]` (the block **pulls** with it) and `out[i]`. For every message at `trigger` it asks every connected `request[i]` (an input block answers with its value; the request carries the trigger value); a slot whose `request[i]` is not connected sends its held `in[i]` (nothing if there is none). The results go out 1:1 on `out[i]`, then `done` sends a new trigger signal (the number of triggers so far) that can start the next step of a flow. A slot that cannot be pulled does not stop the others. The numbers of `in`, `request` and `out` have to be equal (`varArgCounts` of the blueprint). An output that nothing is connected to (`out[i]`, `done`) is skipped. **`flow.trigger` is generic** with the type parameter `T` (bound `Any`): `in`, `request` and `out` carry `T`, which the blueprint binds per block (in the editor: *Type T* in the properties of the block), so typed blocks connect to it without a cast; unbound it is `Any`. The variants `flow.trigger-string`, `-number`, `-int`, `-boolean` are fixed to one type (kept for existing blueprints); `trigger` takes any signal and `done` is an `Int` in all of them. A request and its answer are checked against the type of the port, so a block of type `String`, `Double`, `Int` or `Boolean` (a typed variant, or `flow.trigger` with that `T`) asks with a neutral value of its type (an empty text, 0, `false`; the input blocks ignore it) and an `Any` trigger or one of another type with the trigger value. |
| `input.json` | as `input.text`, OUT `out` (`Any`) | `value` (a text with JSON) | A JSON value (object, list, text, number ...) from the configuration, read at start. It answers a request at `get` with the structure and sends it on `out` for every message at `trigger`. Behind the `source` of an `http.response` it is the body of the answer of an `http.endpoint` (sent as JSON). |
| `input.text`, `input.number` | IN `get` (`REQUEST_RESPONSE`, `Any`), IN `trigger` (`Any`), OUT `out` (`String`, `Double`) | `value` | A value from the configuration. It answers a request at `get` (that is how `flow.trigger` pulls it) and sends the value on `out` for every message at `trigger`. |
| `flow.once` | IN `in`, `reset` (`Int`), OUT `out` | – | Only the first value passes; `reset` lets the next one pass. |
| `flow.log-any` | IN `in` | `level`, `prefix` | Like `flow.log`, the value as JSON text. |
| `logic.if-any`, `logic.filter-any` | as `logic.if`, `logic.filter` | – | For values of any type. |
| `data.as-string`, `-number`, `-int`, `-boolean` | IN `in` (`Any`), OUT `out` (typed), `error` | – | The value as that type, or the reason on `error` when it is of another kind (`as-int` takes whole numbers only). |

### Log and data warehouse: `log.*`, `dwh.*`

| Block | Ports | Configuration | What it does |
|---|---|---|---|
| `http.endpoint` | OUT `handler` (`REQUEST_RESPONSE`, request `HttpRequest`, response `HttpResponse`) | `path` (with `{name}` segments, for example `/orders/{id}`), `method` (default `GET`), `auth` (default false), `timeoutMs` (default 30000, at most 600000), `contentType` (the `Content-Type` of a text answer, for example `text/csv`) | One HTTP route on the **API port of the engine** (setting `api.port`, default 8080, HTTPS unless `api.tls` is `off`; opened with the first route; two engines of a machine that have endpoints need different ports). A request becomes a request on `handler`: `{method, path, params, query, headers, body, user}` (`body` is the text of the body, up to 1 MiB; `user` is the name of the caller of a protected route). The answer of the flow is an `HttpResponse` (below): `status`, `headers` and `body` (every key optional: 200, no headers, empty body); a text body goes out as it is, anything else as JSON with `application/json`. With `auth` the request needs `Authorization: Bearer <token>` of a user of the machine who may use applications (role `END_USER` or `ADMIN`; the tokens of `<home>/management/users.json`); a missing or wrong token is `401`, a user without the role `403`. The server itself answers `404` (no route), `405` (with `Allow`), `413`, `503` (the handler is not connected) and `504` (no answer in time). A second block with the same method and path (also with other parameter names) in the engine fails at its start. The route lives from the start of the block to its stop. The block declares the API port as an **exclusive resource**: a project with an endpoint is updated by stopping the old fabric first and then starting the new one (a redeploy has a short pause), because the new fabric could not take the route while the old one serves it. |
| `http.response` | IN `request` (`REQUEST_RESPONSE`, request `Any`, response `HttpResponse`), IN `status` (`Int`), `contentType` (`String`), `headers` (`Any`, a map of texts), `body` (`Any`), all `PULL` | `status` (default 200), `contentType`, `headers` (texts), `body` (a text) | Answers the request of an `http.endpoint` with a response that is **composed of its parts**: when the request comes the block **asks** the ports `status`, `contentType`, `headers` and `body` (a pull tether; the request is the context of the question), and a port that nothing is connected to gives the value of the properties as the default. The `headers` of a port are added to those of the properties and win over them. A `body` that is not a text goes out as JSON. The `body` of the properties is a template: `{}` is replaced by what this block got as its request (a text as it is, anything else as JSON). A `status` that is no HTTP status (100 to 599) fails the request. |
| `http.request-field` | type parameter `T` (bound `Any`: the type of the part that `next` gets, a `String`, or what `json` gives); IN `request` (`REQUEST_RESPONSE`, `HttpRequest` and `HttpResponse`), OUT `next` (`REQUEST_RESPONSE`, `T` and `HttpResponse`) | `field` (`body`, `method`, `path`, `user`, `params.<name>`, `query.<name>`, `headers.<name>` in lower case), `json` (parse the part as JSON) | Takes one part of the request of an `http.endpoint` and asks the block at `next` with it; the answer of `next` is its answer. A part that the request does not have is an empty text. |
| `http.request-value` | OUT `out` (`PULL`, `Any`) | `field` (as `http.request-field`), `json` | The answer of a pull tether with one part of the HTTP request that it is asked with (the context of the pull goes through the whole chain of pulls): connect it to a port that asks, for example to a variable of `text.format` or to the `body` of `http.response`. |
| `log.write` | IN `in` (`Any`), OUT `out` (`Any`) | `level` (`DEBUG`, `INFO` default, `WARN`, `ERROR`), `prefix`, `template` (`{}` takes the value) | Writes the value to the log of the fabric (`cringle logs`) with the **configured level**: a text as it is, any other value as JSON. The value goes on unchanged on `out`, so a log step can sit in the middle of a flow. `flow.log` and `flow.log-any` stay for the flows that exist. |
| `dwh.write` | IN `in` (`Any`), OUT `out` (`Any`) | `key`, `tags` (optional, texts) | Writes the value as an entry of the own partition of the block in the data warehouse of the engine (`docs/observability.md`) and sends it on. What it stores is read through the CLI and the WebUI (`cringle dwh query`). |
| `dwh.store` | IN `in`, `read` (`Any`), OUT `out`, `entries` (`Any`), `error` (`String`) | `key`, `tags`, `limit` (default 100), `lastMs` | Writes like `dwh.write` and **reads back**: every message at `read` sends the entries as a list on `entries`, oldest first (objects `{key, value, timestamp, tags}`; `lastMs` keeps the last n milliseconds); `error` says so when the driver cannot read. |

**Pull (get) and the function blocks (#428).** A port can support several tether types, and the editor chooses the first that the two ports of a connection share (`MESSAGE`, then `REQUEST_RESPONSE`, then `PULL`), so a connection to a port that only asks is a pull tether by itself; for ports that share several types the properties of the connection say which one it is. The blocks of the themes `text`, `math`, `logic` and `data` that are **pure functions** (no state, no trigger) support `MESSAGE` and `PULL` on every port: pushed, they send their result on; asked for it at an output, they ask their inputs (the context goes upstream), apply the function and answer. `input.text`, `input.number` and `input.json` can be asked at `out` too. A value is computed when it is asked for, nothing is cached. The blocks that have state (`math.counter`, `math.accumulator`, `logic.if`, `logic.filter`, `flow.*`, `time.*`, `file.*` ...) do not support `PULL`.

**The typed HTTP API (#421).** The request that the flow gets is an `HttpRequest` and the answer it gives is an `HttpResponse`, both records of the schema `cringle.stdblocks`:

| Type | Fields |
|---|---|
| `HttpRequest` | `method`, `path`, `body` (texts), `params`, `query`, `headers` (maps of texts; header names in lower case), `user` (optional text: the name of the caller of a protected route) |
| `HttpResponse` | `status` (optional `Int`, default 200), `headers` (optional map of texts), `body` (optional, `Any`: a text as it is, anything else as JSON) |

A port has a type for the request and one for the response (`responseSchema`, `spec/tether.md`): the editor shows `HttpRequest → HttpResponse` at `handler`, and a tether is only accepted when what the receiver answers fits what the sender expects. So the answer of an endpoint is always an `HttpResponse`: **`http.response` builds it**, also for a constant (`input.text`, `input.json` behind its `source`). A block that answers `Any` cannot be connected to `handler` directly.

Example: `http.endpoint` (`/hello/{name}`) → `http.request-field` (`params.name`) → `http.response` (`Hello {}!`); or `http.endpoint` → `http.response` → `input.json` at `source`.

**A block reads its own partition only** (the driver gives no access to the partition of another block), so reading and writing have to be in the same block: that is why there is one block `dwh.store` and no separate read block.

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
| `text.format` | IN `args` (a VarArg port with one slot per name, `Any`; `MESSAGE` and `PULL`), IN `trigger` (`Any`), OUT `out` (`String`; `MESSAGE` and `PULL`) | `template` (with `{name}`; `{{` and `}}` are braces), `variables` (the names, one per line in the editor; default `value`) | A text from a template with **named variables**. The block has one slot at `args` for every name (the editor shows the names at the slots and keeps the number of slots equal to the number of names). It works both ways: a value that arrives at a slot is kept and, as soon as every variable has a value, the text goes out on `out`; a message at `trigger` sends the text with the known values; and when `out` is asked for its value (a pull tether) the block asks every slot that is connected as a pull (the context goes on), uses the kept value for any other slot, and answers the text. A value that is no text is written as JSON. |
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
