---
name: cringle-workflow
description: How agents work on Cringle GitHub issues (claim, implement, verify, hand over, review, rework). Load before touching any issue.
---

Source of truth: `AGENTS.md` (roles, Definition of Ready, workflow, rules). This skill is the short checklist; if they differ, `AGENTS.md` wins.

## Roles
- **lead**: picks the issue, claims it, delegates, runs the build, opens the PR, sets labels. Writes no production code itself unless the coder failed twice.
- **scout**: read-only. Finds files, call sites and existing tests; answers in at most 300 words with `file:line`.
- **coder**: edits code for one small step at a time (one file or one class), then the lead compiles.
- **reviewer**: independent review of the PR, runs the build, merges or sends back.
- The planner (Claude) defines issues. Nobody here changes Scope, Out of scope or Acceptance criteria of an issue.

## Rules that were broken before
1. Work only on `issue/<n>-<slug>`; never commit to `master`; one issue per branch.
2. Gradle only as `./gradlew <task> --quiet --console=plain --no-daemon`; do not redirect the output into the project. A silent run is a green run; use `--info` and `| grep -E " FAILED|tests completed|BUILD "` to inspect.
3. Report nothing as done that did not compile and run green. State what you did not run (modules, Windows).
4. No `println`/debug output, no scratch or log files in the project, no `Thread.sleep` in tests, no hand-written JSON where the real class can write it.
5. Never make a test pass by weakening production code or by deleting a check. If you think the rule is wrong: label `blocked`, comment, stop.
6. Production code stays on the trust rule of `docs/trust.md`: trust is the key fingerprint.
7. Do not touch `docs/Architecture.md`, `docs/decisions.md`, or the labels `ready`/`deferred`.
8. Do not delete tags, branches or releases.

## Loop per issue
1. `python scripts/next-issue.py` (or the given number); read the issue and `**Depends on:**` (all closed).
2. Claim: create and push `issue/<n>-<slug>`, label `in-progress`.
3. Scout reads the affected files; coder implements in small steps; lead compiles after each step (`./gradlew :<module>:compileKotlin :<module>:compileTestKotlin --quiet --console=plain --no-daemon`).
4. `./gradlew spotlessApply` then `./gradlew build` (module tests first, then the full build, see `AGENTS.md` 5).
5. `git status`: only intended files. PR text: what changed, test counts, OS, what was not run, `Closes #<n>`.
6. Label `needs-review`, remove `in-progress`, comment `PR #<pr> ready for review.` Stop; the reviewer merges.
