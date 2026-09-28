---
id: 002
title: Protocol foundation - common proto types
milestone: M0
status: done
assignee: Tim-Meyran
depends_on: [001]
architecture: ["18", "5", "8.4"]
---

# 002 – Protocol foundation: common proto types

## Context
All components talk gRPC with mTLS (chapter 18). Before any service is defined, shared message types and conventions are needed.

## Scope
Create under `proto/cringle/common/v1/`:
- Identifier types: `EngineId`, `MachineId`, `FabricId`, `BlockId`, `ProjectRef`, `PluginRef` (name + version).
- `Version` and `VersionRange` (npm-style, see chapter 8.4) representation.
- `Hash` (algorithm + digest).
- `CertificateInfo` (fingerprint, subject, validity) – no private key material ever.
- Standard error/status model (`CringleError` with code, message, details) and pagination conventions.
- Health/heartbeat payload: a small `EngineHeartbeat` (engine id, timestamp, fabric states, a few metrics; see 4.2, 15.2).
- Proto style guide in `spec/proto-style.md`: naming, versioning of packages (`v1`), backward-compat rules.

## Out of scope
Service definitions (Router, ManagementServer, Repository, Engine management API) – they are added by the issues implementing those components.

## Design notes
- Keep the messages minimal; only add what is needed by 007, 012, 013.
- Certificate handling is done in 013; here only the descriptive message.

## Acceptance criteria
- [x] Protos compile via the build from issue 001 and generate Kotlin classes.
- [x] `buf lint` (or equivalent) passes.
- [x] `spec/proto-style.md` exists.

## Notes / Findings
- Created modular `.proto` files in `proto/cringle/common/v1/` following standard Buf rules and proto3 best practices: `identifiers.proto`, `version.proto`, `hash.proto`, `cert.proto`, `error.proto`, `pagination.proto`, and `heartbeat.proto`.
- Added `buf.yaml` in root referencing the proto modules.
- Replaced the initial dummy proto with comprehensive unit tests in `CommonProtoTypesTest.kt` verifying serialization and Kotlin DSL builder usage for all generated types.

## Result
- Defined common proto types in `proto/cringle/common/v1/`:
  - `identifiers.proto`: `EngineId`, `MachineId`, `FabricId`, `BlockId`, `ProjectRef`, `PluginRef`
  - `version.proto`: SemVer `Version` and npm-style `VersionRange`
  - `hash.proto`: `Hash` and `HashAlgorithm`
  - `cert.proto`: `CertificateInfo` (without private key data)
  - `error.proto`: `CringleError`
  - `pagination.proto`: `PaginationRequest`, `PaginationResponse`
  - `heartbeat.proto`: `EngineHeartbeat`, `FabricStateSummary`, `FabricLifecycleState`, `EngineMetrics`
- Created Protocol Buffer style guide in `spec/proto-style.md` and `buf.yaml`.
- Added unit tests in `kotlin/common/src/test/kotlin/cringle/common/CommonProtoTypesTest.kt`.
- Verified with `./gradlew build`.

