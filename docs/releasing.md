# Releases

A release of Cringle is a GitHub release with one archive per platform, a file with their checksums and a manifest. The archives are the basis for the installers (#58, #59) and the updater (#60). This page describes what is in them, the format of the manifest and how a release is made.

## The archives

`./gradlew cringleDist` writes to `build/dist/`:

| File | Content |
| --- | --- |
| `cringle-<version>-linux.tar.gz` | The distribution with the start scripts for Linux (shell scripts, executable). |
| `cringle-<version>-windows.zip` | The same distribution with the start scripts for Windows (`.bat`). |
| `SHA256SUMS` | One line `<sha256>  <file>` per archive, the format `sha256sum -c SHA256SUMS` checks. |
| `winsw.exe` | [WinSW](https://github.com/winsw/winsw) 2.12.0 (`WinSW.NET461.exe`), the service wrapper of `install.ps1`; it has a line in `SHA256SUMS`, but is not in `manifest.json`. |
| `install.sh`, `install.ps1` | The installers for Linux and Windows (`installer/` in the repository), see `docs/daemon-service.md`. |
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

> **Note:** GitHub does not start Actions for this repository at the moment (billing), so `.github/workflows/release.yml` has never run and is **not verified**. Make releases by hand as described below. Do not push test tags.

1. Make sure `master` is what should be released and `./gradlew build` (all modules, all tests) is green.
2. Build the distribution with a concrete version number, for example:

   ```bash
   ./gradlew cringleDist -PreleaseVersion=0.6.0
   curl -fsSL -o build/dist/winsw.exe https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW.NET461.exe
   echo "b5066b7bbdfba1293e5d15cda3caaea88fbeab35bd5b38c41c913d492aadfc4f  build/dist/winsw.exe" | sha256sum -c -
   (cd build/dist && sha256sum winsw.exe >> SHA256SUMS)
   cp installer/install.sh installer/install.ps1 build/dist/
   ```

3. Create and push the tag manually:

   ```bash
   git tag v0.6.0
   git push origin v0.6.0
   ```

4. Verify locally that `bin/cringle --version` prints `cringle 0.6.0`, that an unpacked archive starts, and that the daemon can start engine processes (`DistributionTest` checks all of this for the archive of your platform; `./gradlew :cli:test --tests cringle.cli.DistributionTest -PreleaseVersion=0.6.0`).
5. Upload the artifacts to a GitHub release:

   ```bash
   gh release create v0.6.0 \
     build/dist/cringle-0.6.0-linux.tar.gz \
     build/dist/cringle-0.6.0-windows.zip \
     build/dist/SHA256SUMS \
     build/dist/manifest.json \
     build/dist/winsw.exe \
     build/dist/install.sh \
     build/dist/install.ps1 \
     --generate-notes
   ```

### Notes

- **Linux start scripts:** the archive is checked for layout, shebang, line ends and permissions (755), and the scripts are only run with the `sh` of Git for Windows (error paths, syntax). They have not been run on a real Linux machine.
- The workflow `.github/workflows/release.yml` is kept for when GitHub runs Actions again. Until it has run once on a real tag, treat it as unverified.
- `lib/` holds no test libraries (JUnit, TestKit, test helpers); `DistributionTest` fails if it does.


### Checking `release.yml` (only when GitHub runs Actions again)

Not done yet, and no test tag is to be pushed before GitHub starts Actions for the repository. Afterwards, once and whenever the workflow changes:

1. Push a tag like `v0.0.1-test.1`.
2. The run of the workflow `Release` has to go through, and the pre-release `Cringle 0.0.1-test.1` has to have four files: both archives, `SHA256SUMS` and `manifest.json`.
3. Download them, check `sha256sum -c SHA256SUMS`, unpack one and run `bin/cringle --version`; it has to print `cringle 0.0.1-test.1`.
4. Delete the test release and the tag: `gh release delete v0.0.1-test.1 --cleanup-tag --yes`.

A tag that is not a version (`vnext`) has to fail the first step of the run with a message that names the tag.

## What is not covered

The updater (#60), bundles with their own JRE, signing the artifacts and packages for package managers (apt, winget, Chocolatey). The licenses of the bundled libraries are not collected in one file yet; every JAR carries its own.
