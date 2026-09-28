---
id: 017
title: Deployment - placement, download and cache
milestone: M4
status: open
assignee:
depends_on: [006, 009, 015, 016]
architecture: ["14", "15"]
---

# 017 – Deployment: placement, download and cache

## Context
Projects are deployed manually through the ManagementServer. It resolves logical roles/labels of the Fabric Config to concrete engines (placement), the engine downloads and unpacks artifacts into the shared machine cache under `~/.cringle/`, and the registry records where each fabric runs.

## Scope
- Deploy command in the ManagementServer: resolve dependencies and write the lock (006), placement by roles/labels, send deploy to engines, record fabric locations in the registry.
- Engine-side artifact cache: download from Repository, hash verification, unpack only one version per Project/Plugin, shared by all engines on the machine (with locking for concurrent engines).
- Binaries unpacked and managed automatically, deleted later.
- Cleanup: remove unused versions after X days (configurable) or on ManagementServer command.
- Restart of a fabric with a new version is a stop/start (blue-green comes later).
- Resolve the plugin trust status through the Repository responsible for the target engine's machine park and pass it in the deploy command; the engine never asks a repository for trust status itself.

## Out of scope
Blue-green, update/downgrade processors, rollback.

## Design notes
- Reference counting or usage tracking is required for cleanup (the exact rules are an open point; propose a simple one).

## Acceptance criteria
- [ ] Deploying a sample Project places fabrics on the right engines and they run.
- [ ] Two engines on one machine share one unpacked version.
- [ ] Corrupt download (hash mismatch) is rejected and cleaned up.
- [ ] Cleanup removes unused versions and keeps used ones.

## Notes / Findings

## Result
