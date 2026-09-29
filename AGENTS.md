# Instructions for AI agents working on Cringle

Cringle is a framework in which applications are assembled from **Blocks** that communicate over **Tethers**, described as **Blueprints** and executed by **Engines**. The implementation is Kotlin (JDK 21, Gradle). The language-independent platform specification is strictly separated from the Kotlin implementation.

You work by implementing **one GitHub issue at a time**. Tasks live as GitHub issues (label `agent-task`, grouped by milestones M0–M9), not as files in the repository. Read this file completely before you start.

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

It lists issues that are open, labeled `agent-task`, not `in-progress`/`blocked`/`deferred`, not claimed by a branch, and whose `**Depends on:**` issues are all closed. Prefer the lowest milestone, then the lowest issue number.

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

`gh issue view <n> --comments`, `docs/decisions.md`, and the architecture chapters named in the issue's **Architecture** line. Look at the pull requests of the issues it depends on. Do not implement anything marked `[Offen]` unless the issue says so. Treat `[Zu bestätigen]` as proposals: implement as described, keep the decision easy to change, and mention it in the pull request.

### 4. Implement

Implement exactly the issue's **Scope**. Anything under **Out of scope** belongs to another issue. Small, focused commits with messages like `#<n>: what and why`.

### 5. Verify locally

```bash
./gradlew spotlessApply
./gradlew build
```

Every acceptance criterion needs a test or a documented manual check. Never disable or weaken tests, style checks or CI to get a green build.

### 6. Pull request and automatic merge

When the acceptance criteria are met, do this **without asking**:

```bash
git push
gh pr create --base master --title "<issue title> (#<n>)" --body-file <file>   # body: use .github/pull_request_template.md, must contain "Closes #<n>"
gh pr merge --auto --squash --delete-branch
gh pr checks --watch --fail-fast
```

- Auto-merge merges the pull request as soon as all required CI checks are green. The merge closes the issue through `Closes #<n>`; the `in-progress` label is removed automatically.
- **If a check fails:** read `gh run view --log-failed`, fix the cause, push, and wait again. Repeat at most 5 times. If it still fails, or the failure is not caused by your change, run `gh pr merge --disable-auto`, add the label `blocked` to the issue, comment what is wrong, and stop.
- If `gh pr merge --auto` is rejected because auto-merge is not enabled, wait for green checks (`gh pr checks --watch`) and then run `gh pr merge --squash --delete-branch`. If the repository is not configured for the workflow at all, tell the user to run `python3 scripts/setup-github-repo.py --apply`.
- After the merge: `git switch master && git pull` and delete your local branch.

### Findings, follow-ups, blockers

- Put notes, deviations and proposed architecture changes into a comment on the issue (`gh issue comment <n>`) and the pull request description.
- Something missing that is not in scope? Create a new issue with the template (`gh issue create --template task.md --label agent-task,follow-up`), mention it in the pull request, and do not implement it.
- Blocked, or the issue and the architecture contradict each other? Add the label `blocked`, comment the reason and what is needed, and stop. Do not work around it by expanding scope.
- A decision is needed that is neither in the issue, in `decisions.md` nor in the architecture? Choose the simplest reversible option, document it in the pull request, and flag it. Use the label `needs-owner-decision` on a follow-up issue if it must be confirmed.

## Rules

- **Scope discipline.** No refactoring of unrelated code, no renames, no extra features.
- **Never edit** `docs/Architecture.md` or `docs/decisions.md` as part of an issue. Propose changes in a comment.
- **Merging** is allowed only through `gh pr merge --auto` (or the fallback above) after all required checks are green. Never use `--admin`, never merge a pull request with a failing or missing check, never push to `master`, and never change branch protection, repository settings, or the CI workflow to make a build pass (unless the issue is about exactly that).
- **Never** force-push, rewrite published history, commit secrets, keys or tokens, or add dependencies without stating them and their license in the pull request (allowed: Apache-2.0, MIT, BSD, EPL-2.0; ask before adding anything else, in particular any GPL/AGPL/LGPL).
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
- [ ] `./gradlew build` passes locally, and all required CI checks on the pull request are green.
- [ ] Pull request contains `Closes #<n>`, follows the template, and has no unrelated changes.
- [ ] Findings and follow-ups are written down (issue comment, follow-up issues).
- [ ] The pull request is merged (automatically) and the issue is closed.
