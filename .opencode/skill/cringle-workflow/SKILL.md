---
name: cringle-workflow
description: How agents work on Cringle GitHub issues (claim, implement, verify, hand over, review, rework). Load before touching any issue.
---

Source of truth: `AGENTS.md` (roles, Definition of Ready, workflow, rules). This skill is the short checklist; if they differ, `AGENTS.md` wins.

## Roles
- **lead**: picks the issue, claims it, delegates work packages, runs the build, opens the PR, sets labels (also reworks issues the reviewer sent back).
- **scout**: read-only. Finds files, call sites and existing tests; answers in at most 300 words with `file:line`.
- **coder**: edits code for one work package at a time (one file or one class); the lead compiles after each.
- **reviewer**: independent review of the PR, runs the build, merges or sends back.
- The planner (Claude) defines issues. Nobody here changes Scope, Out of scope or Acceptance criteria of an issue.

## Rules that were broken before
1. Work only on `issue/<n>-<slug>`; never commit to `master`; one issue per branch.
2. Gradle only as `./gradlew <task> --quiet --console=plain --no-daemon`; do not write the output into a file (piping into `grep` or `tail` to inspect it is fine). A silent run is a green run; use `--info` and `| grep -E " FAILED|tests completed|BUILD "` to inspect.
3. Report nothing as done that did not compile and run green. State what you did not run (modules, Windows).
4. No `println`/debug output, no scratch or log files in the project, no `Thread.sleep` in tests, no hand-written JSON where the real class can write it.
5. Never make a test pass by weakening production code or by deleting a check. If you think the rule is wrong: label `blocked`, comment, stop.
6. Production code stays on the trust rule of `docs/trust.md`: trust is the key fingerprint.
7. Do not touch `docs/Architecture.md`, `docs/decisions.md`, or the labels `ready`/`deferred`.
8. Do not delete tags, branches or releases.

## Loop per issue
1. `python scripts/next-issue.py` (or the given number); read the issue and `**Depends on:**` (all closed).
2. Claim: create and push `issue/<n>-<slug>`, label `in-progress`.
3. Scout reads the affected files; coder implements in small steps; lead compiles after each step (`./gradlew :<module>:compileKotlin :<module>:compileTestKotlin --warn --console=plain --no-daemon`).
4. `./gradlew spotlessApply` then `./gradlew build` (module tests first, then the full build, see `AGENTS.md` 5).
5. `git status`: only intended files. PR text: what changed, test counts, OS, what was not run, `Closes #<n>`.
6. Label `needs-review`, remove `in-progress`, comment `PR #<pr> ready for review.` Stop; the reviewer merges.

## Working with Gradle efficiently
Always the form `./gradlew <task> --warn --console=plain --no-daemon`; never write its output to a file and never run it in the background. The shell is bash in WSL, so `./gradlew` (not `gradlew.bat`) with the JDK 21 of WSL. `--warn` prints errors only, so **no output means green**. To see results use `--info` and filter the same command: `./gradlew :router:test --info --console=plain --no-daemon | grep -E " FAILED|tests completed|BUILD "`.

1. **Narrow first, wide last.** In this order: `:<module>:compileKotlin :<module>:compileTestKotlin` (seconds), then `:<module>:test --tests '*ClassName'`, then `:<module>:test`, and the full `build` only once at the end. Module order by dependency: `common`, `contract`, `router`, `engine`, `daemon`, `management-server`, `cli`.
2. **One failure hides the rest.** Gradle stops at the first failing module, so later modules show no result at all (their report files are old). To see every failure run `./gradlew build --continue` once and list all failing tests before you fix any.
3. **Read the report, not the log.** Failures with message and line are in `kotlin/<module>/build/test-results/test/TEST-*.xml` (`<failure message=...>`); check that the file is newer than your run, otherwise it is an old result. A test that shows `UP-TO-DATE` did not run: use `:<module>:cleanTest :<module>:test`.
4. **One Gradle run at a time.** A second run waits for the first one and looks like a hang. If a run does not end, find and stop the old one first (`Gradle Test Executor` and the daemon of the previous run), then start again.
5. **Slow tests are not failing tests.** `DeploymentTest` and `ManagementServerTest` start real engine JVMs and take minutes. Run them alone (`--tests '*DeploymentTest'`), last, with a timeout of at least 10 minutes, and do not rerun them while they are running. If a tool limits the runtime of one command, split the work (compile, one module, the slow tests separately) instead of retrying the same command.
6. **Do not change the code to get past a build problem.** `Cannot find a Java installation` needs JDK 21 (set `JAVA_HOME` or `-Porg.gradle.java.installations.paths=<jdk>`); a missing network needs `--offline`; `spotlessCheck` complaints are fixed with `./gradlew spotlessApply`, not by hand. Deleting `build/` or `.gradle/` is only for "stale output" errors.
7. **Line endings (Windows).** The working tree may be CRLF and show hundreds of changed files. Judge changes with `git diff --ignore-space-at-eol`, stage only the files you changed (`git -c core.autocrlf=true add <file>`), and never commit line-ending-only changes.
8. **Tests that stay green.** `@TempDir` for files, port `0` for servers, no `Thread.sleep` (wait for a condition), no debug `println`; to find out why a test fails use the XML report or `--info`, not new prints in the code.
