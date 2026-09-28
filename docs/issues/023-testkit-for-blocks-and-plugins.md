---
id: 023
title: Testkit for blocks and plugins
milestone: M1
status: open
assignee:
depends_on: [003, 005]
architecture: ["9", "11", "22"]
---

# 023 – Testkit for blocks and plugins

## Context
Plugin authors, and the engine tests in issues 008 and 009, need a way to test blocks without a running engine and to build small test plugins quickly.

## Scope
- Kotlin module `testkit`:
  - Fake and recording drivers (logging, filesystem in a temp dir, in-memory tether endpoints) implementing the contract driver interfaces.
  - `BlockTestHarness`: instantiate a block through its provider with fake drivers, drive its lifecycle, send messages into ports and assert on outputs, using coroutine test dispatchers.
  - `TestPluginBuilder`: builds Plugin and Project packages programmatically (using `packaging`, issue 005), including tiny compiled test classes or supplied JARs, for classloader and deploy tests.
- Documentation with an example test for a sample block.

## Out of scope
Running a full engine, integration tests across engines.

## Acceptance criteria
- [ ] A sample block is tested with the harness, including a lifecycle and a tether round trip.
- [ ] `TestPluginBuilder` produces packages that pass `packaging` validation.
- [ ] Module is usable from the Gradle plugin's sample project (issue 021).

## Notes / Findings

## Result
