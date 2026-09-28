---
id: 009
title: Fabric and block runtime
milestone: M1
status: open
assignee:
depends_on: [003, 004, 008, 023]
architecture: ["9", "19", "22", "17"]
---

# 009 – Fabric and block runtime

## Context
A Fabric is a runtime instance of a Blueprint on exactly one engine and runs in its own thread. Blocks have their own lifecycle, optional automatic restart with a configurable retry count, and isolated runtime paths.

## Scope
- Fabric instance manager: create from Blueprint + Fabric Config + Block Configs, start, stop, destroy; each fabric in its own thread (with a coroutine scope inside).
- Working dir and logging dir per fabric; isolated runtime paths per block.
- Block lifecycle management incl. crash detection, optional auto-restart with retry count and backoff.
- Driver injection through the BlockProvider (driver factory interface; concrete drivers come in 011).
- Non-blocking execution guarantee per block execution (watchdog for blocked dispatchers, log a warning).
- Isolation level resolution (`shared` … `process`, stricter of plugin trust status and block config wish, see 018). Until 018 is implemented, a block whose resulting level is `process` **must not be started in-process**: the fabric start fails with a clear error (fail closed).
- Engine management API additions: deploy fabric, start, stop, list, status.

## Out of scope
Tether transport (010), built-in drivers (011), blue-green (later issue), untrusted process isolation (018).

## Design notes
- A JVM-level fault can take down the engine; this is accepted for trusted blocks (chapter 19).
- Keep the fabric state machine explicit (created, starting, running, stopping, stopped, failed) and expose it in the heartbeat.

## Acceptance criteria
- [ ] Sample blueprint with two blocks runs, stops and restarts cleanly.
- [ ] Crash of a block triggers restart up to the configured retries, then marks the block failed.
- [ ] Two instances of the same blueprint run concurrently without shared state.

## Notes / Findings

## Result
