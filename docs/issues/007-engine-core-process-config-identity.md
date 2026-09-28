---
id: 007
title: Engine core - process, config and identity
milestone: M1
status: open
assignee:
depends_on: [001, 002]
architecture: ["4.4", "5", "19"]
---

# 007 – Engine core: process, config and identity

## Context
Each engine is its own JVM process, started with its ID and (optionally) name as startup arguments. It creates its own config under `~/.cringle/engines/<id>` and its own certificate with a persistent key pair, and is configured afterwards via the Management API.

## Scope
- Engine main class and CLI arguments (`--id`, `--name`, `--home`).
- Config directory handling `~/.cringle/engines/<id>` (config file with local registry address, etc.); `CRINGLE_HOME` override for tests.
- Identity: generate a persistent key pair and a self-signed certificate on first start; reuse on later starts. Identity stays stable across certificate renewals (renewal process itself is an open point, so only expose a `renew()` hook that keeps the key pair).
- Engine Management gRPC service skeleton (status, configure); definition in `proto/cringle/engine/v1/`.
- Graceful shutdown.

## Out of scope
Fabric execution (009), registration with the Router (012), trust decisions (013).

## Design notes
- Private keys must be stored with restrictive file permissions and never leave the engine.
- The Management API is mTLS-protected; until 013 exists, allow a clearly labeled insecure dev mode behind an explicit flag.

## Acceptance criteria
- [ ] Engine starts twice with the same ID and reuses config and identity.
- [ ] Two engines with different IDs on one machine do not interfere.
- [ ] Integration test starts the engine process and queries status over gRPC.

## Notes / Findings

## Result
