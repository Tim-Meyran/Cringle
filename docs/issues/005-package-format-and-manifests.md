---
id: 005
title: Package format and manifests (Project, Plugin)
milestone: M1
status: open
assignee:
depends_on: [001, 004]
architecture: ["8", "15"]
---

# 005 – Package format and manifests (Project, Plugin)

## Context
Projects and Plugins are plain ZIP files (chapter 8.5). Projects are immutable and versioned after publication; they contain Blueprints, Schemas, Fabric Config, Binaries and Dependencies. Plugins contain BlockProviders, Drivers, Binaries, Schemas and optional update/downgrade processors.

## Scope
- `spec/package-format.md`: ZIP layout and JSON manifest formats for Project and Plugin (name, version, dependencies with npm-style ranges, blueprints, fabric config with logical roles/labels, block definitions, binaries folders, JAR list, schemas, processors).
- Blueprint JSON model: blocks, block configs, tethers (type fixed at design time), VarArg port counts, isolation wish per block.
- Kotlin module `packaging`: read, write and validate packages; compute and verify SHA-256 hash; safe unzip (zip-slip protection).
- Validation: blueprint references existing blocks/ports, tether types are supported by both ports, schemas resolve (uses issue 004).

## Out of scope
Version resolution and lock files (006), download and caching (017), repository storage (015).

## Design notes
- Blueprints have no version of their own (chapter 8.4).
- Fabric Config references engines only via logical roles/labels, never concrete engines (chapter 8.2).
- A Blueprint runs entirely in one engine (chapter 9.1); validate that a blueprint does not span engines.

## Acceptance criteria
- [ ] Spec document with a full example Project and Plugin.
- [ ] Round-trip test: build package → read → identical model.
- [ ] Hash mismatch and zip-slip attempts are rejected with tests.
- [ ] Invalid blueprints produce precise validation errors.

## Notes / Findings

## Result
