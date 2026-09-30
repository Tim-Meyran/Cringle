# Releases

A release of Cringle is a GitHub release with one archive per platform, a file with their checksums and a manifest. The archives are the basis for the installers (#58, #59) and the updater (#60). This page describes what is in them, the format of the manifest and how a release is made.

## The archives

`./gradlew cringleDist` writes to `build/dist/`:

| File | Content |
| --- | --- |
| `cringle-<version>-linux.tar.gz` | The distribution with the start scripts for Linux (shell scripts, executable). |
| `cringle-<version>-windows.zip` | The same distribution with the start scripts for Windows (`.bat`). |
| `SHA256SUMS` | One line `<sha256>  <file>` per archive, the format `sha256sum -c SHA256SUMS` checks. |
| `manifest.json` | The version and, for every archive, name, size, SHA-256 and the minimum Java version, see below. |

The archives run on any JVM, so their JARs are the same; they differ only in the start scripts and in the archive format. No JRE is shipped: **a JDK 21 or newer has to be installed**. Nothing is signed; the checksums are what a download is checked with.

### Layout

Both archives have one top directory `cringle-<version>/`:

```
cringle-<version>/
  bin/      start scripts: cringle, cringle-daemon, cringle-management-server (and the same with .bat on Windows)
  lib/      all JARs: the Cringle modules (cli, daemon, management-server, engine, router, repository, common, contract,
            packaging, schema) and every library they need at runtime; every program uses lib/* as its class path
  conf/     reserved for machine-wide settings that the installers write; nothing reads it yet (see conf/README.txt)
  LICENSE   Apache License 2.0
  NOTICE
  VERSION   the version of the release, one line
```

`cringle` is the command line (`docs/cli.md`), `cringle-daemon` the daemon (`docs/daemon-service.md`), `cringle-management-server` the ManagementServer (`docs/management-server.md`). The engine, the router and the repository have no start script of their own: the daemon starts engine processes with its own class path and runs the router inside its own process with `--combined`; the repository (`repository-*.jar`) is in `lib/` as the library the ManagementServer and the CLI use to talk to a repository, and it has no program of its own yet. The JARs of the Cringle modules carry the internal version `0.0.0-SNAPSHOT` in some file names; the version of the release is the one in `VERSION`, in the archive names and in the manifest.

### The start scripts

Every start script finds its installation directory (also through a symbolic link), looks for Java (`$JAVA_HOME/bin/java` if `JAVA_HOME` is set, otherwise `java` from the `PATH`) and starts the program with the class path `lib/*` and the system property `cringle.home`, the directory of the installation. `CRINGLE_JVM_OPTS` adds JVM options.

If there is no Java, or it is older than 21, or its version cannot be read, the script prints a message that says what it found and that a JDK 21 is needed, and ends with **exit code 1** before anything else starts.

`cringle --version` prints `cringle <version>`; the version is read from `VERSION` in the installation directory. A CLI that was not started from a distribution reports `0.0.0-SNAPSHOT`.

## The manifest

`manifest.json` has the version of the release and one entry per archive:

```json
{
  "version": "1.2.3",
  "files": [
    { "name": "cringle-1.2.3-linux.tar.gz", "size": 34501752, "sha256": "95ea...ffe", "minJava": 21 },
    { "name": "cringle-1.2.3-windows.zip", "size": 34507057, "sha256": "b84d...032", "minJava": 21 }
  ]
}
```

`sha256` is the lower-case hexadecimal SHA-256 of the file, `size` its size in bytes, `minJava` the lowest Java version that runs it. `SHA256SUMS` and `manifest.json` themselves are not listed. The files are listed by name in alphabetical order; a consumer should look them up by name and not by position.

## Versions

The version of a release is the Git tag without the leading `v`: the tag `v1.2.3` is version `1.2.3`, `v1.2.3-rc.1` is the pre-release `1.2.3-rc.1`. The build takes it from the property `releaseVersion` (`./gradlew cringleDist -PreleaseVersion=1.2.3`); a build without it is `0.0.0-SNAPSHOT`, so a local `./gradlew cringleDist` writes `cringle-0.0.0-SNAPSHOT-linux.tar.gz` and so on. A version that is not `MAJOR.MINOR.PATCH` with an optional `-pre-release` fails the build.

## Making a release

1. Make sure `master` is what should be released and `./gradlew build` is green.
2. Tag it and push the tag:

   ```bash
   git tag v1.2.3
   git push origin v1.2.3
   ```

3. The workflow `.github/workflows/release.yml` runs on the tag. It checks that the tag is a version tag, builds on Linux with `./gradlew build -PreleaseVersion=1.2.3` (all tests, including `DistributionTest`, which checks the archives of this version), builds the distribution with `./gradlew cringleDist -PreleaseVersion=1.2.3` and creates the GitHub release with the two archives, `SHA256SUMS` and `manifest.json` attached. A tag with a pre-release part makes a pre-release.
4. Check the release: download the archives and `SHA256SUMS` and run `sha256sum -c SHA256SUMS` (on Windows `Get-FileHash` against the manifest), unpack one and run `bin/cringle --version`.

The workflow runs on Linux only: the archives do not depend on the platform they are built on, and the start scripts are templates (`dist/templates`) that the build fills in for both platforms. `DistributionTest` starts the archive of the platform it runs on and reads the other one; a release is therefore only started on Linux by the build that makes it. Run `./gradlew :cli:test` on Windows as well before a release.

### Checking the release workflow

`release.yml` can only run on GitHub, so it is checked by hand with a test tag, in a fork or in the repository when the release process changes:

1. Push a tag like `v0.0.1-test.1`.
2. The run of the workflow `Release` has to go through, and the release `Cringle 0.0.1-test.1` (a pre-release) has to have the four files attached: both archives, `SHA256SUMS` and `manifest.json`.
3. Download them and check them as in step 4 above; `bin/cringle --version` has to print `cringle 0.0.1-test.1`.
4. Delete the test release and the tag: `gh release delete v0.0.1-test.1 --cleanup-tag --yes`.

A tag that is not a version (`vnext`) has to fail the first step of the run with the message that names the tag.

## What is not covered

Installers for Linux (#58) and Windows (#59), the updater (#60), bundles with their own JRE, signing the artifacts and packages for package managers (apt, winget, Chocolatey). The licenses of the bundled libraries are not collected in one file yet; every JAR carries its own.
