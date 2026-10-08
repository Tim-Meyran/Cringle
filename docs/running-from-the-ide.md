# Running the programs from the IDE

Every program has a Gradle task in the group `cringle`, so that the IDE can run and debug it from the Gradle tool window (in IntelliJ: *Gradle > Tasks > cringle*, *Run* or *Debug*). They are `JavaExec` tasks of the root build; the class path is the one of the module.

| Task | Program | Default arguments |
|---|---|---|
| `runDaemon` | daemon with its own router (`cringle.daemon.MainKt`) | `--port 7400 --combined` |
| `runManagementServer` | management server (`cringle.management.MainKt`) with the WebUI on https://127.0.0.1:8443 (`docs/webui.md`) | `--port 7500 --auth --web-port 8443` |
| `runEngine` | one engine by itself (`cringle.engine.MainKt`); normally the daemon starts engines | `--id dev-engine` |
| `runCli` | the `cringle` command line (`cringle.cli.MainKt`) | `--help` |
| `runShell` | the `cringle` command line in interactive mode (`cringle shell`); the standard input is the console | `shell` |

```
./gradlew runDaemon --console=plain --no-daemon
./gradlew runManagementServer --args="--port 7501 --auth --web-port 8444 --machine m1=127.0.0.1:7400" --console=plain --no-daemon
./gradlew runCli --args="login --server 127.0.0.1:7500 --fingerprint <sha256> --token-file token.txt" --console=plain --no-daemon
```

- **Arguments:** `--args="..."` replaces the default arguments. In the IDE put them into the run configuration of the task (*Arguments*).
- **Home:** the programs read `CRINGLE_HOME`. The tasks set it to `build/dev-home` (`rm -rf build/dev-home` starts from scratch), never to `~/.cringle`. `-PcringleHome=<dir>` chooses another folder; it has to be the same for every task that belongs together.
- **Standard input** is connected, so `runCli` can read a token from it (`login`). A run configuration of the IDE needs *Allow parallel run* if you start the daemon and the management server from one project; each program is its own process.
- **First start:** the management server prints `fingerprint=<sha256>` (the CLI is pinned to it) and, with `--auth`, `bootstrap-token=...` once. Daemon, management server, engines and router accept only peers whose key is in their trust store (`docs/trust.md`), so they talk to each other only after the trust entries are made (`docs/management-server.md`, "Channels and trust").
- The installed programs (`docs/releasing.md`) are started with the scripts in `bin/`; these tasks are for development.
