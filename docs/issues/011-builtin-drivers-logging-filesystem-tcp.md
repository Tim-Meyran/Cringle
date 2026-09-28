---
id: 011
title: Built-in drivers - logging, filesystem, TCP
milestone: M2
status: open
assignee:
depends_on: [003, 009, 010]
architecture: ["11", "16.1"]
---

# 011 – Built-in drivers: logging, filesystem, TCP

## Context
The engine ships built-in drivers. **Decided:** every driver exists exactly once per engine; blocks use the corresponding engine-wide instance (this also lets e.g. TCP detect port conflicts early). Plugins can add further drivers later.

## Scope
- Driver registry in the engine: exactly one instance per driver type, lookup by type, lifecycle. Per-block separation (paths, log tags, quotas) is done by the driver based on the calling block's identity, which is passed with each call or via a block-bound handle.
- **Logging driver:** unified, non-file-based logging per engine; entries tagged with fabric and block **[Zu bestätigen]**; local persistent store; optional file logging into the per-fabric/per-block log dir.
- **Filesystem driver:** access restricted to the block's isolated paths and binaries.
- **TCP driver**: port registry, early conflict detection, TCP tether type.
- Retry details hidden from the block as far as possible.

## Out of scope
Serial driver, DWH driver, user management driver, LoggingCollector (later issues), tether-driver for cross-engine (020).

## Design notes
- Driver isolation level is part of the driver type description (from 003).
- Path traversal must be impossible through the filesystem driver.

## Acceptance criteria
- [ ] Two blocks requesting the same port get a deterministic, clear error.
- [ ] Logs can be queried by fabric and block.
- [ ] Filesystem escape attempts are blocked (tested).

## Notes / Findings

## Result
