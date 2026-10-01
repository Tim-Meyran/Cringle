# Instructions for AI agents working on Cringle

Cringle is a framework in which applications are assembled from **Blocks** that communicate over **Tethers**, described as **Blueprints** and executed by **Engines**. The implementation is Kotlin (JDK 21, Gradle). The language-independent platform specification is strictly separated from the Kotlin implementation.

You work by implementing **one GitHub issue at a time**. Tasks live as GitHub issues (label `agent-task`, grouped by milestones M0–M9), not as files in the repository. Read this file completely before you start.

## Roles

Two agents work on this project. Each has one job, and the owner decides everything else.

| Who | Job |
|---|---|
| **Claude** | Defines tasks: turns issues into clear, implementable tasks and marks them `ready`. Creates and defines follow-up issues. Does not implement issues. |
| **opencode** | Implements tasks: takes `ready` issues, implements exactly the defined scope and opens the pull request. Does not redefine tasks. |
| **Owner** | Decides `[Offen]` points and everything labeled `needs-owner-decision`; can override any rule. |

Handoff rules:

- opencode only picks up issues labeled `ready`. An issue is `ready` only when it meets the *Definition of Ready* below.
- opencode never changes an issue's Scope, Out of scope or Acceptance criteria. If the task is unclear, contradictory or impossible: comment what exactly is unclear, add the label `blocked`, remove `ready`, and stop. Claude sharpens the issue and sets `ready` again.
- Findings while implementing (missing pieces, follow-up work) go into an issue comment and a follow-up issue (label `follow-up`, not `ready`). Claude defines it later.
- The workflow, conventions and rules in the sections below are written for the implementer (opencode). Claude's planner procedure is in `CLAUDE.md`.

### Definition of Ready

An issue is ready when all of this is true:

- [ ] **Context** explains why the issue exists and where it fits; the architecture chapters are referenced.
- [ ] **Scope** is a concrete list (modules, packages, public API, behavior). No open choice is left to the implementer: the choice is made in **Design notes** (simplest reversible option, `[Zu bestätigen]` marked).
- [ ] **Out of scope** names what belongs to other issues, by `#number`.
- [ ] **Acceptance criteria** are verifiable: each one has a test or a documented command or manual check.
- [ ] **Depends on** is correct and every dependency is closed or `ready`.
- [ ] No `[Offen]` point is touched, or the owner has decided it.
- [ ] Small enough for one pull request.

## Where to find things

| What | Where |
|---|---|
| Tasks | GitHub issues of this repository (`gh issue list --label agent-task`) |
| Architecture (source of truth, German) | `docs/Architecture.md` |
| Project decisions (override open proposals) | `docs/decisions.md` |
| Language-independent protocol (`.proto`) | `proto/` |
| Language-independent specs | `spec/` |
| Kotlin implementation | `kotlin/` |
| Documentation index | `docs/README.md` |

The architecture and decision documents are in German, issues and code are in English.

## Prerequisites

`gh` (GitHub CLI) is installed and authenticated (`gh auth status`), `git` can push branches to `origin`, JDK 21 is available. If not, stop and tell the user what is missing.

## Workflow

### 1. Find work

```bash
python3 scripts/next-issue.py
```

It lists issues that are open, labeled `agent-task` and `ready`, not `in-progress`/`blocked`/`deferred`, not claimed by a branch, and whose `**Depends on:**` issues are all closed. Prefer the lowest milestone, then the lowest issue number.

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

`gh issue view <n> --json number,title,body,labels,milestone,comments` (not `--comments` alone: without a terminal it prints only the comments, nothing if there are none), `docs/decisions.md`, and the architecture chapters named in the issue's **Architecture** line. Look at the pull requests of the issues it depends on. Do not implement anything marked `[Offen]` unless the issue says so. Treat `[Zu bestätigen]` as proposals: implement as described, keep the decision easy to change, and mention it in the pull request.

### 4. Implement

Implement exactly the issue's **Scope**. Anything under **Out of scope** belongs to another issue. Small, focused commits with messages like `#<n>: what and why`.

### 5. Verify locally

```bash
./gradlew spotlessApply
./gradlew build
```

Every acceptance criterion needs a test or a documented manual check. Never disable or weaken tests, style checks or CI to get a green build.

### 6. Pull request and merge (CI suspended)

