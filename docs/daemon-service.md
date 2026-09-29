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

The daemon starts engines with the same JVM and class path it runs with. When the daemon stops, it stops the engines it started; the ManagementServer starts them again with `StartAllEngines`.

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
