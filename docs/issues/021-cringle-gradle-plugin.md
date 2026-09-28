---
id: 021
title: Cringle Gradle plugin
milestone: M3
status: open
assignee:
depends_on: [005]
architecture: ["8", "8.5"]
---

# 021 – Cringle Gradle plugin

## Context
Plugin and Project authors build their packages with Gradle (Kotlin). A simple Cringle Gradle plugin turns a normal Kotlin/Gradle project into a Cringle Plugin or Project ZIP (chapter 8.5), so nobody assembles ZIP files and manifests by hand.

## Scope
- Gradle plugin module (included build, e.g. `kotlin/gradle-plugin`), plugin IDs `cringle.plugin` and `cringle.project`.
- DSL for metadata: name, version, dependencies (with npm-style ranges), block providers, block definitions, drivers, schemas folder, binaries folder; for projects additionally blueprints and fabric config folders.
- Tasks: `cringlePackage` (produces the ZIP with JARs of the runtime classpath, JSON manifests, binaries; uses `packaging` from issue 005), `cringleValidate` (validates manifest, blueprints, schemas), and a placeholder `cringlePublish` that is completed after issue 015 exists.
- Contract module (`contract`) is added as `compileOnly` dependency, not packaged into the plugin (it is provided by the parent classloader).
- Functional tests with Gradle TestKit.

## Out of scope
Publishing to the Repository (follow-up once 015 is done), IDE support, project scaffolding templates.

## Design notes
- Reuse the `packaging` library for manifest writing and validation so that build-time and runtime agree.
- Build output must be reproducible (stable file order and timestamps).

## Acceptance criteria
- [ ] A sample plugin project applies `cringle.plugin` and produces a valid Plugin ZIP that `packaging` reads.
- [ ] A sample project applying `cringle.project` produces a valid Project ZIP.
- [ ] `cringleValidate` fails with clear messages on invalid manifests.
- [ ] Two builds of the same sources produce byte-identical ZIPs.

## Notes / Findings

## Result
