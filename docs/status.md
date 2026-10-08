# State of the project

Written for an agent that starts without the history of the conversations. It says what is done, what is open and which decisions were taken on the way but are not yet in [decisions.md](decisions.md) (the owner moves them there). `Architecture.md` stays the source of truth; where this page and `decisions.md` disagree, `decisions.md` wins. Check `gh pr list` and `gh issue list --label agent-task` for the current state of pull requests and issues; this page does not track them.

## Milestones

| Milestone | State |
|---|---|
| M0 – M4 (foundation, engine core, schema and tether types, artifacts and repository, machine level and first deployment) | done |
| M5 – Trust and encryption | done: every link of a component is mutual TLS, there is no unencrypted mode and no `--insecure-dev-mode`; the CLI is pinned to the fingerprint of the management server; `cringle trust` manages trust; `TwoMachineTrustTest` shows two machines that trust each other |
| M6 – Distribution and shared services | started: the wire format (`spec/wire.md`, module `wire`) is done. Cross-engine tethers are done: the blueprint field (#145), the transport with `MESSAGE` tethers over mTLS (#146), the other types (#147) and the resolution through the registry with supervision and reconnecting (#148). Tethers between projects and shared services are done: service names in blueprints (#170), bindings (#171), engine key and tether port in the status (#172), service callers (#177), bound service tethers (#178), deploy applies the bindings (#179), failover between instances (#173), and the end-to-end proof across two machines (#174, `docs/shared-services.md`). Open decisions about selection rules and capabilities stay `[Offen]` (Architecture 13.3) |
| M7 – Observability | not started, no issues yet (architecture chapter 25, M7) |
| M8 – WebUI and blueprint editor | not started, no issues yet |
| M9 – Operations, security and hardening | partly: self-update, cache cleanup, plugin trust in the repository, logging. Open: blue-green, rollback, migrations, isolated blocks (deferred), assertions, debugging, renewal, documentation |

Issues that exist for the open work are on GitHub with the label `agent-task`; the milestone tracking issues (#149 for M6, #150 for M7, #151 for M8, #152 for M9) list what has no issue of its own yet. An agent that picks one of those first splits it into issues that meet the Definition of Ready (AGENTS.md).

## Decisions that are not yet in decisions.md

Propose them to the owner for `decisions.md`; do not change them without asking.

**Wire format and serialization (M6)**
- JSON is the first serialization for typed tether messages across process boundaries; other formats (for example CBOR) plug in later behind `PayloadCodec`. The envelope (`spec/wire.md`) does not depend on the encoding.
- Typed tether types (`MESSAGE`, `REQUEST_RESPONSE`, `STREAM`) carry typed frames, byte tethers (`BYTE_STREAM`, `TCP`) carry raw bytes; one mode per tether, never mixed. The blueprint field `mode` on a tether is **not** implemented yet (follow-up).
- The owner wants the serialization to be configurable per port and tether in the blueprint, different tether types supporting different mechanisms/protocols. This is not specified yet; the JSON codec is the only one.

**Trust and TLS (M5)**
- One identity and one trust store per component, under the component's home: daemon `<home>/daemon`, router `<home>/router`, engine `<engineDir>`, management server `<home>/management`; the repository gets its identity from its starter (`RepositoryTls`).
- Trust is by fingerprint of the public key, never by first use. A peer without an entry in the trust store is refused at the TLS handshake, before any token is looked at. An open connection may live for up to 60 s (`TlsHelper.SESSION_TIMEOUT_SECONDS`) after the trust entry was removed.
- The daemon creates the identity of an engine (`CN=engine:<id>`) when the engine is created and enters its key as `ENGINE`; before each start it writes `<engineDir>/trust.json` with the daemon, every `COMPONENT` of its trust store (management server, repository) and the router.
- The CLI pins the management server by fingerprint (`cringle login --fingerprint`, profile field `fingerprint`, `CRINGLE_FINGERPRINT`); there is no client certificate, the user is authenticated by the token. The Gradle plugin pins the repository by fingerprint (`-Pcringle.fingerprint`, `cringle { publish { fingerprint } }`, `CRINGLE_FINGERPRINT`, `cli.json`) and has its own identity in `<home>/certs`; the repository has to trust it.
- `RepositoryServer` and `RepositoryClient` require TLS. `RouterServer` can still be built without TLS (router unit tests); no production path does that.

**Other**
- Logging uses SLF4J with Logback (dual-licensed EPL-1.0 / LGPL-2.1, used under EPL-1.0); the owner accepted this although AGENTS.md lists EPL-2.0 only.
- The package cache protects a freshly installed version by writing the union of the old and the new usage record before the install and the new set after a successful deploy (`ensureForDeploy`).
- On Windows the self-update switches `current` with a junction (needs no privilege); on Linux with a symbolic link.

## Known gaps and follow-ups

- Blueprint field `mode` per tether (see above) and a per-port/tether serialization setting.
- Remote tether driver, resolution through the registry with cache and health check, failover (M6).
- Transitive trust of the management server in the engines of another router is shown by `cringle trust list` (origin = the router) but the management server does not call those engines yet.
- Isolated (untrusted) blocks and the local IPC tether are deferred (M9).
- The GitHub workflow `CI` is disabled (billing). The local build replaces it; an agent states the results of `./gradlew build` and of `integrationTest` in the pull request; `./gradlew build integrationTest` is the full verification (two suites, #133).
