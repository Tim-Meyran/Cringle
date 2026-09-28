---
id: 022
title: Early user management (token-based)
milestone: M4
status: open
assignee:
depends_on: [002, 012]
architecture: ["6", "18"]
---

# 022 – Early user management (token-based)

## Context
User management belongs to the Registry (chapter 6.1), not the Repository. It manages users and groups of CLI/WebUI and the end users of applications built with Cringle in the same user space, distinguished by rights and roles. CLI and ManagementServer need authentication early, so a first, deliberately small version is built now. During development, users authenticate with tokens.

## Scope
- User model: users, groups, roles; machine identities remain certificate-based (mTLS, 013).
- Token authentication: create, list, revoke, expiry; tokens are stored only as hashes; a bootstrap admin token is generated on first start and shown once.
- Username/password support is optional in this issue (interface and storage prepared; implementation only if simple).
- Roles for the first version: `admin`, `operator` (start/stop, deploy), `viewer` (read-only), plus a role for application end users without management rights. Model rights so that scopes (machine/daemon, project, fabric, framework functions; chapter 6.3) can be added later; the first version applies roles globally.
- gRPC service in `proto/cringle/user/v1/` and a reusable server-side auth interceptor used by ManagementServer (016) and Repository (015).
- Small `UserManagementDriver` interface in `contract` so that blocks can use user management as an engine-wide driver (implementation is minimal: authenticate token, check role).

## Out of scope
Federated users of remote registries (open point), fine-grained rights per object, OAuth/OIDC, WebUI.

## Design notes
- The user store lives in the Router/Registry storage (issue 012). Keep it a library usable from the ManagementServer.
- Authentication errors must not reveal whether a user exists.
- Never log tokens.

## Acceptance criteria
- [ ] Bootstrap admin token works once; created tokens can be revoked and expire.
- [ ] Interceptor rejects missing, invalid and expired tokens and enforces role checks (tested per role).
- [ ] Tokens are not recoverable from storage.

## Notes / Findings

## Result
