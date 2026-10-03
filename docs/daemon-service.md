# Running the Cringle daemon as a service

One daemon runs per machine and starts at boot (Architecture 4.1). This page shows how to install it with systemd and as a Windows service. The daemon is a plain JVM process (`cringle.daemon.MainKt`); until trust management exists (#13) it must be started with `--insecure-dev-mode` and listens on the loopback interface only.

## Command line

```
java -cp <classpath> cringle.daemon.MainKt [--home <dir>] [--port <port>] [--router <host:port> | --combined] --insecure-dev-mode
```

- `--home`: Cringle home (default `~/.cringle`, or `CRINGLE_HOME`). The engine register is `<home>/daemon/engines.json`, engine process logs are `<home>/daemon/logs/<id>.out.log` and `.err.log`.
- `--port`: port of the daemon API (default: any free port; the daemon prints `daemon-port=<port>` on start). Use a fixed port for a service.
- `--combined`: runs the router (registry) of the machine inside the daemon process (Architecture 4.3). Its data lives in `<home>/router/`.
- `--router host:port`: points the engines to an existing router instead.

The daemon starts engines with the same JVM and class path it runs with. When the daemon stops, it stops the engines it started. The ManagementServer brings them back during recovery (`Recover`, which also runs when the ManagementServer starts): it registers every engine it created with `autostart` (the default, see `CreateEngine`) at the daemon again if the daemon lost it and starts it, one engine after the other, and then deploys and starts the fabrics of the running engines that should run. Engines created without `autostart` stay stopped, and an engine that was stopped on purpose is not started again because stopping is not remembered (`autostart` is a setting, not the current state). The daemon API has a call `StartAllEngines`, but recovery does not use it.

## systemd

`/etc/systemd/system/cringle-daemon.service`:

```ini
[Unit]
Description=Cringle daemon
After=network.target

[Service]
User=cringle
Environment=CRINGLE_HOME=/var/lib/cringle
ExecStart=/usr/bin/java -cp "/opt/cringle/lib/*" cringle.daemon.MainKt --port 7400 --combined --insecure-dev-mode
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

## Windows service

### Manual installation with WinSW

Use a service wrapper such as [WinSW](https://github.com/winsw/winsw). `cringle-daemon.xml` next to `cringle-daemon.exe` (the renamed WinSW binary):

```xml
<service>
  <id>cringle-daemon</id>
  <name>Cringle daemon</name>
  <executable>java</executable>
  <arguments>-cp "C:\cringle\lib\*" cringle.daemon.MainKt --port 7400 --combined --insecure-dev-mode</arguments>
  <env name="CRINGLE_HOME" value="C:\cringle\data"/>
  <onfailure action="restart" delay="5 sec"/>
  <stoptimeout>60 sec</stoptimeout>
</service>
```

```
cringle-daemon.exe install
cringle-daemon.exe start
```

### Automated installation

For a simpler installation, use the provided PowerShell installer script. The script is attached to releases as `install.ps1` and can be downloaded from the GitHub releases page.

```powershell
./install.ps1 -Version "1.0.0"
```

The installer:
- Requires administrative privileges (run PowerShell as Administrator)
- Installs the daemon as a Windows service named "Cringle Daemon"
- Sets `CRINGLE_HOME` to `%ProgramData%\Cringle` by default
- Uses `--insecure-dev-mode` for development

Available parameters:
- `-Version`: Specify the daemon version to install (default: latest)
- `-WithManagement`: Include the ManagementServer for engine recovery
- `-Uninstall`: Remove the service
- `-Purge`: Remove service and configuration data (use with -Uninstall)
- `-BaseUrl`: Base URL for testing (overrides default repository)

## mTLS and Enrollment

When starting an engine with mTLS (not using `--insecure-dev-mode`), the daemon performs the following steps:

1. **Generate Enrollment Secret**: The daemon generates a random byte array as the enrollment secret.
2. **Hash the Secret**: The secret is hashed using SHA-256 to create a secret hash.
3. **Prepare Engine**: The daemon calls `PrepareEngine` on the router with the engine ID and the secret hash.
4. **Pass Secret to Engine**: The enrollment secret is passed to the engine via the `CRINGLE_ENROLLMENT_SECRET` environment variable. The secret is hex-encoded (each byte as two lowercase hex digits).
5. **Engine Registration**: The engine uses the secret during its `RegisterEngine` call to prove it is authorized.

The enrollment secret is single-use and expires after successful registration. If the engine fails to register, the secret remains valid until the daemon restarts (announcements are kept in memory).

**Enrollment Secret Environment Variable**

The `CRINGLE_ENROLLMENT_SECRET` environment variable contains the hex-encoded enrollment secret. For example, if the secret is the byte array `[0xAB, 0xCD, 0xEF]`, the environment variable will contain `abcdef`.

This variable is only set when starting engines with mTLS. Engines using `--insecure-dev-mode` do not receive this variable.
