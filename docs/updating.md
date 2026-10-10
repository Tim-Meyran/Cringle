# Updating an installed Cringle

An installed Cringle updates itself with `cringle self-update`; the installers (`installer/install.sh`, `installer/install.ps1`) do not have to be run again. The command is part of the CLI (`docs/cli.md`) and works on Linux (systemd) and Windows (services).

The updater writes below the installation root and restarts the services, so it needs the same rights as the installer: run it as root on Linux (`sudo cringle self-update`) and in an administrative shell on Windows.

## Checking for an update

    cringle self-update --check

prints the version of the running installation (`current`), the latest release (`latest`) and whether an update is available. It changes nothing and exits 0 in both cases.

## Updating

    cringle self-update

downloads the latest release, verifies it, unpacks it next to the running version, switches `current` and restarts the Cringle services. `cringle self-update --version 1.2.3` installs a concrete version instead of the latest; that is also the manual way back to an older version.

The updater only installs a release of the same major version without asking. A different major version needs `--allow-major`:

    cringle self-update --allow-major

## Where the release comes from

By default the release files come from the GitHub releases of Cringle (`manifest.json`, `SHA256SUMS` and the archive of the platform). `CRINGLE_RELEASE_BASE_URL` points the updater at another base (for example a mirror or a test release); with a custom base the latest version is read from `<base>/latest/manifest.json` and the files from `<base>/v<version>/`.

## What the updater does

1. It reads `manifest.json` and `SHA256SUMS` of the target release and checks that they agree.
2. It downloads the archive of the platform into a staging directory below the installation root and checks its size and SHA-256.
3. It unpacks the archive into `<root>/<version>`.
4. It switches `current` to the new version (a symbolic link on Linux, a junction on Windows). On Windows the services are stopped first, because a running process cannot be replaced.
5. It restarts the services that were active and waits up to `--timeout` seconds (default 30) for them to come up.

## Rollback

If a service does not come up within the timeout, the updater switches `current` back to the previous version, restarts the services and removes the failed version directory. The command exits with code 1. A failure before the switch (download, checksum, unpack) leaves the installation untouched.

## Old versions

After a successful update the updater keeps the new version and the previous one and removes older version directories. The active version and the version the running process was started from are never removed.

## Exit codes

0 success (also `--check`), 1 the update failed (including a rollback), 2 the command line is wrong, the installation is not an installed distribution, or a major version was refused without `--allow-major`.

Updating the applications that run on Cringle (new versions of projects and plugins, data migrations, rollback) is a different thing: [migration-guide.md](migration-guide.md) and [operations.md](operations.md).
