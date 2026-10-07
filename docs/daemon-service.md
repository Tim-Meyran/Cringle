# Running the Cringle daemon as a service

One daemon runs per machine and starts at boot (Architecture 4.1). This page shows how to install it with the installers (Linux with systemd, Windows) and how to set it up by hand. The daemon is a plain JVM process (`cringle.daemon.MainKt`). The daemon API is mutual TLS (see "mTLS and Enrollment"); `--insecure-dev-mode` is accepted and ignored and goes away with #5.

## Command line

```
java -cp <classpath> cringle.daemon.MainKt [--home <dir>] [--port <port>] [--router <host:port> | --combined]
```

- `--home`: Cringle home (default `~/.cringle`, or `CRINGLE_HOME`). The engine register is `<home>/daemon/engines.json`, engine process logs are `<home>/daemon/logs/<id>.out.log` and `.err.log`.
- `--port`: port of the daemon API (default: any free port; the daemon prints `daemon-port=<port>` on start). Use a fixed port for a service.
- `--combined`: runs the router (registry) of the machine inside the daemon process (Architecture 4.3). Its data lives in `<home>/router/`.
- `--router host:port`: points the engines to an existing router instead.

The daemon starts engines with the same JVM and class path it runs with. When the daemon stops, it stops the engines it started. The ManagementServer brings them back during recovery (`Recover`, which also runs when the ManagementServer starts): it registers every engine it created with `autostart` (the default, see `CreateEngine`) at the daemon again if the daemon lost it and starts it, one engine after the other, and then deploys and starts the fabrics of the running engines that should run. Engines created without `autostart` stay stopped, and an engine that was stopped on purpose is not started again because stopping is not remembered (`autostart` is a setting, not the current state). The daemon API has a call `StartAllEngines`, but recovery does not use it.

## Installer

The installers set up the daemon as a service from a release (`docs/releasing.md`). Both scripts are attached to every release; download the one for your system, read it if you like, and run it. They download the archive of the release, check its SHA-256 against `SHA256SUMS` and stop before anything is installed if it does not match. A JDK 21 or newer has to be on the machine (the Linux installer only warns if it finds none). Until trust management exists the services run with `--insecure-dev-mode` on the default ports (daemon 7400, management server 7500).

### Linux (systemd)

```
curl -fsSLO https://github.com/Tim-Meyran/Cringle/releases/latest/download/install.sh
sudo sh install.sh [--release <version>] [--with-management] [--start]
```

- `--release <version>`: install this version (default: the latest release).
- `--with-management`: also install `cringle-management.service`.
- `--start`: start the services; without it they are enabled (start at boot), but not started.
- `--uninstall`: stop and remove the units and the symlinks and delete `/opt/cringle`; `/var/lib/cringle` (data) and `/etc/cringle` (configuration) stay. `--uninstall --purge` removes them and the user `cringle` too; `--purge` alone is an error.

It unpacks the archive to `/opt/cringle/<version>` and points the symlink `/opt/cringle/current` to it, creates the system user `cringle`, `/var/lib/cringle` (`CRINGLE_HOME`) and `/etc/cringle`, writes the units to `/etc/systemd/system/` and links `/usr/local/bin/cringle`. Run it again with another `--release` to switch versions: only `current` and the units change, data and configuration stay, and the older versions stay in `/opt/cringle` until the next `--uninstall`. A running service keeps running the old version until you restart it (`systemctl restart cringle-daemon`).

`/etc/cringle/cringle.env` is read by the units (`EnvironmentFile`); put `CRINGLE_JVM_OPTS` or `JAVA_HOME` there. The installer creates it once and never overwrites it. The units are overwritten by every installation: change them with a drop-in (`systemctl edit cringle-daemon`).

`installer/tests/test-install.sh` tests the script against a fake release, and `installer/tests/test-install-container.sh` runs it in Ubuntu 24.04 and Debian stable containers (also in the CI): once below a temporary root and once as a real installation (user, directories, units, `cringle --version` as a normal user). The containers do not run systemd, so enabling the units is checked as links, and starting the services is not tested.

### Windows (service)

In an administrative PowerShell (5.1 or newer):

```
Invoke-WebRequest https://github.com/Tim-Meyran/Cringle/releases/latest/download/install.ps1 -OutFile install.ps1
.\install.ps1 [-Version <version>] [-WithManagement] [-Start]
```

- `-Version <version>`: install this version (default: the latest release).
- `-WithManagement`: also register the service `Cringle Management Server`.
- `-Start`: start the services; without it they start at the next boot.
- `-Uninstall`: stop and remove the services, the `PATH` entry and `%ProgramFiles%\Cringle`; the data in `%ProgramData%\Cringle` stays. `-Uninstall -Purge` removes it too; `-Purge` alone is an error.

Without administrative rights the script stops with exit code 1 and a message. It unpacks the archive to `%ProgramFiles%\Cringle\<version>`, makes the junction `%ProgramFiles%\Cringle\current` point to it, registers the service `Cringle Daemon` (id `cringle-daemon`) with WinSW (in `%ProgramFiles%\Cringle\service\`; the pinned WinSW is a file of the release and is checked like the archive), uses `%ProgramData%\Cringle` as `CRINGLE_HOME` (the logs of the services are in `logs\` there) and adds `%ProgramFiles%\Cringle\current\bin` to the system `PATH` (open a new shell to see it). The services run as `LocalSystem`.

Running files cannot be replaced on Windows, so an upgrade stops the services, unpacks the new version next to the old one, switches the junction and starts again the services that were running. Locked files of an old version do not matter: old versions are removed when possible and stay otherwise. Options for tests: `-BaseUrl`, `-InstallRoot`, `-DataRoot` and `-NoService` (only the files: no service, no `PATH` entry, no administrative rights needed). `installer/tests/InstallerTest.ps1` uses them; in an administrative PowerShell (as on the CI runner) it also tests the services and `PATH`, otherwise it reports them as skipped.

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
