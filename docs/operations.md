# Operations

What an operator needs after the installation: where things are, the settings, addresses and ports, logs and numbers, updates, backups, certificates, and what to do when something does not work. Each part points to the document with the details.

## What runs where

| Program | Job | Default port |
|---|---|---|
| Daemon (`cringle-daemon`, a service) | one per machine: starts and supervises the engines and the programs below; runs the router | 7400; router 7450 |
| Management server | one per site: state, deployments, users, WebUI | gRPC 7500, web interface 8443 |
| Repository | one per site: the packages of plugins and projects | 7600 |
| Engine | a process per engine, started by the daemon: runs fabrics and blocks | free port, reported to the router |

The daemon of the installers runs the management server and the repository as programs it supervises and restarts (1 s up to 30 s delay). A machine that is not the central one installs with `--daemon-only`.

Folders (Linux; Windows in brackets): installation `/opt/cringle` (`C:\Program Files\Cringle`) with `current` pointing at the active version; home `/var/lib/cringle` (`C:\ProgramData\Cringle`) with `config/cringle.conf` (settings), `data/` (data of blocks), `management/` (state, lock files, drafts, `bootstrap-token`), `daemon/` (engines, logs of the programs), the identities and trust stores. `CRINGLE_HOME` moves the home.

## Settings, addresses and ports

All settings are keys of one store per machine, `<home>/config/cringle.conf`, changed with `cringle config get|set|unset|list`, the page *Configuration* of the web interface, `cringle setup`, or the installer options. Arguments of a program win over the store. A key says what must restart (`daemon`, `management`, `repository`, the components, or nothing), and the command does it when you ask. The keys are in [daemon-service.md](daemon-service.md) (table "settings"):

- `bind` (loopback or all interfaces), `cringle.host` (the name other machines use for this one, usually the Tailscale name or address), `components`.
- `daemon.port`, `router.mode`, `router.port`, `router.address`, `management.port`, `management.web.port`, `management.web.url`, `repository.port`.

The page *Connect* shows, for every component, the address another machine has to enter and the key to trust. Ports below 1024 work without root on a systemd installation (`CAP_NET_BIND_SERVICE`); for a certificate of a known authority put a reverse proxy in front of the web interface and set `management.web.url`.

## More machines

1. Install the daemon alone (`--daemon-only`) and tell it where the router of the site is (`router.address`).
2. Trust goes by key, never by first use: `cringle machine add <name> <host:7400>` and `cringle trust add-component <fingerprint>` on the site; the other side trusts the site in return (`cringle trust add <router-address>`). The page *Connect* lists what to enter. [trust.md](trust.md) has the flows.
3. Create an engine with roles and labels (`cringle engine create`, `engine tag`) so that projects can be placed on it.

## Logs, numbers, health

- Logs of the programs: `<home>/daemon/logs/management.out.log`, `.err.log`, `repository.*.log` and `journalctl -u cringle-daemon` (Windows: the service log folder). A program that ends is restarted and the reason is in the log of the daemon.
- Logs of blocks: `cringle logs [--fabric f] [--block b] [--level l]`, the page *Logs*; kept per engine, and by the logging collector for stopped engines (`cringle engine collect`). [logging.md](logging.md), [observability.md](observability.md).
- Numbers: `cringle metrics`, the page *Metrics*; the heartbeat of an engine marks it unreachable when it stops.
- State: `cringle fabric list` (state and `checks`), `cringle fabric status <id>` (assertions with state and detail). Fabrics that need attention show on the home page; `cringle recover` brings wanted fabrics back after a restart.
- Recorded messages: `cringle dwh record <fabric> on` with retention (`--max-age`, `--max-size`), `cringle dwh query`. Debugging a running fabric: [debugging.md](debugging.md).

## Updates

- **Cringle itself:** `cringle self-update` (check with `--check`), a version that does not come up is rolled back by the updater itself, and `--version <v>` goes to an older release by hand; major versions need `--allow-major` ([updating.md](updating.md)). Update every machine; the daemon restarts its programs.
- **Your applications:** `cringle deploy <project>` (blue-green, or stop-then-start with data migrations), `cringle rollback <project>` ([management-server.md](management-server.md), [migration-guide.md](migration-guide.md)). After a restart of the management server or a machine the fabrics come back (`cringle recover`, done by itself at start).
- **The package cache** grows with every version; `cringle cache cleanup [machine]` removes versions that were not used for a time, never one that a running fabric needs.

## Backups

Back up with the services stopped (or accept a copy of a moment): the whole home is the state of the site. The parts that matter, in order:

1. `<home>/management` (users, tokens, deployment history, lock files, drafts) and the identity folders (`daemon`, `router`, `management`: keys and trust stores). **Without the keys the machines do not trust each other any more**; keep these private (owner only, the folders are created so).
2. The repository store (packages; `<home>/repository`). Packages can be published again from the build, the history of versions cannot.
3. `<home>/data` (the data of blocks) and the recordings of the DWH.
4. `<home>/config/cringle.conf`.

Restore by putting the folders back and starting the services. A project with data migrations needs its own backup of `<home>/data/<project>` before an update that deletes data ([migration-guide.md](migration-guide.md)).

## Certificates

Certificates are valid for 10 years and renewed by the components themselves (at start if they end within 30 days, and once a day while running). Trust is by key, so a renewal changes nothing for the peers. `cringle cert status` shows the days left, `cringle cert renew [--force]` renews by hand. A new key is not renewal and needs a new trust exchange with every peer ([trust.md](trust.md)).

## When something does not work

| Symptom | Look at |
|---|---|
| The daemon ends at once | `journalctl -u cringle-daemon`, `<home>/daemon/logs/*.err.log`; a port held by another program shows as a one-line message (`ss -ltnp`) |
| The web interface does not open | `management.web.port`, `bind` (loopback only by default), the address on the page *Connect*; the browser warns about the self-signed key: compare its fingerprint |
| `cringle` says the server key differs (exit code 2) | the fingerprint of the server changed; compare both, and run `cringle login --fingerprint` again only if the change is expected |
| A machine shows unreachable | the daemon runs? the trust entries on both sides (`cringle trust list`), the firewall for 7400 and the router port |
| A deploy is refused | the message names the missing roles or labels, a lock file that differs (`--relock`), or a plugin that is not `trusted` |
| A fabric does not start | `cringle fabric status <id>`: `MIGRATION_FAILED` has step and backup path; a plugin that is not `trusted` is refused (fail closed); a block that does not answer within its time is stopped and restarted up to its limit |
| A user cannot do something | `cringle user list` / the page *Users*: the rights are per scope (machine, project, fabric, function) |
