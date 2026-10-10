# Running the Cringle daemon as a service

One daemon runs per machine and starts at boot (Architecture 4.1). This page shows how to install it with the installers (Linux with systemd, Windows) and how to set it up by hand. The daemon is a plain JVM process (`cringle.daemon.MainKt`). The daemon API is mutual TLS (see "mTLS and Enrollment"); there is no unencrypted mode.

## Command line

```
java -cp <classpath> cringle.daemon.MainKt [--home <dir>] [--port <port>] [--router <host:port> | --combined] [--with-management [<port>]] [--web-port <port>] [--with-repository [<port>]]
```

- `--home`: Cringle home (default `~/.cringle`, or `CRINGLE_HOME`). The engine register is `<home>/daemon/engines.json`, engine process logs are `<home>/daemon/logs/<id>.out.log` and `.err.log`.
- `--port`: port of the daemon API (default: any free port; the daemon prints `daemon-port=<port>` on start). Use a fixed port for a service.
- `--combined`: runs the router (registry) of the machine inside the daemon process (Architecture 4.3). Its data lives in `<home>/router/`.
- `--with-management [<port>]` (default 7500), `--web-port <port>`, `--with-repository [<port>]` (default 7600): the daemon starts the management server and the repository as JVM processes of their own (same class path, same `CRINGLE_HOME`) and starts them again with a growing delay (1 s up to 30 s) when they end. The management server runs with `--auth` (user logins; the first start writes the admin token to `<home>/management/bootstrap-token`, see `docs/trust.md`), knows this daemon as machine `local`, and uses the repository and the router of the daemon. These options imply local trust (`--trust-local`): the programs of one home trust each other by their key files, and the daemon makes the identity of the repository before it starts the engines. The output of the programs is in `<home>/daemon/logs/management.out.log`, `management.err.log`, `repository.out.log` and `repository.err.log` (without the token). A daemon on its own (without these options) does not start them; they are one per site, so a machine that is not the site's central one uses `--daemon-only` in the installer.
- `--router host:port`: points the engines to an existing router instead.

The daemon starts engines with the same JVM and class path it runs with. When the daemon stops, it stops the engines it started. The ManagementServer brings them back during recovery (`Recover`, which also runs when the ManagementServer starts): it registers every engine it created with `autostart` (the default, see `CreateEngine`) at the daemon again if the daemon lost it and starts it, one engine after the other, and then deploys and starts the fabrics of the running engines that should run. Engines created without `autostart` stay stopped, and an engine that was stopped on purpose is not started again because stopping is not remembered (`autostart` is a setting, not the current state). The daemon API has a call `StartAllEngines`, but recovery does not use it.

## Installer

### Quick install

A JDK 21 or newer has to be installed. Copy one line, and the daemon, the management server (web interface on 8443, user logins) and the repository run as a service. The links always point to the latest release.

Linux (systemd):

```bash
curl -fsSL https://github.com/Tim-Meyran/Cringle/releases/latest/download/install.sh | sudo sh -s -- --start
```

Windows (PowerShell; the script asks for administrative rights, the second command runs it without being stopped by the execution policy):

```powershell
irm https://github.com/Tim-Meyran/Cringle/releases/latest/download/install.ps1 -OutFile install.ps1; powershell -ExecutionPolicy Bypass -File .\install.ps1 -Start
```

A fixed version (here `0.0.3`; every release is at `https://github.com/Tim-Meyran/Cringle/releases/tag/v<version>`):

```bash
curl -fsSL https://github.com/Tim-Meyran/Cringle/releases/download/v0.0.3/install.sh | sudo sh -s -- --release 0.0.3 --start
```

```powershell
irm https://github.com/Tim-Meyran/Cringle/releases/download/v0.0.3/install.ps1 -OutFile install.ps1; powershell -ExecutionPolicy Bypass -File .\install.ps1 -Version 0.0.3 -Start
```