**Status (decided by the owner, 2026-09-30): the GitHub CI is suspended** (the account's Actions billing is not available; the workflow `CI` is disabled). Until the owner re-enables it, the local build replaces the CI checks. The rules of the previous procedure (automatic merge after green CI checks) apply again as soon as the owner says the CI runs.

When the acceptance criteria are met, do this **without asking**:

```bash
./gradlew build                                   # must pass locally, all modules, all tests
git push
gh pr create --base master --title "<issue title> (#<n>)" --body-file <file>   # body: use .github/pull_request_template.md, must contain "Closes #<n>"
gh pr merge --squash --delete-branch
```

- The pull request text states the result of the local build (operating system, number of tests, `BUILD SUCCESSFUL`) and names every test that could not run on this machine (for example tests that are skipped on Windows or Linux). Code that only one platform can exercise is called out explicitly, so the owner can run it there.
- Merge only if `./gradlew build` was green **on the final commit of the branch**. A red or unfinished build is never merged; add the label `blocked`, comment what is wrong, and stop.
- The merge closes the issue through `Closes #<n>` (the squash commit message must contain it); remove the label `in-progress` yourself, because the housekeeping workflow does not run either.
- After the merge: `git switch master && git pull` and delete your local branch.

### Findings, follow-ups, blockers

- Put notes, deviations and proposed architecture changes into a comment on the issue (`gh issue comment <n>`) and the pull request description.
- Something missing that is not in scope? Create a new issue with the template (`gh issue create --template task.md --label agent-task,follow-up`), mention it in the pull request, and do not implement it.
- Blocked, or the issue and the architecture contradict each other? Add the label `blocked`, comment the reason and what is needed, and stop. Do not work around it by expanding scope.
- A decision is needed that is neither in the issue, in `decisions.md` nor in the architecture? Choose the simplest reversible option, document it in the pull request, and flag it. Use the label `needs-owner-decision` on a follow-up issue if it must be confirmed.

## Rules

- **Scope discipline.** No refactoring of unrelated code, no renames, no extra features.
- **Never edit** `docs/Architecture.md` or `docs/decisions.md` as part of an issue. Propose changes in a comment.
- **Merging** is allowed only through `gh pr merge --squash --delete-branch` after `./gradlew build` was green locally on the final commit (while the CI is suspended, see section 6). Never use `--admin`, never merge a pull request whose local build failed or was not run, never push to `master`, and never change branch protection, repository settings, or the CI workflow to make a build pass (unless the issue is about exactly that).
- **Never** force-push, rewrite published history, commit secrets, keys or tokens, or add dependencies without stating them and their license in the pull request (allowed: Apache-2.0, MIT, BSD, EPL-2.0; ask before adding anything else, in particular any GPL/AGPL/LGPL).
- **Technical guard rails.** `opencode.json` (opencode) and `.claude/settings.json` (Claude Code) enforce the hard rules above: force-pushes, pushes to `master`, `--admin` merges, repository and branch-protection changes, and edits of `docs/Architecture.md` and `docs/decisions.md` are denied; edits of workflows are confirmed by the user. If an action is denied, do not look for a way around it: stop and tell the user.
- One issue per branch and pull request. Do not start a second issue before the first pull request is merged or you have marked the issue `blocked`.

## Code conventions

- Kotlin, JDK 21, Gradle with the version catalog; pin versions in `gradle/libs.versions.toml`.
- Public API of `contract` and other library modules has KDoc. Keep `contract` dependency-free (stdlib and coroutines only).
- Coroutines for all asynchronous code. Never block a dispatcher thread inside block or tether execution.
- Tests: JUnit 5; deterministic (no sleeps for synchronization, use test dispatchers or latches); no network access outside `localhost`; generated certificates and temp dirs only, never files from the real `~/.cringle`. Use the `CRINGLE_HOME` override.
- Language-independent parts (`proto/`, `spec/`) contain no Kotlin-specific assumptions.
- Every source file starts with the SPDX header `// SPDX-License-Identifier: Apache-2.0` (Kotlin, Java, `.proto`) or the equivalent comment in other languages; `./gradlew spotlessApply` adds it.
- Security-relevant code (keys, certificates, tokens, path handling) must never log secrets and must have tests for the failure cases.

## Definition of done

- [ ] All acceptance criteria of the issue are met and tested.
- [ ] `./gradlew build` passes locally on the final commit (the CI is suspended; see section 6), and the pull request text states the result.
- [ ] Pull request contains `Closes #<n>`, follows the template, and has no unrelated changes.
- [ ] Findings and follow-ups are written down (issue comment, follow-up issues).
- [ ] The pull request is merged and the issue is closed.

## Token economy (applies to every agent)

- Read only what the task needs: the issue, the documents it names, and the files you change; use line ranges, `grep -n` and `git show` instead of whole files. Do not re-read files you just wrote.
- Keep command output small: `--jq` for `gh`, `| tail -n 40` for build logs (read the full log only for a failure), `--tests <name>` for a single test while iterating; run the full `./gradlew build` once before the pull request, not after every edit.
- Write short commits, pull request texts and comments: the result, deviations, and what could not be checked. No recap of steps, no restating the issue.
- Do not generate files, tests or documentation beyond the scope of the issue.

