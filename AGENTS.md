# Instructions for AI agents working on Cringle

Cringle is a framework in which applications are assembled from **Blocks** that communicate over **Tethers**, described as **Blueprints** and executed by **Engines**. The implementation is Kotlin (JDK 21, Gradle). The language-independent platform specification is strictly separated from the Kotlin implementation.

You work by implementing **one issue at a time** from `docs/issues/`. Read this file completely before you start.

## Where to find things

| What | Where |
|---|---|
| Architecture (source of truth, German) | `docs/Architecture.md` |
| Project decisions (override open proposals) | `docs/decisions.md` |
| Issues and their rules | `docs/issues/README.md`, one file per issue |
| Issue template | `docs/issues/TEMPLATE.md` |
| Language-independent protocol (`.proto`) | `proto/` |
| Language-independent specs | `spec/` |
| Kotlin implementation | `kotlin/` |

The architecture and decision documents are in German, issues and code are in English.

## Workflow

1. **Find work.** Run `python3 scripts/next-issue.py`. It lists issues that are `open` and whose dependencies are all `done`. Prefer the lowest milestone, then the lowest number. Do not pick `deferred`, `blocked` or `in-progress` issues.
2. **Claim it.** Claiming means a remote branch exists. Check `git ls-remote --heads origin 'issue/NNN-*'` first. If a branch for that issue exists, pick another issue. Otherwise create and push the branch `issue/NNN-short-slug` immediately, and in the first commit set `status: in-progress` and `assignee:` in the issue's frontmatter.
3. **Read** `docs/decisions.md`, the issue completely, and the architecture chapters listed under `architecture:` in its frontmatter. Do not implement anything marked `[Offen]` unless the issue says so. Treat `[Zu bestätigen]` as proposals: implement as described, keep the decision easy to change, and list it under *Notes / Findings*.
4. **Implement** exactly the issue's *Scope*. Anything under *Out of scope* belongs to another issue.
5. **Verify** with `./gradlew build` (runs all tests and style checks) and fix everything you broke. Every acceptance criterion needs a test or a documented manual check. Never disable or weaken tests or style checks to get a green build.
6. **Finish.** In the issue file: tick the acceptance criteria, fill in *Result* (what, where, how to run tests) and *Notes / Findings*, set `status: done`. Open a pull request titled `NNN: <issue title>` using the PR template. One issue per pull request.

## Rules

- **Scope discipline.** Do not refactor unrelated code, rename things, or add features that are not in the issue. Found something missing? Add a note under *Notes / Findings* or create a new issue file (next free number, copy `TEMPLATE.md`, `status: open`) and mention it in the PR. Do not implement it.
- **Never edit** `docs/Architecture.md` or `docs/decisions.md` as part of an issue. Propose changes in *Notes / Findings*. Where the issue and the architecture disagree, stop, explain in *Notes / Findings*, set `status: blocked`, and open the PR as draft.
- **No guessing on decisions.** If a decision needed for your issue is neither in the issue, in `decisions.md` nor in the architecture, choose the simplest reversible option, document it in *Notes / Findings*, and flag it in the PR.
- **Blocked?** Set `status: blocked` with the reason and what is needed, push, and open a draft PR. Do not work around the problem by touching other issues' scope.
- **Never** force-push, rewrite history, push to `main`, merge your own pull request, commit secrets, keys or tokens, or add dependencies without stating them and their license in the PR (allowed: Apache-2.0, MIT, BSD, EPL-2.0; ask before adding anything else, in particular any GPL/AGPL/LGPL).
- Keep commits small with clear messages (`NNN: what and why`).

## Code conventions

- Kotlin, JDK 21, Gradle with the version catalog; pin versions in `gradle/libs.versions.toml`.
- Public API of `contract` and other library modules has KDoc. Keep `contract` dependency-free (stdlib and coroutines only).
- Coroutines for all asynchronous code. Never block a dispatcher thread inside block or tether execution.
- Tests: JUnit 5; deterministic (no sleeps for synchronization, use test dispatchers or latches); no network access outside `localhost`; generated certificates and temp dirs only, never files from the real `~/.cringle`. Use the `CRINGLE_HOME` override.
- Language-independent parts (`proto/`, `spec/`) contain no Kotlin-specific assumptions.
- Every source file starts with the SPDX header `// SPDX-License-Identifier: Apache-2.0` (Kotlin, Java) or the equivalent comment in other languages. `.proto` files use `// SPDX-License-Identifier: Apache-2.0` as well.
- Security-relevant code (keys, certificates, tokens, path handling) must never log secrets and must have tests for the failure cases.

## Definition of done

- [ ] All acceptance criteria of the issue are met and tested.
- [ ] `./gradlew build` passes locally on a clean checkout.
- [ ] Issue file updated: criteria ticked, *Result* and *Notes / Findings* filled, `status: done`.
- [ ] Pull request opened with the template, one issue per PR, no unrelated changes.