**Questions.** On the first installation, in a terminal, the installer asks what no option gave: whether to run the management server and the repository on this machine, their ports, the port of the daemon, and whether to listen on all network interfaces (Enter takes the value in the brackets). Without a terminal nothing is asked: the options or the defaults count, so `curl ... | sudo sh` in a script, a container or CI works as before. The answers are read from `/dev/tty`, so the questions also work when the script comes through a pipe; on Windows they are asked in the window of the person before the elevated run starts. `--no-ask` (`-NoAsk`) skips them, an option skips its question, a later installation does not ask again (it keeps the settings, an option changes one).

The daemon alone (a machine that is not the central one of a site): add `--daemon-only` (Linux) or `-DaemonOnly` (Windows). Only the repository, or only the management server: `--components repository` or `--components management` (`-Components`; the default is `management,repository`). Remove everything but the data: `... | sudo sh -s -- --uninstall` and `powershell -ExecutionPolicy Bypass -File .\install.ps1 -Uninstall`.

**Settings (the configuration manager, #314 to #318).** The address, the ports and what the daemon runs live in one key-value store per machine, the file `<home>/config/cringle.conf` (`/var/lib/cringle/config/cringle.conf`, on Windows `%ProgramData%\Cringle\config\cringle.conf`): lines `key=value`, comments and unknown lines are kept. The daemon owns it; the installers, `cringle setup`, `cringle config` and the web interface write it.

| Key | Default | What it is | A change restarts |
|---|---|---|---|
| `bind` | `loopback` | where the servers of the machine listen: `loopback` or `all` (every network interface) | the daemon |
| `components` | `none` (the installers write `management,repository`) | what the daemon runs besides itself: `management`, `repository`, both, or `none` | the programs that start or stop |
| `daemon.port` | `7400` | port of the daemon | the daemon |
| `management.port` | `7500` | port of the management server (gRPC: CLI and daemons) | the management server |
| `management.web.port` | `8443` | port of the web interface | the management server |
| `management.web.url` | empty | public address for login links and QR codes (`https://host:port`); empty: the `Host` header of the request | the management server |
| `repository.port` | `7600` | port of the package repository | the repository and the management server |

The daemon reads the store when it starts. Arguments of the daemon (`--port`, `--bind`, `--with-management`, `--with-repository`, `--web-port`) **win** over the store; what they do not give comes from the store, then from the default. The unit and the service of the current installers start the daemon without arguments, so the store decides; a daemon of an older installation that still has the arguments keeps them until the installer runs again, and `cringle config` says that an argument wins. **First start:** when the file does not exist the daemon makes it from the old `CRINGLE_BIND`, `CRINGLE_COMPONENTS`, `CRINGLE_DAEMON_PORT`, `CRINGLE_MANAGEMENT_PORT`, `CRINGLE_WEB_PORT` and `CRINGLE_REPOSITORY_PORT` variables and the arguments it was started with, once; the variables are not read again. The store holds no secrets.

Change a setting, from this machine or through the management server (the daemon restarts what the key affects; a change of the daemon itself is saved and the answer says to restart it):

```bash
cringle config list [machine]                       # machine defaults to local
cringle config set [machine] management.web.port 9443
cringle config unset [machine] management.web.url   # the default is in effect again
sudo cringle setup --bind all --web-port 9443       # this machine, without a management server; asks without options
```

The installer options (`--bind`, `--components`, `--port`, `--web-port`, `--web-url`, `--repository-port`, `--daemon-port`; Windows: `-Bind` ...) and its questions write the same keys. Rights: reading needs `READ`, changing `ADMINISTER`, for the machine or for the function `config` (`users.md`). `bind` and the other settings of one machine do not reach other machines: every daemon has its own store.

After the installation, in a new terminal (the `PATH` entry is only seen by new shells):

```bash
sudo cat /var/lib/cringle/management/bootstrap-token        # Windows: type C:\ProgramData\Cringle\management\bootstrap-token
cringle login --server 127.0.0.1:7500 --yes --token-file <file with the token>
cringle machine list                                          # the machine "local" is this daemon
```

`--yes` accepts the key of the server that `login` shows: on the same machine that is the server just installed. The web interface is at `https://127.0.0.1:8443`; log in with the same token (the file is deleted at the first login, the token keeps working). Details and options follow below.

The installers set up the daemon as a service from a release (`docs/releasing.md`). Both scripts are attached to every release; download the one for your system, read it if you like, and run it. They download the archive of the release, check its SHA-256 against `SHA256SUMS` and stop before anything is installed if it does not match. A JDK 21 or newer has to be on the machine (the Linux installer only warns if it finds none). The services run on the default ports (daemon 7400, management server 7500).

### Linux (systemd)

```
curl -fsSLO https://github.com/Tim-Meyran/Cringle/releases/latest/download/install.sh
sudo sh install.sh [--release <version>] [--components <list>] [--bind loopback|all] [--port <n>] [--web-port <n>] [--repository-port <n>] [--daemon-port <n>] [--no-ask] [--start]
```

- `--release <version>`: install this version (default: the latest release).
- `--components <list>`: what the daemon runs besides itself: `management`, `repository`, both separated by a comma, or `none` (default: both). `--daemon-only` is `--components none`. `--no-ask`: do not ask the questions (see above). The options for the address and the ports are described above.
- `--daemon-only`: run the daemon alone. By default the daemon service also runs the management server (port 7500, web interface 8443, with user logins) and the repository (port 7600) as programs it supervises (`--with-management --web-port --with-repository`, see below). An older `cringle-management.service` is removed on upgrade. `--with-management` is accepted and does nothing.
- `--start`: start the services; without it they are enabled (start at boot), but not started.
- `--uninstall`: stop and remove the units and the symlinks and delete `/opt/cringle`; `/var/lib/cringle` (data) and `/etc/cringle` (configuration) stay. `--uninstall --purge` removes them and the user `cringle` too; `--purge` alone is an error.

It unpacks the archive to `/opt/cringle/<version>` and points the symlink `/opt/cringle/current` to it, creates the system user `cringle`, `/var/lib/cringle` (`CRINGLE_HOME`) and `/etc/cringle`, writes the units to `/etc/systemd/system/` and links `/usr/local/bin/cringle`. Run it again with another `--release` to switch versions: only `current` and the units change, data and configuration stay, and the older versions stay in `/opt/cringle` until the next `--uninstall`. A running service keeps running the old version until you restart it (`systemctl restart cringle-daemon`).

`/etc/cringle/cringle.env` is read by the units (`EnvironmentFile`); put `CRINGLE_JVM_OPTS` or `JAVA_HOME` there. The installer creates it once and never overwrites it. The units are overwritten by every installation: change them with a drop-in (`systemctl edit cringle-daemon`).

`installer/tests/test-install.sh` tests the script against a fake release, and `installer/tests/test-install-container.sh` runs it in Ubuntu 24.04 and Debian stable containers (also in the CI): once below a temporary root and once as a real installation (user, directories, units, `cringle --version` as a normal user). The containers do not run systemd, so enabling the units is checked as links, and starting the services is not tested.

### Windows (service)

In a PowerShell (5.1 or newer; it asks for administrative rights when needed):

```
Invoke-WebRequest https://github.com/Tim-Meyran/Cringle/releases/latest/download/install.ps1 -OutFile install.ps1
.\install.ps1 [-Version <version>] [-Components <list>] [-Bind loopback|all] [-Port <n>] [-WebPort <n>] [-RepositoryPort <n>] [-DaemonPort <n>] [-NoAsk] [-Start]
```

- `-Version <version>`: install this version (default: the latest release).
- `-Components <list>`: what the daemon runs besides itself: `management`, `repository`, both separated by a comma, or `none` (default: both). `-DaemonOnly` is `-Components none`. `-NoAsk`: do not ask the questions (see above).
- `-DaemonOnly`: run the daemon alone. By default the service `Cringle Daemon` also runs the management server (7500, web interface 8443, user logins) and the repository (7600). An older service `Cringle Management Server` is removed on upgrade. `-WithManagement` is accepted and does nothing.
- `-Start`: start the services; without it they start at the next boot.
- `-Uninstall`: stop and remove the services, the `PATH` entry and `%ProgramFiles%\Cringle`; the data in `%ProgramData%\Cringle` stays. `-Uninstall -Purge` removes it too; `-Purge` alone is an error.

In a normal PowerShell the script asks for administrative rights (the Windows confirmation dialog), runs itself again elevated with the same arguments, shows its output and ends with its exit code; `-NoElevate` stops with exit code 1 and a message instead, and declining the dialog ends with exit code 1. It unpacks the archive to `%ProgramFiles%\Cringle\<version>`, makes the junction `%ProgramFiles%\Cringle\current` point to it, registers the service `Cringle Daemon` (id `cringle-daemon`) with WinSW (in `%ProgramFiles%\Cringle\service\`; the pinned WinSW is a file of the release and is checked like the archive), uses `%ProgramData%\Cringle` as `CRINGLE_HOME` (the logs of the services are in `logs\` there) and adds `%ProgramFiles%\Cringle\current\bin` to the system `PATH` (open a new shell to see it). The services run as `LocalSystem`.

Running files cannot be replaced on Windows, so an upgrade stops the services, unpacks the new version next to the old one, switches the junction and starts again the services that were running. Locked files of an old version do not matter: old versions are removed when possible and stay otherwise. Options for tests: `-BaseUrl`, `-InstallRoot`, `-DataRoot` and `-NoService` (only the files: no service, no `PATH` entry, no administrative rights needed). `installer/tests/InstallerTest.ps1` uses them; in an administrative PowerShell (as on the CI runner) it also tests the services and `PATH`, otherwise it reports them as skipped.

### A locally built Cringle (Windows)

To run what you built yourself instead of a release, use Gradle in a PowerShell (the checkout is the working directory; a JDK 21 has to be installed). The installer asks for administrative rights itself (the Windows confirmation dialog appears; its output is shown afterwards):

```
.\gradlew.bat cringleInstallLocal --console=plain --no-daemon    # build cringleDist and install it
.\gradlew.bat cringleUpdateLocal --console=plain --no-daemon     # build again and install over the existing installation
.\gradlew.bat cringleUninstallLocal --console=plain --no-daemon   # remove it (the data stays)
```

`cringleInstallLocal` and `cringleUpdateLocal` build the distribution (`cringleDist`, version `0.0.0-SNAPSHOT` unless you give `-PreleaseVersion=1.2.3`) and run `installer\install.ps1 -FromBuild build\dist -Version <version>`, so a local build is installed exactly like a release: same folders, services and `PATH` entry. `cringleUpdateLocal` stops with a message if nothing is installed; the services that were running are started again after the switch. The same version is replaced, so a new build of `0.0.0-SNAPSHOT` simply overwrites the old one. Options as `-P` properties: `-PnoStart` (the services are started after the installation by default; with this they start at the next boot only), `-PdaemonOnly`, `-PnoService` (files only, no administrative rights needed), `-PinstallRoot=<dir>`, `-PdataRoot=<dir>`, and for `cringleUninstallLocal` `-Ppurge`.

Without Gradle: `.\gradlew.bat cringleDist`, then `.\installer\install.ps1 -FromBuild build\dist` (`-Version` is needed only if `build\dist` holds several versions). WinSW is taken from `build\dist\winsw.exe` if it is there and otherwise downloaded (pinned version, checksum checked, as in a release). On Linux run `sudo installer/install.sh` with `CRINGLE_RELEASE_BASE_URL=file://<dir>` (a folder `v<version>` with the archive and `SHA256SUMS`); Gradle tasks for Linux do not exist yet.

### A locally built Cringle (Linux)

The same three Gradle tasks work on Linux. They install below `/opt`, `/etc` and `/var`, so they need root: start Gradle with `sudo` (root needs a JDK 21: `sudo env JAVA_HOME=$JAVA_HOME ./gradlew ...`), or leave the `sudo` out if your user may run `sudo` without a password (the task then calls `sudo -n` itself):

```
sudo ./gradlew cringleInstallLocal --console=plain --no-daemon     # build cringleDist and install it
sudo ./gradlew cringleUpdateLocal --console=plain --no-daemon      # build again and install over the existing installation, restart the services
sudo ./gradlew cringleUninstallLocal --console=plain --no-daemon   # remove it (the data stays; -Ppurge removes it too)
```

The tasks build `cringleDist` and run `installer/install.sh --from-build build/dist --release <version> --no-ask --start`: nothing is downloaded, the checksum of the archive is checked like for a release, and the settings are the ones the installation has (or the defaults; use `cringle config` or `cringle setup` afterwards). Options: `-PreleaseVersion=1.2.3`, `-PdaemonOnly`, `-PnoStart` (enabled, not started), `-Ppurge` (with the uninstall task). `-PinstallRoot=<folder>` makes it a trial installation below that folder, without root and without systemd (what the tests of the installer do). Without Gradle: `./gradlew cringleDist`, then `sudo installer/install.sh --from-build build/dist`.

## Manual setup

The installers do what is described here; you only need it for a setup of your own.

### systemd

`/etc/systemd/system/cringle-daemon.service`:

```ini
[Unit]
Description=Cringle daemon
After=network.target

[Service]
User=cringle
Environment=CRINGLE_HOME=/var/lib/cringle
ExecStart=/opt/cringle/current/bin/cringle-daemon --port 7400 --combined
Restart=on-failure
RestartSec=5
# give engines time to stop gracefully
TimeoutStopSec=60

[Install]
WantedBy=multi-user.target
```

```
sudo systemctl daemon-reload
sudo systemctl enable --now cringle-daemon
```

### Windows service

Use a service wrapper such as [WinSW](https://github.com/winsw/winsw). `cringle-daemon.xml` next to `cringle-daemon.exe` (the renamed WinSW binary):

```xml
<service>
  <id>cringle-daemon</id>
  <name>Cringle Daemon</name>
  <executable>%SystemRoot%\System32\cmd.exe</executable>
  <arguments>/c ""C:\Program Files\Cringle\current\bin\cringle-daemon.bat" --port 7400 --combined"</arguments>
  <env name="CRINGLE_HOME" value="C:\ProgramData\Cringle"/>
  <onfailure action="restart" delay="5 sec"/>
  <stoptimeout>60 sec</stoptimeout>
</service>
```

```
cringle-daemon.exe install
cringle-daemon.exe start
```

## mTLS and Enrollment

The daemon API, the channel from the daemon to every engine and the engine-router and daemon-router channels use mutual TLS (#7). Nothing is plaintext any more.

- **Daemon API:** the daemon presents its identity and accepts only peers in `<home>/daemon/trust.json` (`COMPONENT`: the ManagementServer, the CLI, a repository). A peer without an entry is refused at the handshake.
- **Engine identity:** when an engine is created (or loaded from the register after an update) the daemon creates its identity in `<engineDir>/certs` with the subject `CN=engine:<id>` and enters its key as `ENGINE` into the daemon trust store. The daemon calls the engine API with its own identity; the engine accepts it because of the trust file below.
- **Engine trust file:** before every start the daemon writes `<engineDir>/trust.json` with the daemon, every `COMPONENT` of the daemon trust store and, if there is one, the router. The engine management API accepts only those.

- The daemon owns its own `Identity` (under `<home>/daemon`) and `TrustStore` (under `<home>/daemon/trust.json`).
- In combined mode, the embedded router runs with mTLS using its own `Identity` (under `<home>/router`) and `TrustStore` (under `<home>/router/trust.json`). Mutual trust is set up automatically at start: the router trusts the daemon (`COMPONENT`) and the daemon trusts the router (`ROUTER`).
- In separate router mode, the daemon announces engines via `PrepareEngine` over mTLS using its identity and trust store. The router must already trust the daemon (manual trust, #82).
- Before starting an engine, the daemon writes `<engineDir>/trust.json` with the router's fingerprint (looked up in the daemon's trust store by address). If the router is not in the trust store, the start fails with a clear message.

The enrollment secret is generated by the `EngineSupervisor` and passed to the engine via the `CRINGLE_ENROLLMENT_SECRET` environment variable (hex-encoded). The engine uses it during its `RegisterEngine` call to prove it is authorized. The secret is single-use and expires after successful registration.
