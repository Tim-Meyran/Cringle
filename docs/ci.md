# Continuous integration

The workflows in `.github/workflows/` run on **self-hosted runners** of the repository (Settings, Actions, Runners), not on GitHub-hosted ones (the account has no hosted minutes, see the note in `AGENTS.md`).

| Runner | Labels | Used by |
|---|---|---|
| Linux (a Docker container on Rocky Linux) | `self-hosted`, `Linux`, `X64`, `rocky` | `CI` (Build & Test linux, Installer ubuntu and debian), `Release`, `Issue housekeeping` |
| Windows | `self-hosted`, `Windows`, `X64` | `CI` (Build & Test windows, Installer windows) |

A job names its runner with `runs-on: [self-hosted, Linux, X64, rocky]` (or the Windows labels). Change the labels in the three workflow files if a runner gets other ones. A job that finds no online runner with all its labels waits in the queue.

## Workflows

- **CI** (`ci.yml`, on every push and pull request): `./gradlew build integrationTest --no-daemon` on Linux and on Windows, and the tests of `install.sh` (in containers of Ubuntu 24.04 and Debian stable) and of `install.ps1`. A newer push to the same ref cancels the run that is going (`concurrency`): a runner runs one job at a time and a full build takes about 20 minutes.
- **Release** (`release.yml`, on a tag `v*`): builds with `-PreleaseVersion`, adds WinSW and the installers and creates the GitHub release with the API of `actions/github-script`, so the runner needs no `gh`. See `docs/releasing.md`.
- **Issue housekeeping** (`issue-housekeeping.yml`, an issue is closed): removes the label `in-progress`; also with `actions/github-script`.

## What the Linux runner has to provide

- `git`, `curl`, `sha256sum` and `tar` (the JDK 21 comes from `actions/setup-java`, Gradle from `gradle/actions/setup-gradle`; both need network access to download).
- For `installer-linux`: the `docker` CLI and access to a Docker daemon (the socket of the host mounted into the runner container, or Docker-in-Docker). The test mounts `installer/` with `-v <workspace>/installer:/src`, and the daemon resolves that path on **its** host: with the socket of the host, the workspace of the runner has to be mounted at the same path on the host and in the container (`-v /actions-runner/_work:/actions-runner/_work`), or the job fails with an empty `/src`.
- A user that may start processes and open loopback ports: the tests start engines, daemons and routers on `127.0.0.1`.

## What the Windows runner has to provide

Git for Windows (`bash`, `sha256sum`), PowerShell 5.1 and administrative rights (`install.ps1` registers a service and changes `PATH`, and its test checks that), and a JDK 21 is installed by `actions/setup-java`.
