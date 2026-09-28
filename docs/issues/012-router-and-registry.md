---
id: 012
title: Router and Registry
milestone: M4
status: open
assignee:
depends_on: [002, 007]
architecture: ["4.2", "5"]
---

# 012 – Router and Registry

## Context
Each machine runs one Router with a Registry that stores which fabrics run on which engines, knows remote routers (and caches their engines), and receives periodic heartbeats. Trust store and user management live here too, but are covered by 013 and later issues.

## Scope
- Module `router`, gRPC service in `proto/cringle/router/v1/`: engine register/unregister, heartbeat, fabric lookup, list engines, add/remove remote router.
- Persistent registry storage (embedded, e.g. SQLite or file-based; choose and document).
- Engines register on startup with their local registry address from the engine config; heartbeat handling with timeouts marking engines as unreachable.
- Remote router federation: query remote routers and cache their engines.
- Engine client code in the engine module for registering and sending heartbeats.

## Out of scope
Trust decisions and mTLS enforcement (013), user management, ManagementServer.

## Design notes
- Router and Daemon are logically separate but may share a process (4.3); keep the Router usable as a library.

## Acceptance criteria
- [ ] Engine registers, sends heartbeats, appears in list; disappears from "reachable" after missed heartbeats.
- [ ] Fabric lookup returns correct engine after registry restart.
- [ ] Two routers can exchange engine information.

## Notes / Findings

## Result
