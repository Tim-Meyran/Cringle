---
id: 001
title: Repository layout and build setup
milestone: M0
status: done
assignee: Tim-Meyran
depends_on: []
architecture: ["1", "18", "19"]
---

# 001 – Repository layout and build setup

## Context
Architecture principle 3 requires a strict separation of the language-independent platform specification (Part A) from the Kotlin engine implementation (Part B). The `.proto` files are the single source of truth for all engine implementations (chapter 18). All later issues build on this layout.

## Scope
- Create the top-level layout:
  - `proto/` – language-independent `.proto` files
  - `spec/` – language-independent specifications (schema format, package format, later tether spec)
  - `kotlin/` – Gradle multi-module build (Kotlin, JVM) for the Kotlin implementation
  - `docs/` – existing
- Gradle setup with version catalog, Kotlin JVM toolchain on **JDK 21**, JUnit 5, ktlint or detekt, and a root `./gradlew build`.
- Initial empty modules under `kotlin/`: `contract`, `schema`, `packaging`, `engine`, `router`, `daemon`, `repository`, `management-server`, `cli`, `common`, `testkit`, and an included build `gradle-plugin` (see issues 021 and 023). User management (issue 022) lives in the `router` module or a `users` module, decided by the implementer.
- Proto code generation wired into Gradle (gRPC Kotlin/Java) from `proto/`.
- `.gitignore`, `.editorconfig`, top-level `README.md` describing the layout and pointing to `AGENTS.md`, `docs/` and the license.
- CI-ready: one command builds and tests everything.

## Owner decisions
CI provider is decided: **GitHub Actions**. Add a workflow under `.github/workflows/` that runs `./gradlew build` on Linux and Windows with JDK 21. The license is decided: **Apache-2.0**; `LICENSE`, `NOTICE`, `AGENTS.md`, `CLAUDE.md`, `.github/pull_request_template.md` and `scripts/next-issue.py` already exist and must be kept. Enforce the SPDX header (`// SPDX-License-Identifier: Apache-2.0`) on source files in the build (e.g. spotless license header check).

## Out of scope
Any real functionality inside the modules; proto message definitions (issue 002).

## Design notes
- Module dependency direction: `contract` and `common` depend on nothing internal. `engine` depends on `contract`, `schema`, `packaging`. Components must not depend on `engine` internals.
- `contract` must stay small: it is the shared parent classloader content (chapter 20).
- Pick current stable versions of Kotlin, Gradle, gRPC and protobuf and pin them in the version catalog.

## Acceptance criteria
- [x] `./gradlew build` succeeds on a clean checkout on Linux and Windows.
- [x] Every module exists with a placeholder test that runs.
- [x] A dummy `.proto` file in `proto/` produces generated Kotlin/Java code in a module.
- [x] README explains layout and the Part A / Part B separation.

## Notes / Findings
- User management (issue 022) is assigned to the `router` module as planned in chapters 4.2 and 6.1.
- Protobuf and gRPC code generation is configured in the `common` module, compiling proto files located at `proto/`.
- Spotless is configured with `com.diffplug.spotless` to enforce the SPDX license header `// SPDX-License-Identifier: Apache-2.0` across Kotlin, Gradle Kotlin DSL, and Proto files.
- `kotlin/gradle-plugin` is configured as an included build and connected to the root verification tasks.

## Result
- Created repository layout: `proto/`, `spec/`, and `kotlin/` multi-module Gradle project with modules `contract`, `schema`, `packaging`, `engine`, `router`, `daemon`, `repository`, `management-server`, `cli`, `common`, `testkit`, and included build `gradle-plugin`.
- Version catalog configured in `gradle/libs.versions.toml` with Kotlin 2.1.10, Coroutines 1.10.1, JUnit 5.11.4, Protobuf 4.29.3, gRPC 1.70.0, Spotless 7.0.2 on JDK 21.
- Dummy proto `proto/cringle/dummy/v1/dummy.proto` created and tested in `common` placeholder test.
- GitHub Actions CI workflow in `.github/workflows/ci.yml` running on `ubuntu-latest` and `windows-latest`.
- `.gitignore`, `.editorconfig`, and root `README.md` created.
- Tests can be run via `./gradlew build` or `./gradlew check`.

