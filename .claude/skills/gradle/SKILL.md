---
name: gradle
description: How to run Gradle in the Cringle repository and read its result. Use it before every ./gradlew call (compile, test one class or module, format, full build, distribution) and whenever a build or test result is unclear, hidden or contradicts what you expected.
---

# Gradle in Cringle

Gradle 8.12.1, JDK 21, one root build (`build.gradle.kts`, `settings.gradle.kts`), the modules are the folders `kotlin/<module>` and are addressed as `:<module>`: `contract`, `schema`, `packaging`, `engine`, `router`, `daemon`, `repository`, `management-server`, `cli`, `common`, `testkit`, `gradle-plugin`. Versions are pinned in `gradle/libs.versions.toml`.

## The form of every call

```
./gradlew <tasks> --console=plain --no-daemon
```

- Always run it in the root of your checkout or worktree (the folder with `gradlew`). In WSL, bash and Git Bash use `./gradlew`; `gradlew.bat` is for cmd and PowerShell.
- **No log-level flag**: not `--quiet`/`-q`, not `--warn`. They hide `BUILD SUCCESSFUL`, the task lines and the counts, and then nothing shows that anything ran.
- `--console=plain`: no progress bars, no colors, readable in a log. `--no-daemon` is the rule of AGENTS.md.
- Do not redirect or filter the output of a Gradle call (no `> file`, no `| tail`, no background run); read the output itself. To look at details afterwards, read the test result files (below).
- The tool that runs your command has a time limit. A full build takes several minutes, the first one in a fresh checkout more. **Set the timeout of the command to 10 minutes (600000 ms)**. A build that was cut off by a timeout did not run: do not report anything about it, run it again with a longer limit, or run it per module.

## Which call for what

| You want | Call |
|---|---|
| Does one module compile (main and tests)? | `./gradlew :engine:compileKotlin :engine:compileTestKotlin --console=plain --no-daemon` |
| All tests of one module | `./gradlew :engine:test --console=plain --no-daemon` |
| One test class | `./gradlew :engine:test --tests 'cringle.engine.PackageCacheTest' --console=plain --no-daemon` |
| One method or a pattern | `--tests 'cringle.engine.PackageCacheTest.ensureForDeploy*'` |
| Run tests again that did not change | add `--rerun` to the test task: `./gradlew :engine:test --rerun --console=plain --no-daemon` |
| Format all files (SPDX header, line ends) | `./gradlew spotlessApply --console=plain --no-daemon`; `spotlessCheck` only checks |
| The full verification before a pull request | `./gradlew spotlessApply` first, then `./gradlew build --console=plain --no-daemon` (all modules, all tests, spotlessCheck) |
| See every failure of a full build, not only the first | add `--continue` |
| Why did a build fail (more detail) | add `--stacktrace`, or `--info` (with it the output of the tests shows) |
| The release archives | `./gradlew cringleDist -PreleaseVersion=0.6.0 --console=plain --no-daemon` (`DistributionTest` in `:cli` checks them) |

Rules for `--tests`: it comes **after** the test task of **one** module (`:engine:test --tests ...`). The filter is the fully qualified class name (`cringle.engine.PackageCacheTest`), a wildcard is allowed. A filter that matches nothing makes the task fail with `No tests found for given includes`: check the package name and the module (`git grep -l PackageCacheTest`) instead of removing the filter. Do not put `--tests` on a call with several test tasks: the modules without a match fail.

## Never, to get a green build

- `-x test`, `-x spotlessCheck`, `-x <anything>`: a skipped check is not a green build.
- Deleting, `@Disabled` or loosening a test or a style rule (AGENTS.md: never).
- `--offline` unless you know that everything is in the cache; `--rerun-tasks` (it reruns everything including the publishing tasks, slow; use `--rerun` on the one task); `-Dtest.single` (gone); `gradle` instead of `./gradlew`.

## Reading the result

1. The last lines: `BUILD SUCCESSFUL in 9s` or `BUILD FAILED`. No such line means the call did not finish.
2. A failed test prints `Class > method() FAILED` with the first lines of the exception, and `N tests completed, M failed`. Read those lines first, not the whole log.
3. **A passing test task prints no test counts.** Whether tests really ran, and how many, is in `kotlin/<module>/build/test-results/test/TEST-<class>.xml`:
   ```
   grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' kotlin/engine/build/test-results/test/*.xml
   grep -l "<failure\|<error" kotlin/*/build/test-results/test/*.xml
   grep -h -A4 "<failure" kotlin/engine/build/test-results/test/TEST-cringle.engine.PackageCacheTest.xml
   ```
   The HTML report is `kotlin/<module>/build/reports/tests/test/index.html`.
4. `> Task :engine:test UP-TO-DATE` or `FROM-CACHE` means the tests did **not** run this time (nothing changed). If you changed a test or production code and it still says so, you ran the wrong module or class; if you want a fresh run, add `--rerun`. `NO-SOURCE` means the module has no tests of that kind.
5. Count what you ran: say in the pull request which calls you ran and their result lines (for example `./gradlew build` on the final commit: `BUILD SUCCESSFUL`, 538 tests), not "the build passes".

## Errors you will see

| Message | Cause and fix |
|---|---|
| `No tests found for given includes` | wrong filter or module, see `--tests` above |
| `Task 'x' not found in project ':engine'` | the task or the module name is wrong; `./gradlew :engine:tasks --all --console=plain --no-daemon` lists the tasks |
| `Execution failed for task ':spotlessKotlinCheck'` ... `format violations` | run `./gradlew spotlessApply` and commit the changes. If whole files change, the line endings differ (spotless uses the native ones of the OS you build on): stop, do not commit that, and report it |
| `Unsupported class file major version` or `Toolchain ... cannot be found` | the JDK is not 21; use `JAVA_HOME` of a JDK 21 (`java -version`) |
| `Could not resolve ...` | no network or a wrong version in `libs.versions.toml`; do not add repositories to get around it |
| `WARNING: Unsupported Kotlin plugin version ... embedded-kotlin` | known, comes from the Gradle plugin module; ignore it, do not "fix" it in an unrelated pull request |
| `AccessDeniedException` on a temp file in a test (Windows) | a known flake of the cache tests on Windows; run the class once more with `--rerun`; if it passes, say so in the pull request, if it fails again, it is a real problem |
| the call hangs or ends without `BUILD ...` | it was cut off by the time limit: run it again with a limit of 10 minutes |

## Tests that need more than the module

- `:gradle-plugin:test` publishes contract, schema, packaging and the plugin into `build/cringle-test-maven-local` first (the tasks `publishToLocalRepo` and `publishToTestMavenLocal` run by themselves) and runs TestKit builds: it is the slowest module, give it time.
- `:cli:test` contains `DistributionTest`, which builds and checks the release archives of the platform you run on.
- Tests use generated certificates and temp directories and the override `CRINGLE_HOME`; they never read the real `~/.cringle`.
