GOAL: Add the internal helper `writeAtomically` to the `management-server` module.

CONTEXT: Kotlin on JDK 21, built with Gradle (`./gradlew`). Every source file starts with the line `// SPDX-License-Identifier: Apache-2.0`. The package is `cringle.management`. The helper will later be used to write lock files; nothing calls it yet.

FILES (touch only these):
- create `kotlin/management-server/src/main/kotlin/cringle/management/AtomicFile.kt`
- create `kotlin/management-server/src/test/kotlin/cringle/management/AtomicFileTest.kt`

SPEC: in `AtomicFile.kt`:
```kotlin
internal fun writeAtomically(target: Path, text: String, beforeMove: () -> Unit = {})
```
- Create the parent directory of `target` (with missing parents) if it does not exist.
- Write `text` to a temporary file in the same directory (`Files.createTempFile(directory, <file name of target>, ".tmp")`), then call `beforeMove()`, then move the temporary file over `target` with `StandardCopyOption.REPLACE_EXISTING` and `StandardCopyOption.ATOMIC_MOVE`.
- Afterwards `target` is either the old file or the complete new text, never a part of it.
- If anything fails, including an exception thrown by `beforeMove`: delete the temporary file (ignore errors while deleting), leave `target` as it was (it does not exist if it did not exist before), and rethrow the original exception unchanged.
- `beforeMove` exists only so that tests can inject a failure between writing and moving.
- Add a short KDoc comment.

TESTS: JUnit 5 with `@TempDir`, in `AtomicFileTest`:
1. Writing twice to the same target (in a not yet existing subdirectory) leaves the second text.
2. A failure thrown in `beforeMove` with an existing target: the exception propagates, the old content stays, the directory contains only the target (no `.tmp` file).
3. A failure thrown in `beforeMove` without an existing target: the directory stays empty.

CHECK: `./gradlew :management-server:test --tests 'cringle.management.AtomicFileTest' --warn --console=plain` must pass.

OUT OF SCOPE: using the helper anywhere, changing any other file, adding dependencies.
