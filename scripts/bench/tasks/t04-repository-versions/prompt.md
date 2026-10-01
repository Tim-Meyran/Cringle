GOAL: The repository must refuse versions that `Version` cannot parse, and must not fail as a whole because of one such entry in a hand-edited index.

CONTEXT: Module `repository` (Kotlin, JDK 21, Gradle). `PackageRepository.checkIdentity(name, version, kind)` only checks the grammar of the version with a regex. A version like `99999999999.0.0` passes, is stored, and then makes every `list()` fail because `Version` holds its numbers in an `Int`. Already available in module `packaging` (do not change it): `Version.parse(text)` throws `IllegalArgumentException` (with a readable message) for every version that cannot be represented, and `PackageNames.versionProblem(value)` returns that message or `null`. `Version` has `equals`/`hashCode` that compare by value.

FILES (touch only these):
- `kotlin/repository/src/main/kotlin/cringle/repository/PackageRepository.kt`
- `kotlin/repository/src/main/kotlin/cringle/repository/IndexStore.kt`
- `kotlin/repository/src/test/kotlin/cringle/repository/RepositoryTest.kt` (add tests, keep the existing ones)

SPEC:
1. `PackageRepository.checkIdentity`: parse the version with `Version.parse`; if it throws `IllegalArgumentException`, throw `RepositoryException(RepositoryError.INVALID, <the exception's message or "invalid version '$version'">)`. This happens before anything is written. The duplicate check must compare parsed versions (`Version.parse(it.version) == parsed`) instead of strings; the index only holds versions that parse.
2. `PackageRepository` gets a new last constructor parameter `private val warn: (String) -> Unit = { System.err.println("WARNING: $it") }` (after `maxPackageBytes`), documented as "where content that is left out of the index is reported". It is passed to `IndexStore`.
3. `IndexStore(private val file: Path, private val warn: (String) -> Unit)`: when loading, an index entry whose `version` has a `PackageNames.versionProblem(...)` is left out and reported once with `warn("$file: leaving out ${name}@${version} of the index: $problem")`. All other entries are loaded as before. The rest of the file stays strict: a broken index (for example a missing key) still throws `RepositoryIndexException` and the repository refuses to start. Because the entry is really gone, the same version can be published again afterwards.

TESTS: add to `RepositoryTest`: (a) publishing a plugin or project with versions `99999999999.0.0`, `2147483648.0.0`, `1.0.0-01`, `1.0.0-1.02` fails with `RepositoryError.INVALID`, `list()` stays empty and nothing is written to disk; a valid `1.0.0-1` can be published once, a second publish of it gives `ALREADY_EXISTS`; (b) an index that was edited to contain `99999999999.0.0` for one of two packages loads with a single warning that contains that version, the other package is still listed, and the removed version can be published again; (c) an index with a renamed required key still throws `RepositoryIndexException` from the `PackageRepository` constructor.

CHECK: `./gradlew :repository:test -q --console=plain` must pass (all existing tests too).

OUT OF SCOPE: the `packaging` module, protocol files, anything outside the three files above.
