# Continuous integration

The workflows in `.github/workflows/` run on **self-hosted runners** of the repository (Settings, Actions, Runners), not on GitHub-hosted ones (the account has no hosted minutes, see the note in `AGENTS.md`).

| Runner | Labels | Used by |
|---|---|---|
| Linux (a Docker container on Rocky Linux) | `self-hosted`, `Linux`, `X64`, `rocky` | `CI` (fast tests and integration tests linux), `Installer` (ubuntu and debian), `Release`, `Issue housekeeping` |
| Windows | `self-hosted`, `Windows`, `X64` | `CI` (fast tests windows, integration tests windows at night), `Installer` (windows) |

A job names its runner with `runs-on: [self-hosted, Linux, X64, rocky]` (or the Windows labels). Change the labels in the four workflow files if a runner gets other ones. A job that finds no online runner with all its labels waits in the queue.

## Workflows

- **CI** (`ci.yml`): a full `build integrationTest` takes about 20 minutes and one runner runs one job at a time, so a trigger runs only what it needs:

  | Trigger | Fast suite (`build`) | Integration tests (`integrationTest`) |
  |---|---|---|
  | pull request | Linux and Windows | Linux, only with the label `full-ci` (use it for a risky change) |
  | push to `master` | Linux and Windows | Linux |
  | nightly (01:00 UTC) and `workflow_dispatch` | Linux and Windows | Linux and Windows |
  | push to another branch | nothing (its pull request runs) | nothing |

  Changes of documents only (`docs/**`, `*.md`, `LICENSE`, `NOTICE`) run nothing; because of that, do not make the checks of `CI` *required* in the branch protection (a required check that never reports blocks the merge). The Gradle commands use `--build-cache`; it helps only if the Gradle home of the runner is kept between runs. A newer push to the same ref cancels the run that is going (`concurrency`). The agent runs `build integrationTest` locally and states it in the pull request (`AGENTS.md`); `master` and the nightly run catch the rest.
- **Installer** (`installer.yml`): the tests of `install.sh` (in containers of Ubuntu 24.04 and Debian stable) and of `install.ps1` (administrative runner). They run when `installer/**` changes (pull request and `master`), nightly (01:30 UTC) and by hand.: a runner runs one job at a time and a full build takes about 20 minutes.
- **Release** (`release.yml`, on a tag `v*`): does not run all integration tests again (they take an hour on the runner, and the commit was tested by `CI` on `master`): it fails when `CI` failed or still runs for the commit of the tag (no run at all is a warning), runs the fast suite and `DistributionTest` (the archives that are published) with `-PreleaseVersion`, builds with `-PreleaseVersion`, adds WinSW and the installers and creates the GitHub release with the API of `actions/github-script`, so the runner needs no `gh`. See `docs/releasing.md`.
- **Issue housekeeping** (`issue-housekeeping.yml`, an issue is closed): removes the label `in-progress`; also with `actions/github-script`.

## What the Linux runner has to provide

- `git`, `curl`, `sha256sum` and `tar` (the JDK 21 comes from `actions/setup-java`, Gradle from `gradle/actions/setup-gradle`; both need network access to download).
- For `installer-linux`: the `docker` CLI and access to a Docker daemon (the socket of the host mounted into the runner container, or Docker-in-Docker). The test mounts `installer/` with `-v <workspace>/installer:/src`, and the daemon resolves that path on **its** host: with the socket of the host, the workspace of the runner has to be mounted at the same path on the host and in the container (`-v /actions-runner/_work:/actions-runner/_work`), or the job fails with an empty `/src`.
- A user that may start processes and open loopback ports: the tests start engines, daemons and routers on `127.0.0.1`.

## What the Windows runner has to provide

Git for Windows (`bash`, `sha256sum`), PowerShell 5.1 and administrative rights (`install.ps1` registers a service and changes `PATH`, and its test checks that), and a JDK 21 is installed by `actions/setup-java`.
