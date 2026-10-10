# Acceptance for 1.0.0: chapter 26 against the state

This is the check of the feature list of chapter 26 of the architecture (`Architecture.md`, "Feature-Liste für 1.0.0") against the code, made for the last issue of M9 (#234). Every point says **done**, **changed** (done in another way than the architecture proposed, by decision of the owner), **partly** or **open**, and where the proof is: a document, a test or both. Tests are named by their class; `./gradlew build integrationTest` runs them all.

**Summary.** Of the 79 points, 72 are done, 2 are done in another form, 4 are partly done (the local IPC tether, the health check of drivers, automatic federation, user name and password) and 1 is open (isolated blocks with resource limits). What is open or partly done is in [What is not there](#what-is-not-there), with what it means for an operator and what the owner has to decide.

## Runtime

| Point | State | Where |
|---|---|---|
| Engine as its own process, several engines per machine | done | `daemon-service.md`; `DaemonTest`, `EngineProcessTest`; `cringle engine create\|start` |
| Several fabrics per engine, isolated from each other | done | `FabricManagementTest`; one thread and one coroutine scope per fabric (`FabricRuntime`) |
| The same blueprint several times without shared state | done | `FabricManagementTest.twoInstancesOfTheSameBlueprintRunTogether`; `instances` of a fabric config |
| Block lifecycle with automatic restart and retry limit | done | `FabricRuntimeTest.crashTriggersRestartsUpToTheRetryLimitThenTheBlockFails`; `RestartPolicy` |
| Class loader per provider and fabric instance | done | `engine/classloading`; tests in `engine/src/test/.../classloading` |
| Isolation rule (the stricter level wins, fail closed) | done | `IsolationResolver`; `FabricManagementTest.unspecifiedOrUntrustedPluginFailsClosedOnStart` |
| Isolated blocks in their own process with resource limits | **open** (deferred, #18) | see below |
| Isolated runtime paths per fabric and block | done | `FabricPaths`, `FabricPathsTest`; `block-data.md` |

## Communication

| Point | State | Where |
|---|---|---|
| Tether synchronous, asynchronous and as a stream, including raw byte streams | done | `spec/tether.md` (`REQUEST_RESPONSE`, `MESSAGE`, `STREAM`, `BYTE_STREAM`, `TCP`, `SERIAL`); `TetherNetwork` tests |
| Tether type fixed at design time, no change of mode at runtime | done | the blueprint check (`PackageValidator`, the editor of the WebUI) |
| Non-blocking guarantee per block execution | done | the blocked-thread watchdog (`WatchdogConfig`), `FabricRuntimeTest` |
| Tether types local, IPC, TCP, Serial, Filesystem | **partly** | local, TCP and Serial are tether types, Filesystem is a driver (decision #75); the local IPC tether is deferred with the isolated blocks (#18) |
| Backpressure and retry configuration | done | `DeliveryPolicy` in the blueprint, `spec/tether.md` |
| Tethers across engine and project boundaries | done | `shared-services.md`; `TwoMachineTrustTest`, the tests of #146 to #148, #174 |
| Optional encryption through the certificate infrastructure, with a choice of method | **changed** | a tether between engines is **always** mutual TLS (not optional); a choice of method is not offered (`trust.md`, "Tethers between engines") |

## Model and types

| Point | State | Where |
|---|---|---|
| Ports with tether types and schemas | done | `spec/schema.md`, `spec/package-format.md` |
| VarArg ports as lists, the number fixed at start | done | `BlockPorts.varArgPort`; `FabricRuntimeTest` |
| Schemas per project and plugin, standard schemas | done | `cringle.std`; `SchemaRegistry` |
| Resolution through unique namespace ids | done | `spec/schema.md` |
| Runtime check | done | `SchemaValidator` (config of a block, tether values) |
| Serialization across process boundaries by the schema | done | `spec/wire.md`, module `wire` |

## Drivers

| Point | State | Where |
|---|---|---|
| The driver as the only access to the outside world | done | `contract` (`Driver`, `BlockProvider`) |
| Injection by the block provider | done | `BlockProvider.createBlock(name, drivers)` |
| Lifecycle per driver | done | `Driver.start()` / `stop()` |
| Isolation level per driver type, one driver per engine | done | `DriverType`, `DriverFactory` |
| Built-in drivers: tether, TCP, serial, filesystem, logging, DWH, user management | done | `engine/drivers`; `UserManagementDriver`; `observability.md` |
| Drivers from plugins, available in the fabric | done | `drivers` in the plugin manifest (`spec/package-format.md`) |
| Retry details hidden from the block | done | the drivers and the tether network |
| Health check and failover in the driver | **partly** | failover between instances of a shared service is done (#173, `shared-services.md`); the `Driver` interface has no health-check call |

## Artifacts and versioning

| Point | State | Where |
|---|---|---|
| The repository as its own service with an API and a download endpoint | done | module `repository`; `cringle repo publish\|list\|versions\|download` |
| Projects and plugins as ZIP with binaries, JARs and JSON configs | done | `spec/package-format.md`; `cringlePackage` (`gradle-plugin.md`) |
| Immutable project versions | done | `PackageCacheTest` (a version with another hash is refused), the repository |
| npm-style compatibility ranges and lock concept | done | `management-server.md` (lock files), `cringle deploy --relock` |
| Hash per version with a check | done | `PackageHash`, `PackageCache` |
| Central trust status for plugins | done | `cringle repo trust <plugin> trusted\|untrusted`; `security.md` |
| Cache under the home with one unpacked version per artifact | done | `PackageCache`; `management-server.md` ("Package cache") |
| Cleanup after a period and by the management server | done | `cringle cache cleanup`, `--cache-max-unused-days`; `PackageCacheTest` |

## Deployment

| Point | State | Where |
|---|---|---|
| Manual deployment through the management server | done | `cringle deploy`, the page *Deployments* |
| Fabric config with logical roles and labels | done | `fabric(...)` in the Gradle DSL; `engine tag` |
| Placement at deploy time | done | `ManagementCore.deploy`; `DeploymentTest` |
| Portability between environments without export and import | done | a project names roles, never engines (`gradle-plugin.md`) |
| Recovery after a restart of the system | done | `ManagementServerTest.enginesAndFabricsComeBackAfterRestartingManagementServerAndDaemon`; `OperationsEndToEndTest` |
| Blue-green as the default, switchable off | done | `cringle deploy --no-blue-green`; `management-server.md` ("Update strategy"); the exclusive resource `OTHER "data"` makes a project stop-then-start |
| Rollback to earlier versions | done | `cringle rollback`; `RollbackTest`; `migration-guide.md` |
| Multi-step update and downgrade processors, abort on a failure | done | `Processor`, `SteppedProcessor`; `DataMigrationsTest`, `FabricRuntimeTest`; state `MIGRATION_FAILED`; `migration-guide.md`, `block-data.md` |

## Machines and trust

| Point | State | Where |
|---|---|---|
| A daemon per machine | done | `daemon-service.md` |
| A router with a registry per machine | done | `RouterServer`; the daemon runs it (`router.mode`, `router.port`) |
| Fabric lookup | done | `LookupFabric` |
| Heartbeat of the engines | done | `observability.md` |
| Remote router with caching | done | `cringle router add\|remove\|list`; `RemoteRouters` |
| Self-generated certificates with a stable identity | done | `Identity` (key and certificate under the home); `trust.md` |
| Manual trust, transitive trust to engines | done | `cringle trust`; `TwoMachineTrustTest` |
| mTLS on every component link | done | `trust.md` ("All links"); there is no unencrypted mode |
| Certificate renewal | done | `cringle cert status\|renew`, `CertificateWatcher`; `IdentityRenewalTest`, `CertificateWatcherTest`, `OperationsEndToEndTest`; `trust.md` |

## Users and rights

| Point | State | Where |
|---|---|---|
| User management in the registry | done | `UserManager`; `users.md` |
| One common user space for the framework and applications | done | the same users, told apart by rights and roles |
| User management as a driver for blocks | done | `UserManagementDriver` (`UserManager.asDriver`) |
| Federation with a registry identifier, automatic or manual | **partly** | manual is done: trusted registries and users as `name@registry` (`trust.md`, "Federation"); automatic discovery is left for later (decision of the owner) |
| Rights scopes: machine, project, fabric, framework functions | done | `users.md`; `ScopedRolesTest`, `RoleMatrixTest`, `WebScopedAccessTest` |
| Token, user name and password, and certificates | **partly** | tokens (also as login links and invite links) and certificates (components) are done; **user name and password are open** |

## Observability

| Point | State | Where |
|---|---|---|
| Logging driver, not file based | done | `logging.md`, `observability.md` |
| Optional file based logging for foreign processes | done | #189, `observability.md` |
| LoggingCollector per machine | done | `LoggingCollector`; `cringle engine collect` |
| Central log view | done | `cringle logs`, the page *Logs* |
| Monitoring metrics and heartbeat | done | `cringle metrics`; `observability.md` |
| DWH per engine with a driver | done | `observability.md` |
| Recording mode and definition per tether | done | `cringle dwh record`; the blueprint field `record` |
| Retention by time and size, per block and per tether | done | `cringle dwh retention` |
| Assertions | done | #232, #233, #293; `cringle fabric status`; `spec/package-format.md` ("Assertions") |
| Remote debugging with breakpoints and a look at the data | done | `cringle debug`; `debugging.md` |

## Operation

| Point | State | Where |
|---|---|---|
| Management server with gRPC and REST | done | `management-server.md`; the web layer answers the browser |
| CLI for the simple operations | done | `cli.md` |
| WebUI in Svelte 5 with SvelteFlow | **changed** | htmx, Alpine.js and Drawflow without a build step (decision of the owner, `webui.md`, `status.md`) |
| Visual blueprint editor with a compatibility check | done | the page *Drafts*; `WebBlueprintTest` |
| Deployment management in the WebUI | done | the page *Deployments* |
| Schema editor | done | `WebSchemaEditorTest` |

## Foundations

| Point | State | Where |
|---|---|---|
| `.proto` files in the language independent part as the single source of truth | done | `proto/`, `buf.yaml`; the Kotlin stubs are generated from them |
| Platform specification separated from the Kotlin implementation | done | `spec/`, `proto/` and `kotlin/` (`README.md`) |
| Documentation and sample projects | done | this issue: `getting-started.md`, `operations.md`, `security.md`, `migration-guide.md`, `samples/README.md` |

## What is not there

1. **Isolated (untrusted) blocks, their resource limits (cgroups, job objects) and the local IPC tether** (deferred, #18). The rule is fail closed: a block of a plugin that is not marked `trusted` needs a level of isolation that the engine cannot give, so the fabric is **not started** and says why. Consequence for an operator: mark the plugins you use `trusted` (`cringle repo trust <plugin> trusted`); there is no way to run code you do not trust.
2. **User name and password.** A user signs in with a token: typed, as a login link or from an invite (`users.md`). Decision for the owner: add it later (a password hash per user, a login form), or leave tokens as the only way.
3. **The choice of the encryption method of a tether.** Tethers between engines are mutual TLS with the identities of the engines, always. Decision for the owner: that is enough (and the architecture sentence is changed), or a per-tether option is wanted.
4. **A health-check call in the `Driver` interface.** Failover between service instances exists; a driver cannot report its health to the engine. Decision for the owner: add `Driver.health()` in a later version.
5. **Automatic discovery of trusted registries.** Registries are trusted by hand (`cringle registry trust`).

These are proposals for `decisions.md` and `status.md`; this document does not change the architecture.

## How this was checked

- The code and the tests were read against every point; the proof of each is named above.
- `OperationsEndToEndTest` runs the chain of the operations in one installation: update with migrations of the data over three versions, rollback with the downgrade processor, renewal of the certificate of the daemon and a restart, and the recovery of the fabrics.
- The Gradle plugin had no way to name processors (the manifest field existed since #257, the plugin wrote an empty one); the check found it and #234 closes it (`gradle-plugin.md`).
- `./gradlew build integrationTest` is green on the final commit of #234 (numbers in the pull request).
