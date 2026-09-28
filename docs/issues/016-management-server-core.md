---
id: 016
title: ManagementServer core
milestone: M4
status: open
assignee:
depends_on: [002, 012, 014, 015, 022]
architecture: ["7", "14.1"]
---

# 016 – ManagementServer core

## Context
The ManagementServer is the central control instance. CLI and WebUI talk only to it. It manages engines via their Management API, the Repository via its API, adds remote routers, and restores engines and fabrics after a system restart.

## Scope
- Module `management-server` with gRPC API (`proto/cringle/management/v1/`) covering: machines/daemons, engines (create, start, stop, list, status), remote routers, repository operations (proxy), logs query (fan-out to engines).
- Registry of known machines and daemons (persisted), including the mapping machine park → responsible Repository.
- Restart recovery: on start, instruct daemons to start registered engines and restore fabrics.
- REST facade for the WebUI is **out of scope** here; design the internal service layer so it can be added.

## Out of scope
Deployment logic (017), fallback ManagementServer (open point), user management and rights (open), WebUI.

## Design notes
- Authentication: mTLS between machines; users authenticate with tokens via the user management from issue 022 (gRPC interceptor).
- The ManagementServer knows which Repository is responsible for which machine park/engines (configurable mapping, default: one repository). It resolves plugin trust status from the responsible repository and hands it to the engine at deploy time (see 017).

## Acceptance criteria
- [ ] End-to-end test: management server creates an engine through a daemon and queries its status.
- [ ] After restarting the management server and daemon, engines and fabrics come back.

## Notes / Findings

## Result
