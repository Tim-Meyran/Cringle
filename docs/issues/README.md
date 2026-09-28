# Cringle Issues

This folder contains the implementation tasks for Cringle. Each issue is one Markdown file and is meant to be picked up by an autonomous agent (or a human) and implemented independently.

The single source of truth for design decisions is [`../Architecture.md`](../Architecture.md). Issues reference its chapters; if an issue and the architecture disagree, the architecture wins and the issue must be corrected.

## Rules for agents

The full agent workflow (claiming via branch, pull requests, conventions, definition of done) is in [`../../AGENTS.md`](../../AGENTS.md). In short:

1. **Pick an issue** with `status: open` whose `depends_on` issues are all `done` (`python3 scripts/next-issue.py` lists them). Prefer the lowest milestone, then the lowest number.
2. **Claim it** by pushing the branch `issue/NNN-slug` and setting `status: in-progress` and `assignee` in the frontmatter in its first commit.
3. **Read** [`../decisions.md`](../decisions.md) and the referenced architecture chapters completely. Decisions there override open proposals in the architecture. Do not implement anything from chapters marked `[Offen]` (Architecture.md, chapter 30) unless the issue explicitly says so.
4. **Stay in scope.** Only do what is under *Scope*. Anything under *Out of scope* belongs to another issue. If you find missing pieces, add a note under *Notes / Findings* or create a new issue file; do not silently expand the scope.
5. **Points marked `[Zu bestätigen]`** in the architecture are proposals. Implement them as described, but isolate them so they are easy to change, and list them in *Notes / Findings*.
6. **Meet every acceptance criterion** and include tests. Then set `status: done` and fill in *Result*.
7. **Never edit `Architecture.md`** as part of an issue. Propose changes under *Notes / Findings* instead.
8. **Language:** issues and code comments are English, code identifiers are English. The architecture document is German.
9. Keep the language-independent parts (`proto/`, specs) strictly separate from the Kotlin implementation (see Architecture, Part A vs. Part B).

## Statuses

`open` → `in-progress` → `done` (or `blocked`, with the reason in *Notes / Findings*). `deferred` issues are postponed on purpose and must not be picked up.

## Roadmap

Milestones follow Architecture.md, part C (chapter 25). Because of dependencies, the schema core and package format basics (004, 005) are already part of M1.

| # | Milestone | Title | Depends on |
|---|---|---|---|
| 001 | M0 | Repository layout and build setup | – |
| 002 | M0 | Protocol foundation: common proto types | 001 |
| 003 | M0 | Contract API: Block, BlockProvider, Driver, Tether | 001 |
| 004 | M1 | Schema system | 001, 003 |
| 005 | M1 | Package format and manifests (Project, Plugin) | 001, 004 |
| 007 | M1 | Engine core: process, config and identity | 001, 002 |
| 023 | M1 | Testkit for blocks and plugins | 003, 005 |
| 008 | M1 | Classloading per provider and fabric instance | 003, 005, 007, 023 |
| 009 | M1 | Fabric and block runtime | 003, 004, 008, 023 |
| 010 | M1 | Tether core and in-process tether | 003, 004, 009 |
| 011 | M2 | Built-in drivers: logging, filesystem, TCP | 003, 009, 010 |
| 006 | M3 | Versioning, dependency resolution and lock files | 005 |
| 015 | M3 | Repository service | 002, 005, 006 |
| 021 | M3 | Cringle Gradle plugin | 005 |
| 012 | M4 | Router and Registry | 002, 007 |
| 022 | M4 | Early user management (token-based) | 002, 012 |
| 014 | M4 | Daemon | 007, 012 |
| 016 | M4 | ManagementServer core | 002, 012, 014, 015, 022 |
| 017 | M4 | Deployment: placement, download and cache | 006, 009, 015, 016 |
| 019 | M4 | CLI | 016, 022 |
| 013 | M5 | Trust management and mTLS | 002, 007, 012 |
| 020 | M6 | Cross-engine tethers | 010, 012, 013 |
| 018 | M9 | Isolated (untrusted) blocks – **deferred** | 009, 010, 013 |

Numbers are identifiers, not an order; use `depends_on` to determine what can be picked up. Project decisions are recorded in [`../decisions.md`](../decisions.md).

Later issues (blue-green updates and migrations, DWH, breakpoints, shared-service discovery, WebUI, federated users, Gradle plugin publishing) will be added once their open points in Architecture chapter 30 are decided.
