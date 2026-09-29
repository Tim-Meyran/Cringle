# `cringle` command line

The CLI talks to the ManagementServer only (one address, also for user management). Build it with `./gradlew :cli:installDist`; the start scripts `bin/cringle` and `bin/cringle.bat` are in `kotlin/cli/build/install/cringle/` (`:cli:distZip` packs them with the JARs). `cringle --help` lists all commands, `cringle <command> --help` shows the options of one.

**Connection.** The profile `<home>/cli.json` (home: `--home`, `CRINGLE_HOME` or `~/.cringle`) holds the server address and the user token; it is readable by the owner only where the system allows. `cringle login --server host:port` reads the token from `--token`, or standard input, checks it at the server and stores both. `CRINGLE_SERVER` and `CRINGLE_TOKEN` override the profile, `--server` overrides both; a token is deliberately not a global option, so it does not end up in shell histories by accident. A server started without `--auth` needs no token. The connection is not encrypted yet; the client certificate joins the profile with mTLS (issue #13).

**Output.** Text by default (tables, `key: value` lines); `--json` prints the same data as JSON. Exit codes: 0 success, 1 the command failed (server errors are printed with their gRPC status), 2 the command line is wrong (the usage of the command is printed).

| Area | Commands |
| --- | --- |
| Session | `login`, `logout`, `whoami` |
| Machines | `machine add\|list\|remove` |
| Engines | `engine create\|start\|stop\|delete\|list\|status\|tag` (roles and labels for placement) |
| Fabrics, deployment | `fabric list\|status\|start\|stop\|remove`, `deploy <project> [--version range] [--no-start]`, `undeploy <project>`, `cache cleanup [machine]`, `recover` |
| Logs | `logs [machine engine] [--fabric f] [--block b] [--level l] [--since t] [--limit n]` |
| Routers | `router add\|remove\|list` (trust and revoke follow with mTLS, issue #13) |
| Repository | `repo publish\|list\|versions\|download\|trust` |
| Users | `user create\|list\|delete`, `group create\|list`, `token create\|list\|revoke` |
