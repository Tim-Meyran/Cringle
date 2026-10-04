# Running the Cringle daemon as a service

One daemon runs per machine and starts at boot (Architecture 4.1). This page shows how to install it using the Linux installer script and as a Windows service. The daemon is a plain JVM process (`cringle.daemon.MainKt`); until trust management exists (#13) it must be started with `--insecure-dev-mode` and listens on the loopback interface only.

## Linux Installer

The primary method for installing the Cringle daemon on Linux is the `install.sh` script. This script automates the installation process, including creating the systemd service.

### Download and Installation

The `install.sh` script is attached to releases. You can download it directly:

```bash
wget https://github.com/Tim-Meyran/Cringle/releases/latest/download/install.sh
chmod +x install.sh
```

### Command-line Options

The installer provides several command-line options:

- `--version`: Display the installer version and exit
- `--with-management`: Install the ManagementServer alongside the daemon
- `--release`: Install this version (default: latest)
- `--start`: Start the daemon immediately after installation
- `--uninstall`: Remove the Cringle daemon installation
- `--purge`: Completely remove all Cringle data and configuration

### Example Installation Commands

Basic installation:
```bash
sudo ./install.sh
```

Install with ManagementServer:
```bash
sudo ./install.sh --with-management
```

Install with release build:
```bash
sudo ./install.sh --release 1.2.3
```

Start daemon immediately:
```bash
sudo ./install.sh --start
```

Uninstall:
```bash
sudo ./install.sh --uninstall --purge
```

**Note**: `--purge` requires `--uninstall`

### Verification Steps

After installation, verify that the daemon is running correctly:

```bash
# Check service status
sudo systemctl status cringle-daemon

# View daemon logs
journalctl -u cringle-daemon -f

# Verify daemon version
cringle --version
```

The installer creates a systemd service at `/etc/systemd/system/cringle-daemon.service` and sets up the Cringle home directory at `/var/lib/cringle` by default.

## Manual setup (without the installer)

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
