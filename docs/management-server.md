# ManagementServer

The ManagementServer is the central control instance: CLI and WebUI talk only to it (`proto/cringle/management/v1/management.proto`). It keeps the list of known machines (their Daemons), creates and controls Engines through the Daemons, deploys and controls fabrics through the Engine management API, proxies the Repository and the Router, and answers log queries (fan-out to the running Engines).

Start: `management-server --insecure-dev-mode [--home <dir>] [--port <port>] [--repository <host:port>] [--repository-token <token>] [--router <host:port>] [--machine <id>=<daemon host:port>]...` (prints `management-port=N`). Without `--insecure-dev-mode` it refuses to start until mTLS (#13) exists.

State (`<home>/management/state.json`): machines, Engines it created (with `autostart`, default true) and fabrics it deployed (the complete deploy request and whether the fabric should run). This is what recovery uses.

Recovery runs when the server starts (and on demand with `Recover`): Engines with `autostart` are re-registered at their Daemon if needed and started; the fabrics of running Engines are deployed again if the Engine lost them, and started if they should run. A machine that is not reachable is reported in the result, it does not stop the recovery. Stopping a fabric or removing it is remembered; stopping an Engine is not (`autostart` is a setting, not the current state).

Permissions (user management, #22): `READ` for lists, status, logs and package downloads; `OPERATE` for Engines, fabrics, publishing and `Recover`; `ADMINISTER` for machines, remote routers and plugin trust.

Limits until mTLS (#13): the APIs of Daemons and Engines are plaintext and unauthenticated, and an Engine management API listens on the loopback interface of its machine, so only Engines on the machine of the ManagementServer can be controlled. Placement and download of packages is deployment (#17); `DeployFabric` expects the packages in the Cringle home of the machine.
