---
id: 006
title: Versioning, dependency resolution and lock files
milestone: M3
status: open
assignee:
depends_on: [005]
architecture: ["8.4"]
---

# 006 – Versioning, dependency resolution and lock files

## Context
Dependencies carry concrete versions and npm-style compatibility ranges. A lock concept fixes the versions resolved at deploy time.

## Scope
- Semantic version parsing and comparison; npm-style ranges (`^`, `~`, exact, comparators).
- Dependency resolver over an abstract `PackageSource` interface (implemented later by the Repository client): resolves Projects and Plugins including transitive dependencies; reports conflicts and cycles.
- Lock file model (JSON): resolved versions plus hashes; deterministic output.
- Library in module `packaging` (or a new `versioning` module, keep it dependency-light).

## Out of scope
Network access, repository implementation, actual download.

## Design notes
- Same package must resolve to a single version per lock (chapter 15: only one version is unpacked per Project/Plugin).
- Resolution must be deterministic and independent of ordering.

## Acceptance criteria
- [ ] Range semantics covered by table-driven tests.
- [ ] Conflict and cycle reports are readable and tested.
- [ ] Lock file is reproducible byte for byte.

## Notes / Findings

## Result
