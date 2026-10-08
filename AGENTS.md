# Instructions for AI agents working on Cringle

Cringle is a framework in which applications are assembled from **Blocks** that communicate over **Tethers**, described as **Blueprints** and executed by **Engines**. The implementation is Kotlin (JDK 21, Gradle). The language-independent platform specification is strictly separated from the Kotlin implementation.

You work on **one GitHub issue at a time**. Tasks live as GitHub issues (label `agent-task`, grouped by milestones M0–M9), not as files in the repository. Read this file completely before you start. The state of the project (what is done, what is open, decisions that are not yet in `docs/decisions.md`) is in [`docs/status.md`](docs/status.md).

## Roles

| Who | Job |
|---|---|
| **Agent** (Claude Code, in the cloud or on a developer machine) | Takes one issue, makes it ready if it is not (see "Definition of Ready"), implements exactly its scope, verifies it, opens the pull request. Does not merge. |
| **Owner** | Decides `[Offen]` points and everything labeled `needs-owner-decision`, reviews and merges pull requests, can override any rule. |
| **Reviewer** (optional: a second agent or `/code-review`) | Reviews a pull request independently (acceptance criteria, correctness, scope, tests, code quality, build) and comments findings on the pull request. Never edits code. |

Rules of the hand-over:

- An agent only implements issues that are `ready`. An issue is `ready` only when it meets the *Definition of Ready* below. Many issues are not `ready` yet: defining them (sharpening scope and acceptance criteria, `gh issue edit`) is part of the job, in a pull-request-free step of its own, and the owner confirms the open points (see "Open decisions").
- Never change the Scope, Out of scope or Acceptance criteria of an issue while implementing it. If it is unclear, contradictory or impossible: comment what exactly is unclear, add the label `blocked`, remove `ready`, and stop.
- Findings while implementing (missing pieces, follow-up work) go into an issue comment and a follow-up issue (label `follow-up`), which is defined later.
- Review loop: after opening the pull request, add the label `needs-review` and stop. When the owner or the reviewer sends it back (comment `Review round <k>: CHANGES REQUESTED`, label `changes-requested`), rework exactly those findings on the existing branch, push, and set `needs-review` again. After three rounds add `needs-owner-decision` and stop.
- **Do not stack pull requests on branches that are not merged yet**, unless the owner asks for it. A pull request whose base is another feature branch is merged into that branch, not into `master`, and the work never reaches `master` (this happened with #136 and #137). If you have to, say so in the pull request and retarget it when its base is merged.

### Definition of Ready

An issue is ready when all of this is true:

- [ ] **Context** explains why the issue exists and where it fits; the architecture chapters are referenced.
- [ ] **Scope** is a concrete list (modules, packages, public API, behavior). No open choice is left to the implementer: the choice is made in **Design notes** (simplest reversible option, `[Zu bestätigen]` marked).
- [ ] **Out of scope** names what belongs to other issues, by `#number`.
- [ ] **Acceptance criteria** are verifiable: each one has a named test or a documented command or manual check.
- [ ] **Depends on** is correct and every dependency is closed or `ready`.
- [ ] No `[Offen]` point is touched, or the owner has decided it.
- [ ] Small enough for one pull request (one concern, about five production files; split it otherwise).

### Open decisions

For `[Offen]` points or anything that is neither in the issue, in `docs/decisions.md` nor in the architecture: propose the simplest reversible option under **Design notes**, marked `[Zu bestätigen]`. If the owner has to decide, add the label `needs-owner-decision`, do not mark the issue `ready`, and ask the owner (all questions at once, with a recommended option first).

## Where to find things

| What | Where |
|---|---|
| Tasks | GitHub issues of this repository (`gh issue list --label agent-task`) |
| State of the project, open decisions | `docs/status.md` |
| Architecture (source of truth, German) | `docs/Architecture.md` |
| Project decisions (override open proposals) | `docs/decisions.md` |
| Language-independent protocol (`.proto`) | `proto/` |
| Language-independent specs | `spec/` |
| Kotlin implementation | `kotlin/` |
| Documentation index | `docs/README.md` |
| How to run Gradle and read its result (read it before every `./gradlew` call) | `.claude/skills/gradle/SKILL.md` |

The architecture and decision documents are in German, issues and code are in English.

## Prerequisites

`gh` (GitHub CLI) or the GitHub tools of your environment can read and write issues and pull requests, `git` can push branches to `origin`, JDK 21 is available. If not, stop and tell the user what is missing. The tests start real JVM processes (engines, daemons) on loopback ports and need a few minutes; give a Gradle call 10 minutes.

## Workflow

### 1. Find work

```bash
python scripts/next-issue.py
```

It lists issues that are open, labeled `agent-task` and `ready`, not `in-progress`/`blocked`/`deferred`, not claimed by a branch, and whose `**Depends on:**` issues are all closed. Prefer the lowest milestone, then the lowest issue number. If you were given an issue number, take that one.

### 2. Claim it

Claiming is done by pushing a branch, which is atomic. `<n>` is the GitHub issue number.

```bash
git fetch origin
git ls-remote --heads origin "issue/<n>-*"        # if this prints anything, the issue is taken: pick another
git switch -c issue/<n>-short-slug origin/master
git push -u origin issue/<n>-short-slug            # if this fails because the branch exists, pick another issue
gh issue edit <n> --add-label in-progress
gh issue comment <n> --body "Claimed on branch issue/<n>-short-slug."
```

### 3. Read

`gh issue view <n> --json number,title,body,labels,milestone,comments` (not `--comments` alone: without a terminal it prints only the comments, nothing if there are none), `docs/decisions.md`, `docs/status.md`, and the architecture chapters named in the issue's **Architecture** line. Look at the pull requests of the issues it depends on. Do not implement anything marked `[Offen]` unless the issue says so. Treat `[Zu bestätigen]` as proposals: implement as described, keep the decision easy to change, and mention it in the pull request.

### 4. Implement

Implement exactly the issue's **Scope**. Anything under **Out of scope** belongs to another issue. Small, focused commits with messages like `#<n>: what and why`.

### 5. Verify locally

```bash
./gradlew spotlessApply --console=plain --no-daemon
./gradlew build --console=plain --no-daemon
./gradlew :<module>:integrationTest --console=plain --no-daemon   # for every module you touch and every module that depends on it
```

There are two test suites. `./gradlew build` runs only the fast suite. Tests that start a process, use the network, run a Gradle build or take 5 s or more carry `@Tag("integration")` and run with `integrationTest` (all modules: `./gradlew integrationTest`). The full verification is `./gradlew build integrationTest`.

Run every Gradle command in this form: `./gradlew <task> --console=plain --no-daemon`. Do not redirect or filter the output (no `> file`, no `| tail`, no `nohup`/background run); read the output of the command itself. Test and build output never goes into a file in the project. A successful build ends with `BUILD SUCCESSFUL`, a failed one with `BUILD FAILED`.

Every acceptance criterion needs a test or a documented manual check. Never disable or weaken tests, style checks or CI to get a green build. A test that fails only on one operating system is a finding, not something to skip: say so in the pull request and fix it if the cause is in the code or the test.

### 6. Pull request and review

**Status of the CI:** the GitHub workflow `CI` is disabled (billing of the account), so the local build replaces the CI checks. The agent does not merge: the owner (or a reviewer the owner names) verifies and merges.

When the acceptance criteria are met, do this **without asking**:

```bash
./gradlew build --console=plain --no-daemon       # must pass locally: all modules, fast suite
./gradlew :<module>:integrationTest --console=plain --no-daemon   # integration suite of every touched module and its dependents
git push
gh pr create --base master --title "<issue title> (#<n>)" --body-file <file>   # body: use .github/pull_request_template.md, must contain "Closes #<n>"
gh issue edit <n> --add-label needs-review --remove-label changes-requested
gh issue comment <n> --body "PR #<pr> ready for review."
```

- The pull request text states the result of the local build (operating system, number of tests, `BUILD SUCCESSFUL`) and names every test that could not run on this machine (for example tests that are skipped on Windows or Linux). Code that only one platform can exercise is called out explicitly, so the owner can run it there.
- Open the pull request only if `./gradlew build` was green **on the final commit of the branch**. A red or unfinished build is never handed over; add the label `blocked`, comment what is wrong, and stop.
- **Rework** (issue has the label `changes-requested`): do not claim a new branch. Switch to the existing `issue/<n>-*` branch, take the last comment that starts with `Review round` as the spec, implement exactly those blocker/major findings, run `./gradlew build`, push, and set `needs-review` again. Do not change anything else.
- **Merging** (owner): `gh pr merge --squash --delete-branch`; the squash commit message contains `Closes #<n>`. Afterwards the labels `in-progress`, `needs-review` and `changes-requested` are removed from the closed issue.

### Findings, follow-ups, blockers

- Put notes, deviations and proposed architecture changes into a comment on the issue (`gh issue comment <n>`) and the pull request description.
- Something missing that is not in scope? Create a new issue with the template (`gh issue create --template task.md --label agent-task,follow-up`), mention it in the pull request, and do not implement it.
- Blocked, or the issue and the architecture contradict each other? Add the label `blocked`, comment the reason and what is needed, and stop. Do not work around it by expanding scope.

## Rules

- **Scope discipline.** No refactoring of unrelated code, no renames, no extra features.
- **Never edit** `docs/Architecture.md` or `docs/decisions.md` as part of an issue. Propose changes in a comment (or in `docs/status.md`, where decisions that are not yet in `decisions.md` are collected).
- **Merging** is done only by the owner (or a reviewer the owner names), only through `gh pr merge --squash --delete-branch`, and only after `./gradlew build integrationTest` was green on the final commit and the review found no blocker or major problem. Never use `--admin`, never merge a pull request whose local build failed or was not run, never push to `master`, and never change branch protection, repository settings, or the CI workflow to make a build pass (unless the issue is about exactly that).
- **Never** force-push, rewrite published history, commit secrets, keys or tokens, or add dependencies without stating them and their license in the pull request (allowed: Apache-2.0, MIT, BSD, EPL-2.0; ask before adding anything else, in particular any GPL/AGPL/LGPL).
- **Technical guard rails.** `.claude/settings.json` (Claude Code) denies force-pushes, branch deletion, repository and branch-protection changes; `opencode.json` (opencode) additionally denies pushes to `master`, `--admin` merges and edits of `docs/Architecture.md` and `docs/decisions.md`. For Claude Code the last three are rules without a technical guard: keep to them unless the owner allows an exception; edits of workflows are confirmed by the user. If an action is denied, do not look for a way around it: stop and tell the user.
- **No commits to `master`.** Every change goes through a branch and a pull request (`issue/<n>-<slug>` for an issue). Scratch, log and test-output files go to `$TMPDIR` or `build/`, never into the project (`git status` must show none before you commit).
- **Do not make a test pass by weakening production code.** If a test fails and you think the production rule is wrong, set `blocked` and describe it.
- One issue per branch and pull request. Do not start a second issue before the first pull request is handed over for review (`needs-review`) or you have marked the issue `blocked`.

## Code conventions

- Kotlin, JDK 21, Gradle with the version catalog; pin versions in `gradle/libs.versions.toml`.
- Public API of `contract` and other library modules has KDoc. Keep `contract` dependency-free (stdlib and coroutines only).
- Coroutines for all asynchronous code. Never block a dispatcher thread inside block or tether execution.
- Tests: JUnit 5; deterministic (no sleeps for synchronization, use test dispatchers or latches); no network access outside `localhost`; generated certificates and temp dirs only, never files from the real `~/.cringle`. Use the `CRINGLE_HOME` override. A test that starts a process, uses the network, runs a Gradle build or takes 5 s or more gets `@Tag("integration")`; all others stay in the fast suite.
- **TLS in tests:** every connection between components is mutual TLS and there is no unencrypted mode. Tests use `cringle.common.test.TestTls` (test fixtures of `common`, `testFixtures(project(":common"))`) for identities and trust stores, and `cringle.management.test.ManagementTls` (test fixtures of `management-server`) for a management server with daemon, repository and router. A peer without an entry in the trust store is refused at the handshake; test it that way.
- **Windows and Linux:** tests run on both. Do not create symbolic links in tests (they need a privilege on Windows); use `linkSwitcherFor(Platform.current())` where the self-update needs a link. A test that cannot run on one system uses `@EnabledOnOs` and the pull request says so.
- Language-independent parts (`proto/`, `spec/`) contain no Kotlin-specific assumptions.
- Every source file starts with the SPDX header `// SPDX-License-Identifier: Apache-2.0` (Kotlin, Java, `.proto`) or the equivalent comment in other languages; `./gradlew spotlessApply` adds it.
- Security-relevant code (keys, certificates, tokens, path handling) must never log secrets and must have tests for the failure cases.

## Definition of done

- [ ] All acceptance criteria of the issue are met and tested.
- [ ] `./gradlew build` and the `integrationTest` of the touched modules pass locally on the final commit, and the pull request text states both results and the number of tests of each. The reviewer runs `./gradlew build integrationTest` before merging.
- [ ] Pull request contains `Closes #<n>`, follows the template, and has no unrelated changes.
- [ ] Findings and follow-ups are written down (issue comment, follow-up issues).
- [ ] Agent: the pull request is open and the issue has the label `needs-review`.
- [ ] Owner: the pull request is approved and merged, and the issue is closed.

## Token economy (applies to every agent)

- Read only what the task needs: the issue, the documents it names, and the files you change; use line ranges, `grep -n` and `git show` instead of whole files. Do not re-read files you just wrote.
- Keep command output small: `--jq` for `gh`, `--tests <name>` for a single test while iterating; run the full `./gradlew build` once before the pull request, not after every edit. The output of a Gradle call itself is read in full (see the Gradle skill).
- Write short commits, pull request texts and comments: the result, deviations, and what could not be checked. No recap of steps, no restating the issue.
- Do not generate files, tests or documentation beyond the scope of the issue.
