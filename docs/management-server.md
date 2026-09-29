# ManagementServer

The ManagementServer is the central control instance: CLI and WebUI talk only to it (`proto/cringle/management/v1/management.proto`). It keeps the list of known machines (their Daemons), creates and controls Engines through the Daemons, deploys and controls fabrics through the Engine management API, proxies the Repository and the Router, and answers log queries (fan-out to the running Engines).

Start: `management-server --insecure-dev-mode [--home <dir>] [--port <port>] [--repository <host:port>] [--repository-token <token>] [--router <host:port>] [--machine <id>=<daemon host:port>]... [--cache-max-unused-days <n>]` (prints `management-port=N`). Without `--insecure-dev-mode` it refuses to start until mTLS (#13) exists.

State (`<home>/management/state.json`): machines, Engines it created (with `autostart`, default true) and fabrics it deployed (the complete deploy request and whether the fabric should run). This is what recovery uses.

Recovery runs when the server starts (and on demand with `Recover`): Engines with `autostart` are re-registered at their Daemon if needed and started; the fabrics of running Engines are deployed again if the Engine lost them, and started if they should run. A machine that is not reachable is reported in the result, it does not stop the recovery. Stopping a fabric or removing it is remembered; stopping an Engine is not (`autostart` is a setting, not the current state).

Permissions (user management, #22): `READ` for lists, status, logs and package downloads; `OPERATE` for Engines, fabrics, publishing and `Recover`; `ADMINISTER` for machines, remote routers and plugin trust.

Limits until mTLS (#13): the APIs of Daemons and Engines are plaintext and unauthenticated, and an Engine management API listens on the loopback interface of its machine, so only Engines on the machine of the ManagementServer can be controlled. Placement and download of packages is deployment (#17); `DeployFabric` expects the packages in the Cringle home of the machine.

## Deployment (#17)

`Deploy(project, version_range)` resolves the project and its plugins against the default Repository (issue #6 resolver), writes the lock to `<home>/management/locks/<project>-<version>.lock.json`, and reads the project's fabric configuration. For each entry it picks `instances` running Engines that have all `roles` and all `labels` (Engines get them with `CreateEngine` or `SetEngineTags`; Engines are never addressed by id), the least loaded first, and sends each Engine a deploy command with the exact plugin versions, their trust status (from the Repository responsible for the Engine's machine; the Engine never asks for it) and the artifacts to download with their hashes. Fabric ids are `<project>-<blueprint>-<n>`. Deploying a project again replaces its fabrics (stop, remove, deploy: a new version is a restart; blue-green comes later); if a fabric cannot be deployed, the ones deployed by that call are removed. `Undeploy(project)` removes all fabrics of a project. Where each fabric runs is in the state of the ManagementServer and in the Router registry (the Engines report their fabrics with every heartbeat).

### Package cache

All Engines that use the same Cringle home share one cache: `<home>/projects/<name>/<version>` and `<home>/plugins/<name>/<version>`. A version is downloaded, its SHA-256 checked against the lock, and unpacked once, in a temporary directory that is moved into place, so a corrupt download (`DATA_LOSS`) or a failed unpack leaves nothing behind. A file lock (and an in-process lock) makes Engines that need the same version at the same time wait for each other. `.cringle-installed` inside a version holds its hash; versions without it were placed by hand and are never touched. Binaries in packages are unpacked with the package and removed with it.

### Cleanup

Each Engine records which package versions its fabrics use (`<home>/cache/usage/<engine>/<fabric>`); the record is removed with the fabric and when the Engine restarts. A version is unused if no fabric of an existing Engine lists it. `CleanupCache(machine, min_unused_seconds)` removes unused versions that were last used at least that long ago (0: all unused; "last used" is when a fabric using it was last deployed or removed). With `--cache-max-unused-days N` the ManagementServer does this for all machines once an hour.
