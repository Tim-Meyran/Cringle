---
id: 015
title: Repository service
milestone: M3
status: open
assignee:
depends_on: [002, 005, 006]
architecture: ["8", "17"]
---

# 015 – Repository service

## Context
The Repository is a standalone service, managed by the ManagementServer via an API. It stores Projects and Plugins with versions and hashes, schemas, and the central trust status (`trusted`/`untrusted`) of plugins, and offers a download endpoint.

## Scope
- Module `repository`, gRPC service (`proto/cringle/repository/v1/`): publish Project/Plugin, list versions, get metadata, set plugin trust status, download.
- Storage on disk plus metadata index; hash computed and stored on publish.
- Projects are immutable after publication: re-publishing the same version is rejected.
- Download endpoint with hash in metadata; client library implementing the `PackageSource` interface from 006.
- Validation on publish using `packaging` (005).

## Out of scope
ZIP import/export between environments (explicitly not planned), UI.

## Design notes
- The plugin trust status is the only place it is set (chapter 17, rule 5).
- The repository can be addressed from other machine parks (mTLS, 013). Which repository is responsible for which engine is decided by the ManagementServer (see 016/017); the Repository itself only answers for its own content.

## Acceptance criteria
- [ ] Publish, list, download and hash check work end to end.
- [ ] Immutability is enforced with a test.
- [ ] Trust status changes are persisted and returned with plugin metadata.

## Notes / Findings

## Result
