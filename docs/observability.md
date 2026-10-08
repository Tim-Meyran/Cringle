# Observability

What an operator can see of a running Cringle (Architecture chapter 16). This page grows with the M7 issues (#150).

## Metrics (#187)

Every engine answers `GetMetrics` (engine management API) with the numbers below; counters are cumulative since the fabric was
created, a reader computes rates.

| Level | Numbers |
|---|---|
| Engine | CPU load of the process (0 to 1, -1 if unknown), heap used and maximum, thread count |
| Fabric | CPU time of the fabric's thread in nanoseconds (-1 if unknown), errors (blocks and tethers) |
| Block | errors: crashes and failed starts, restarts included |
| Tether | messages (values that crossed it: messages, requests, responses, stream openings and items), bytes (tethers of bytes), errors (validation and delivery failures) |

Memory is measured per engine only, CPU per engine and per fabric: the JVM cannot attribute either to a block that shares the
thread and heap of its fabric. Per-block CPU and memory would need isolated blocks (#18). The ManagementServer reads the numbers
(`GetMetrics`, `cringle metrics`, #191: one row per engine, `--fabrics`, `--tethers`) and the heartbeat carries a few of them to the router (#190).

## Data warehouse store (#188)

Every engine has a data warehouse (`Dwh`, `<engine dir>/dwh`) for tether messages and other complex data (Architecture 16.5).
It is one store with **logical partitions** per fabric instance and block or tether, so that rights, retention and deletion work
per partition:

```
<engine dir>/dwh/<fabric>/<block|tether>/<name>/
    meta.json              the real fabric, kind and name, and the retention
    2026-10-01.jsonl       one record per line: {"t": <ISO instant>, "v": <JSON payload>, "tags": {...}}
```

- A record is appended to the day file of its timestamp (UTC). Names that are not harmless (`[A-Za-z0-9._-]{1,64}`, not starting
  with a dot) are written as a cleaned name with a hash, so that no name can leave the store; `meta.json` has the real name.
- Retention is set per partition (`maxAge`, `maxBytes`) and applied every minute by the engine (and on demand). A day file is
  the unit: files that are entirely older than `maxAge` go, then the oldest files go until the partition is within `maxBytes`;
  the newest file always stays, so a size limit is kept to `maxBytes` plus up to one day file.
- Queries take a time range and a limit and return the newest records, oldest first.

The block driver (below), the recording of tether traffic (#193) and the access of the ManagementServer (#194) build on it.

## Heartbeat (#190)

The heartbeat an engine sends to its router every few seconds carries a few important numbers (Architecture 16.2) next to the fabric
states (`EngineMetrics` of the heartbeat): CPU load of the process in percent (-1 if unknown), heap used and maximum, tether
messages per second since the previous heartbeat (0 for the first one), the number of fabrics and of running fabrics, and the
failures of blocks and tethers (cumulative). They come from the numbers of the metrics above; there is no sampling thread of its own.

The router keeps the last numbers of every engine in memory only (they are not written to `registry.json`; a heartbeat without them
keeps the last ones, an unregistered engine loses them) and returns them as `vitals` of the engine entry in `ListEngines`. The
cached engines of remote routers carry the numbers their router reported at the last refresh. The full numbers stay a pull:
`GetMetrics` of the engine (`cringle metrics`).

## DWH driver for blocks (#192)

A block that names the driver `dwh` in its `requiredDrivers` gets a `DwhDriver` (`BuiltinDriverTypes.DWH`, contract). It writes
`DwhEntry(key, value, timestamp, tags)` to the partition `(fabric, block)` of the engine's store and reads its own entries back
(`read(since, until, limit)`: the newest `limit`, oldest first). The value is JSON-like data (maps with string keys, lists, strings,
numbers, booleans, `null`); anything else is rejected with an `IllegalArgumentException`. A block cannot read the partitions of other
blocks or fabrics; the ManagementServer reads any partition (#194). An entry is stored as `{"key": ..., "value": ...}` with the
timestamp and tags of the entry. The retention of a block partition is set per partition (`SetDwhRetention`, #193).

## Recording tether traffic (#193)

Tether messages go into the DWH in two ways:

- **Per tether**, in the blueprint: a tether with a `record` object is recorded. `{"maxAge": <ms>, "maxBytes": <bytes>}` (both optional,
  both positive; `{}` records without limits) is the retention of the partition of that tether. Tethers that carry bytes (`BYTE_STREAM`,
  `TCP`, `SERIAL`) cannot have one. See `spec/package-format.md`.
- **Per fabric**, the recording mode: the engine API `SetRecording(fabric, all, default retention)` switches a running fabric to
  "record every typed tether that has no definition of its own" and back. The mode is not kept by the engine; the ManagementServer
  applies it again (#194).

What is recorded: every message, request, response and stream item after schema validation, as the JSON value the tether carries,
in the partition `(fabric, TETHER, tether id)` with the tag `kind` (`message`, `request`, `response`, `stream_item`). The tether id is
the one `GetMetrics` shows (`s.out -> d.in`). Recording never slows a tether down: records go through a bounded queue (1024) to a
writer; when it is full a record is dropped, counted, and a warning is written once to the log of the fabric.

The retention of any partition (a block's or a tether's) can be changed at run time with `SetDwhRetention(fabric, kind, name,
retention)`; a retention of 0 for age or size means no limit. The limits are applied every minute (see "Data warehouse store").
