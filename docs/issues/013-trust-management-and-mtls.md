---
id: 013
title: Trust management and mTLS
milestone: M5
status: open
assignee:
depends_on: [002, 007, 012]
architecture: ["5", "6", "18"]
---

# 013 – Trust management and mTLS

## Context
There is no central CA. Trust is established manually: a router is added and trusted (via CLI/WebUI through the ManagementServer). Trusting a remote router transitively trusts all engines registered there. Router trust and user trust are separate.

## Scope
- Trust store in the Router (persistent): trusted router certificates/fingerprints, transitive engine trust.
- mTLS for all gRPC connections between components, with a shared TLS helper in `common`.
- API: add remote router (with fingerprint confirmation), trust, revoke, list.
- Certificate validation rules incl. stable engine identity; renewal only as hook (details are an open point, chapter 30).
- Remove the dev-mode insecure flag from 007 for all non-test configurations.

## Out of scope
User trust and federated users, certificate renewal process, fingerprint pinning vs. implicit CA decision (open).

## Design notes
- Revocation of a router revokes all engines that trusted only through it.
- Do not decide the open renewal question; document the assumptions in *Notes / Findings*.

## Acceptance criteria
- [ ] Untrusted peers are rejected; trusted peers connect.
- [ ] Transitive engine trust works and is revoked with the router.
- [ ] Tests use generated certificates only.

## Notes / Findings

## Result
