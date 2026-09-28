---
id: 018
title: Isolated (untrusted) blocks
milestone: M9
status: deferred
assignee:
depends_on: [009, 010, 013]
architecture: ["17", "21"]
---

# 018 – Isolated (untrusted) blocks

## Context
**Deferred:** the child-JVM implementation is postponed (decision by the project owner). Only the isolation resolution rule and the fail-closed behavior below are implemented earlier as part of issue 009; this issue stays for later.

Blocks from untrusted plugins run in their own child JVM process started by the engine, communicating over a local IPC tether. The effective isolation is the maximum of the plugin's trust status and the block config wish; authors can tighten but never loosen.

## Scope
- Child process launcher containing only the provider's classloader **[Zu bestätigen]**.
- Local IPC tether type (Unix domain socket / named pipe) with schema-based serialization across the process boundary.
- Isolation resolution rule: the stricter of the plugin trust status (set centrally by the operator in the Repository: `trusted`/`untrusted`) and the block config wish (set by the blueprint author) wins. Levels are ordered from `shared` (normal fabric thread) to `process` (own child JVM). The author can tighten, never loosen. Trust status is passed by the ManagementServer at deploy time.
- Resource limits via cgroups (Linux) and Job Objects (Windows) where available; restricted filesystem access so the block cannot reach neighbor blocks or the engine certificate.
- Lifecycle monitoring: crash via exit code or heartbeat timeout.

## Out of scope
Container-based isolation, other languages.

## Design notes
- Provide a clean abstraction for limits so platform-specific code is separate and gracefully degrades with a logged warning.

## Acceptance criteria
- [ ] Untrusted sample block runs in a separate process and exchanges messages with a trusted block.
- [ ] `System.exit()` in the untrusted block does not stop the engine.
- [ ] Block cannot read the engine's key material (tested on the supported OS).
- [ ] Effective isolation rule has unit tests.

## Notes / Findings

## Result
