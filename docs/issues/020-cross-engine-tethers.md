---
id: 020
title: Cross-engine tethers
milestone: M6
status: open
assignee:
depends_on: [010, 012, 013]
architecture: ["10", "13"]
---

# 020 – Cross-engine tethers

## Context
Blueprints can be connected via tethers across engines and across projects. Targets are resolved through the Registry and cached; after a failover the target is looked up again.

## Scope
- Tether driver for remote blueprint-to-blueprint communication over the certificate infrastructure (mTLS), with schema-based serialization.
- Address resolution through the Registry, with cache and re-lookup after connection loss.
- Explicit healthcheck loop in the driver; the block sees failover transparently.
- Design-time abstract dependency (expected schema type) and deploy-time binding to a concrete instance **[Zu bestätigen]**; only the runtime parts are required here, deploy-time binding hooks are interfaces.

## Out of scope
Final shared-service discovery model (capabilities, priorities), cross-project deploy UX.

## Acceptance criteria
- [ ] Two blueprints on two engines exchange messages over a tether.
- [ ] Killing the target engine and starting it elsewhere leads to re-resolution and recovery.
- [ ] Unencrypted connections are refused unless explicitly enabled in the blueprint (where the protocol allows the choice).

## Notes / Findings

## Result
