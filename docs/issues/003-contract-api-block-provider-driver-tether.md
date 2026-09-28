---
id: 003
title: Contract API - Block, BlockProvider, Driver, Tether
milestone: M0
status: open
assignee:
depends_on: [001]
architecture: ["9", "10", "11", "22"]
---

# 003 – Contract API: Block, BlockProvider, Driver, Tether

## Context
The contract module contains the interfaces shared between engine and plugins. It becomes the narrow parent classloader (chapter 20), so it must be minimal and dependency-free.

## Scope
Kotlin module `contract` with:
- `Block` interface with lifecycle hooks (init, start, stop, destroy) and handlers for tether events.
- `BlockProvider` interface: creates block instances and receives injected drivers. No provider lifecycle methods yet (open point, chapter 30).
- `Driver` interface with lifecycle. **Decided:** drivers are unique per engine (one instance per driver type in an engine); blocks use those engine-wide instances, injected by the BlockProvider. A `DriverType` description carries the driver's isolation level.
- `DwhDriver` interface (interface only) so blocks can write entries into the data warehouse; the DWH itself is a later issue.
- Tether abstractions: tether types (sync request/response, async message, stream incl. raw byte stream), ports (with supported tether types and schema references), VarArg ports (list, fixed at start).
- Non-blocking API: suspend functions and Flow-based streams (**decided:** Kotlin coroutines, JDK 21).
- Block definition metadata model (name, schema refs, ports, required drivers, config schema) as Kotlin data classes, matching the JSON config described in 22.
- KDoc for every public type.

## Out of scope
Implementations of blocks, drivers or tethers; classloading; schema type system internals (issue 004; only reference types here).

## Design notes
- Only `kotlinx-coroutines-core` and the Kotlin stdlib may be dependencies. Keep them `api`-exposed deliberately.
- Blueprint authors never see drivers (chapter 1, principle 5, and 9.2): drivers are only for block authors.
- Mark all proposals from `[Zu bestätigen]` clearly in KDoc.

## Acceptance criteria
- [ ] Module compiles with no dependency other than stdlib and coroutines.
- [ ] A sample block and provider in tests exercise the interfaces (with fake drivers).
- [ ] Block definition metadata can be (de)serialized to/from the JSON structure from chapter 22 (serialization lives in `packaging`, only data classes here).

## Notes / Findings

## Result
