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

The block driver (#192), the recording of tether traffic (#193) and the access of the ManagementServer (#194) build on it.
