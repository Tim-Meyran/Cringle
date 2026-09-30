# Cringle Versioning Specification

Status: format version 1. Language independent; the reference implementation lives in the Kotlin module `packaging`
(`Version`, `VersionRange`, `Resolver`, `LockFile`). Architecture: chapter 8.4 and 15. The manifest fields that carry
versions and ranges are defined in `package-format.md`.

## 1. Versions

`MAJOR.MINOR.PATCH` with optional `-prerelease`; numbers are non-negative decimals without leading zeros;
prerelease is a dot separated list of `[0-9A-Za-z-]+` identifiers, and a prerelease identifier that is a number has
no leading zeros either (`1.0.0-01` is invalid, `1.0.0-0` and `1.0.0-0a` are valid). Build metadata (`+build`) is not
allowed.

Ordering is Semantic Versioning 2.0: compare major, minor, patch numerically; a version with a prerelease is
lower than the same version without; prereleases compare identifier by identifier, numeric identifiers numerically
and lower than alphanumeric ones, and a shorter list is lower when all shared identifiers are equal.
Example chain: `1.0.0-alpha < 1.0.0-alpha.1 < 1.0.0-alpha.beta < 1.0.0-beta < 1.0.0-beta.2 < 1.0.0-beta.11 < 1.0.0-rc.1 < 1.0.0`.

## 2. Ranges

A range is one or more **sets** separated by `||`; a version satisfies the range if it satisfies any set. A set is
one or more space separated **terms**; a version satisfies the set if it satisfies all terms.

| Term | Meaning |
|---|---|
| `1.2.3`, `=1.2.3` | exactly this version |
| `>1.2.3`, `>=1.2.3`, `<1.2.3`, `<=1.2.3` | comparison; a full version is required; a space after the operator is allowed |
| `^1.2.3` | `>=1.2.3 <2.0.0`; for major 0 the left-most non-zero part is fixed: `^0.2.3` is `>=0.2.3 <0.3.0`, `^0.0.3` is `>=0.0.3 <0.0.4` |
| `^1.2`, `^1`, `^0.0`, `^0` | partial: missing parts are 0 for the lower bound and are not fixed: `^1.2` is `>=1.2.0 <2.0.0`, `^0.0` is `>=0.0.0 <0.1.0`, `^0` is `>=0.0.0 <1.0.0` |
| `~1.2.3`, `~1.2` | `>=1.2.3 <1.3.0`, `>=1.2.0 <1.3.0` |
| `~1` | `>=1.0.0 <2.0.0` |
| `1.2`, `1.2.x`, `1`, `1.x` | x-range: `>=1.2.0 <1.3.0`, `>=1.0.0 <2.0.0` |
| `*`, `x` | any version (releases only, see below) |

Hyphen ranges (`1.2.3 - 2.0.0`) are not supported and are reported as an error. A blank range or an empty
alternative is an error. Upper bounds of `^`, `~` and x-ranges exclude prereleases of the bound (`^1.2.3` does not
match `2.0.0-alpha`).

**Prereleases.** A version with a prerelease satisfies a set only if, in addition to satisfying every term, some
term of that same set is written with a prerelease on the same `MAJOR.MINOR.PATCH`. So `>=1.2.3-alpha.1 <2.0.0`
matches `1.2.3-beta` but not `1.3.0-alpha`, and `^1.0.0` matches no prerelease at all.

## 3. Resolution

Input: root dependencies (name to range) and a package source that lists the versions of a name and, for one version,
its dependencies (name to range) and hash. Package names are one namespace for dependencies: a plugin and a project
cannot share a name (`[Zu bestätigen]`). Output: **exactly one version per package name** (chapter 15).

The algorithm is fixed so that results are reproducible and independent of the order in which roots and
dependencies are listed: repeatedly take the alphabetically first package that has constraints but no version yet,
try its versions from the highest down (those satisfying all constraints collected so far), add the chosen
version's dependencies as constraints, and backtrack to the next lower version when a later step fails. A candidate
is skipped when one of its dependency ranges rejects a version that was already chosen.

Failures are reported with a readable message:

- `UNKNOWN_PACKAGE`: `package 'ghost' is not available (required by a@1.0.0)`.
- `NO_SATISFYING_VERSION`: lists every constraint with who required it and the available versions, for example

  ```
  no version of 'c' satisfies all constraints:
    ^1.0.0 (required by a@1.0.0)
    ^2.0.0 (required by b@1.0.0)
  available versions: 1.0.0, 2.0.0
  ```

  When several failures occurred during backtracking, the one reached after the most decisions is reported.
- `CYCLE`: checked on the resolved graph, e.g. `dependency cycle: a -> b -> c -> a`. A cycle is always an error.
- `INVALID_RANGE`: a range that cannot be parsed, with the package that required it.

## 4. Lock file

Written at deploy time next to the project, it fixes the result of the resolution so that a redeployment or rollback
uses the same versions. JSON, UTF-8, `\n` line ends, four-space indentation, all object keys sorted alphabetically,
one trailing newline; the same resolution therefore always produces the same bytes.

```json
{
    "format": 1,
    "roots": {
        "alpha": "~2.0.0",
        "zeta": "^1.0.0"
    },
    "packages": {
        "alpha": {
            "version": "2.0.3",
            "hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "dependencies": {}
        },
        "zeta": {
            "version": "1.4.0",
            "hash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "dependencies": {
                "alpha": "2.0.3"
            }
        }
    }
}
```

`roots` are the ranges the lock was resolved for; `dependencies` of a package hold the **resolved versions** (not
ranges) so that installing from a lock needs no resolution. `hash` is the package hash from `package-format.md`,
section 7. A lock is consistent if every dependency and every root is locked, dependencies point to the locked
version, versions are valid and hashes are 64 lowercase hex characters. Parsing is strict: unknown keys and duplicate
keys are errors.
