# `cringle` command line

The CLI talks to the ManagementServer only (one address, also for user management). Build it with `./gradlew :cli:installDist`; the start scripts `bin/cringle` and `bin/cringle.bat` are in `kotlin/cli/build/install/cringle/` (`:cli:distZip` packs them with the JARs). `cringle --help` lists all commands, `cringle <command> --help` shows the options of one.

**Connection.** The profile `<home>/cli.json` (home: `--home`, `CRINGLE_HOME` or `~/.cringle`) holds the server address, the user token and the fingerprint of the key of the server (`"fingerprint"`). The file is created with owner-only rights from the first moment, not restricted afterwards: `rw-------` on POSIX file systems, and on Windows an access list with one entry, full access for the owner, and nothing inherited. `cringle login --server host:port` reads the token from standard input (`echo $TOKEN | cringle login ...`) or from a file with `--token-file FILE`, checks it at the server and stores server, token and fingerprint. `--token TOKEN` still works but prints a warning: the token is then in the history of the shell and in the process list. `CRINGLE_SERVER` and `CRINGLE_TOKEN` override the profile, `--server` overrides both; a token is deliberately not a global option, so it does not end up in shell histories by accident. A server started without `--auth` needs no token.

**Pinned TLS.** The connection is TLS 1.3 and pinned: the CLI accepts the server only if the SHA-256 fingerprint of its key is the one in the profile (or in `CRINGLE_FINGERPRINT`, which wins). The operator of the server reads the fingerprint at the start of the server (`fingerprint=...`). `cringle login --server host:port --fingerprint SHA256` asks the server for its key first; if it is another one, nothing is stored and the command ends with exit code 2 and both fingerprints. Without `--fingerprint` the login prints the fingerprint the server presents and stops (exit code 2); `--yes` accepts it. A command that connects without a stored fingerprint ends with exit code 2 and a message. There is no client certificate: the user is authenticated by the token. Commands that do not connect (`--help`, `logout`) need nothing. All examples of the CLI assume one of the three, for example `cringle login --server 127.0.0.1:7500 --fingerprint SHA256 --token-file admin.token`.

**Output.** Text by default (tables, `key: value` lines); `--json` prints the same data as JSON. Exit codes: 0 success, 1 the command failed (server errors are printed with their gRPC status), 2 the command line is wrong (the usage of the command is printed).

| Area | Commands |
| --- | --- |
| Session | `login`, `logout`, `whoami` |
| Machines | `machine add\|list\|remove` |
| Engines | `engine create\|start\|stop\|delete\|list\|status\|tag` (roles and labels for placement) |
| Metrics | `metrics [machine engine] [--fabrics] [--tethers] [--fabric id]`: engine numbers (CPU, heap, threads, errors); with `--fabrics` one row per fabric, with `--tethers` one row per tether |
| Bindings of service dependencies | `bind <project> <service> <fabric>...` (the preferred fabric first; more than one gives failover), `unbind <project> <service>`, `bindings [project]` |
| Fabrics, deployment | `fabric list\|status\|start\|stop\|remove`, `deploy <project> [--version range] [--no-start] [--relock]`, `undeploy <project>`, `cache cleanup [machine]`, `recover` |
| Logs | `logs [machine engine] [--fabric f] [--block b] [--level l] [--since t] [--limit n]` |
| Routers | `router add\|remove\|list` |
| Trust | `trust list`, `trust add <router-address> [--fingerprint SHA256 \| --yes]`, `trust add-component <fingerprint> --name NAME [--kind COMPONENT\|SERVER] [--address HOST:PORT]`, `trust revoke <fingerprint>` |
| Repository | `repo publish\|list\|versions\|download\|trust` |
| Users | `user create\|list\|delete`, `group create\|list`, `token create\|list\|revoke` |
| Update | `self-update [--check] [--version v] [--allow-major] [--timeout s] [--install-root dir]` (see `updating.md`) |

**Deploy.** `deploy` uses the lock file `<home>/management/locks/<project>-<version>.lock.json` of the version it picks; `--relock` resolves the dependencies again and overwrites it (for example to get a newer compatible plugin). A damaged lock, or one whose hashes differ from the Repository, fails the command and names `--relock`. `deploy` and `undeploy` of one project run one after the other. A deploy that cannot be placed (no running engine with the required roles and labels) fails with the requirement and leaves the running fabrics of the project untouched; a deploy that fails while starting restores the previous fabrics of the project. Details are in `management-server.md`.

## Trust

Trust is by fingerprint, never by first use (Architecture 5.1). `cringle trust` talks to the ManagementServer, which keeps its own trust store (`<home>/management/trust.json`) and forwards to the router of its machine.

- `cringle trust list` shows every entry with `fingerprint`, `kind` (`ROUTER`, `ENGINE`, `COMPONENT`, `SERVER`), `name`, `address`, `origin` (the fingerprint of the router that vouched for the entry, empty for direct trust) and `addedAt`. It needs the right to read.
- `cringle trust add <router-address>` asks the router for the fingerprint of its key and compares it with `--fingerprint`, which the operator of the router gave you. A different key ends with exit code 1 and both values, and nothing is added. Without `--fingerprint` the command prints the fingerprint the router presents and stops with exit code 2; `--yes` accepts it. Then the router of the ManagementServer is connected to the remote router, and the engines of that router are trusted through it.
- `cringle trust add-component <fingerprint> --name NAME` trusts a daemon, a repository or a server by the fingerprint of its key (`--kind COMPONENT`, the default, or `SERVER`; `--address host:port` if it matters).
- `cringle trust revoke <fingerprint>` removes the trust. For a router the router and every engine that came through it are removed; an engine of another router cannot be removed alone. `add`, `add-component` and `revoke` need the right to administer.
