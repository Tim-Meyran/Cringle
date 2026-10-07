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
| Fabrics, deployment | `fabric list\|status\|start\|stop\|remove`, `deploy <project> [--version range] [--no-start] [--relock]`, `undeploy <project>`, `cache cleanup [machine]`, `recover` |
| Logs | `logs [machine engine] [--fabric f] [--block b] [--level l] [--since t] [--limit n]` |
| Routers | `router add\|remove\|list` (trust and revoke follow with mTLS, issue #13) |
| Repository | `repo publish\|list\|versions\|download\|trust` |
| Users | `user create\|list\|delete`, `group create\|list`, `token create\|list\|revoke` |
| Update | `self-update [--check] [--version v] [--allow-major] [--timeout s] [--install-root dir]` (see `updating.md`) |

**Deploy.** `deploy` uses the lock file `<home>/management/locks/<project>-<version>.lock.json` of the version it picks; `--relock` resolves the dependencies again and overwrites it (for example to get a newer compatible plugin). A damaged lock, or one whose hashes differ from the Repository, fails the command and names `--relock`. `deploy` and `undeploy` of one project run one after the other. A deploy that cannot be placed (no running engine with the required roles and labels) fails with the requirement and leaves the running fabrics of the project untouched; a deploy that fails while starting restores the previous fabrics of the project. Details are in `management-server.md`.
