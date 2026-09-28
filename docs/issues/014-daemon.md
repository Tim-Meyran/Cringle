---
id: 014
title: Daemon
milestone: M4
status: open
assignee:
depends_on: [007, 012]
architecture: ["4.1", "4.3", "4.4"]
---

# 014 – Daemon

## Context
One daemon per machine starts at system boot and is required for the ManagementServer to create and start engines. It may run in the same process as the Router.

## Scope
- Module `daemon` with gRPC service: create engine (registers in local registry, allocates ID), start, stop, restart, list, delete.
- Starts engine JVM processes with ID and name arguments; supervises them.
- After a restart, starts all registered engines when told by the ManagementServer.
- Optional combined mode running the Router in the same process.
- Service installation helpers/documentation for systemd and Windows service (documentation is sufficient).

## Out of scope
Rights per machine/daemon beyond mTLS identity (user management is open).

## Design notes
- Steps of engine creation follow chapter 4.4.

## Acceptance criteria
- [ ] Integration test creates, starts and stops an engine via the daemon API.
- [ ] Killed engine process is detected and reported.

## Notes / Findings

## Result
