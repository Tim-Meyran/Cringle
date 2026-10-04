GOAL: Make `Version` and `PackageNames` reject versions that the implementation cannot represent, and make `equals` and `hashCode` of `Version` agree.

CONTEXT: Module `packaging` (Kotlin, JDK 21, Gradle). `PackageNames.version` is a regex (in `ManifestJson.kt`), `PackageNames.versionProblem(value)` returns a message or `null`, and `Version.parse` (in `Version.kt`) turns a text into a `Version(major: Int, minor: Int, patch: Int, prerelease: List<String>)`. `spec/versioning.md` section 1 says: numeric prerelease identifiers have no leading zeros, build metadata (`+...`) is not part of a version. Today `Version(1,0,0,listOf("01"))` equals `Version(1,0,0,listOf("1"))` but hashes differently, and `Version.parse("99999999999.0.0")` fails with an unclear message while the grammar accepts it.

FILES (touch only these):
- `kotlin/packaging/src/main/kotlin/cringle/packaging/ManifestJson.kt` (object `PackageNames`)
- `kotlin/packaging/src/main/kotlin/cringle/packaging/Version.kt`
- `kotlin/packaging/src/test/kotlin/cringle/packaging/VersionTest.kt` (add tests, keep the existing ones)

SPEC:
1. `PackageNames.version` (regex): a numeric prerelease identifier is `0` or a decimal without leading zeros; an identifier that is not all digits may contain letters, digits and `-` (for example `0a`, `a-b`, `alpha`). Examples that must match: `0.0.0`, `1.0.0`, `10.20.30`, `1.0.0-0`, `1.0.0-0a`, `1.0.0-a.b`, `1.0.0-x.7.z.92`, `1.0.0-a-b`. Examples that must not match: `01.2.3`, `1.02.3`, `1.2.03`, `1.0.0-01`, `1.0.0-1.02`, `1.0.0-00`, `1.0.0+build`, `1.0.0-`, `1.0.0-1..2`, `1.0.0-.1`.
2. `PackageNames.versionProblem(value)`: returns a message if the regex does not match, or if one of `MAJOR.MINOR.PATCH` is larger than `2147483647` (compare without converting to `Int`: a number without leading zeros with fewer digits is smaller, with equal length compare as text; prerelease identifiers are not limited). Otherwise `null`.
3. `Version.parse(text)`: throws `IllegalArgumentException` with the message of `versionProblem` if there is one; otherwise parses (no more `toIntOrNull` handling needed).
4. `Version` constructor validation: additionally reject (`require`) numeric prerelease identifiers with leading zeros (all characters digits, length > 1, starts with `0`) with a clear message. Then `equals` (based on `compareTo`) and `hashCode` agree.

TESTS: extend `VersionTest` with: (a) the good and bad examples of the grammar above, in both directions (`versionProblem` and `Version.parse`; good ones round-trip through `toString`); (b) `2147483647.0.0` parses and `2147483648.0.0`, `1.0.0.2147483648`, `99999999999.0.0` are rejected; (c) for a list of valid versions, `x == y` iff `compareTo == 0`, and equal versions have equal hash codes; `Version(1, 0, 0, listOf("01"))` throws `IllegalArgumentException`.

CHECK: `./gradlew :packaging:test --console=plain` must pass (all existing tests too).

OUT OF SCOPE: other modules, the specification files, the repository module.
