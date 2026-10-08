# Trust foundation

Cringle has no central certificate authority. Trust comes from a manual act (Architecture 5.1). This page describes the building blocks in `kotlin/common` (#13): the identity of a component, the fingerprint trust is given to, the trust store and the TLS helper. The router uses them for the trust API (#82, see "Flows"); the other servers and channels follow, see "What is missing".

## Identity

`cringle.common.Identity` is the stable identity of one component: a persistent EC P-256 key pair and a self-signed certificate over it.

- Files in `<home>/certs`: `identity.key` (private key, PKCS#8), `identity.pub`, `identity.crt`. The private key is written with `OwnerOnlyFiles` (#71): owner-only from the moment it is created, on POSIX (`rw-------`) and on Windows (one ACL entry).
- `Identity.loadOrCreate(home, commonName)` creates key and certificate on first use and loads the same ones afterwards.
- `renew()` issues a new certificate for the **same key pair** and stores it. The validity is 3650 days (`[Zu bestätigen]`, Architecture chapter 30 leaves renewal open; `renew()` is only a hook, nothing triggers it).
- The subject is `CN=<kind>:<id>` for new components: `ComponentKind.commonName(id)` gives `engine:e1`, `router:r1`, `daemon:d1`, `management:m1`, `repository:p1`. `EngineIdentity` (engine module) is a thin caller and keeps its subject `CN=<engineId>` and its report of the certificate fingerprint, so nothing that exists changes; the engine moves to `engine:<id>` when it is switched to TLS (#84).

## Fingerprint

`PublicKeyFingerprint` is the SHA-256 of the `SubjectPublicKeyInfo` of the public key, lowercase hex (64 characters). Trust is given to this value and not to the certificate, so a certificate can be renewed without breaking any trust (Architecture 5.2). `Identity.certificateFingerprint` is the hash of the certificate; it changes with every renewal and is not an identity.

## Trust store

`TrustStore(file)` is the file-based store of one component, normally `<home>/trust.json` (written atomically, readable by the owner only). An entry has `fingerprint`, `name`, `kind` (`ROUTER`, `ENGINE`, `COMPONENT`, `SERVER`), optional `address`, `origin` and `addedAt`.

- `origin` is `null` for trust that was given directly, otherwise the fingerprint of the router that vouched for the peer.
- `add`, `remove`, `list`, `isTrusted`, `onChange`. `remove(fp)` also removes every entry whose `origin` is `fp`, and only those.
- The file is read when the store is opened and written through on every change. A second process that changes the file is not noticed.

## TLS helper

`TlsHelper.serverCredentials(identity, trustStore)` and `TlsHelper.channelCredentials(identity, trustStore)` return the netty `SslContext` for `NettyServerBuilder.sslContext(...)` and `NettyChannelBuilder.sslContext(...)`. (They are called credentials because that is what a caller wants; the type is the netty context, because only it can restrict the protocol version.)

- TLS 1.3 only. A client that offers TLS 1.2 or older is refused.
- The server demands a client certificate. `identity` can be `null` for a client that has none (#85); such a client only reaches servers that do not demand one.
- A peer is accepted if and only if the fingerprint of its key is in the trust store and its certificate is valid now (an expired certificate is refused). Host names and certificate chains are not checked: the trust is in the key.
- A refused peer is logged (logger `cringle.common.TlsHelper`, WARNING) with its fingerprint and the reason, never with the certificate.
- The trust store is asked at every handshake, so an entry that is added or removed counts for the next new session. The JDK lets a client **resume** a session without a new check of the certificate, and the server cannot take such a ticket back. A peer that was removed from the trust store can therefore still resume an old session for up to `TlsHelper.SESSION_TIMEOUT_SECONDS` (60) seconds; connections that are already open are not closed by a removal.
- The JDK TLS provider is used, not OpenSSL: the tests show that it gives the behavior above, and ALPN for HTTP/2 works with it.

## Flows

All three run on a router with TLS (`RouterServer(tls = RouterTls(identity, trustStore))`). Without TLS the router has no trust: `TrustRemoteRouter`, `ListTrust` and `PrepareEngine` answer `FAILED_PRECONDITION` and `AddRemoteRouter` keeps its old behavior; the daemon, engines and the ManagementServer always run their routers with TLS.

### Trust a remote router

1. An admin calls `AddRemoteRouter` or `TrustRemoteRouter` (role ADMINISTER) with the address and the `expected_fingerprint` that was read from the other router by hand (Architecture 5.1). A blank fingerprint is `INVALID_ARGUMENT`.
2. The router connects, takes the key fingerprint from the certificate of the target and compares it with the expected one. A mismatch is `FAILED_PRECONDITION` and names both values; the trust store is not changed.
3. On a match the router is stored as `ROUTER` (direct, `origin = null`) and the router is refreshed at once.
4. At every refresh the engines of that router (with their key fingerprints) are stored as `ENGINE` entries with `origin = <fingerprint of the router>`. An engine that the remote router no longer reports loses its entry at the next refresh.
5. Engines of a router that is not in the trust store are not returned by `ListEngines(include_remote = true)`.

### Revoke

1. An admin calls `RevokeRemoteRouter` with the address (`NOT_FOUND` for an unknown one).
2. `TrustStore.remove` deletes the router and every entry with its `origin`; the cached engines of that router leave the registry.
3. `TrustInterceptor` listens to the store. Running calls of every peer that is no longer trusted are closed with `UNAUTHENTICATED`; calls of other peers go on.
4. Every new call is checked by the fingerprint of the TLS peer, so a peer that resumes an old TLS session (up to 60 s, see TLS helper) or keeps its connection is refused as well. gRPC cannot close the TCP connection of a server; "closed" means: running calls end, new calls fail.

### Enrollment of a local engine

1. The daemon (trusted `COMPONENT`) calls `PrepareEngine` with the engine id and the SHA-256 of a one-time secret and hands the secret to the engine. A client that is trusted but not a component gets `PERMISSION_DENIED`.
2. The engine calls `RegisterEngine` with its certificate and the secret. The router checks that the key of the certificate is the key of the TLS connection and that the certificate is valid, then compares the secret with the announced hash.
3. On success the fingerprint is stored as `ENGINE` (direct, named like the id) and the secret is used up. A wrong, missing or used secret is `PERMISSION_DENIED` with one message for all three.
4. Later registrations of the same id need the same key and no secret; another key for a bound id is refused. Announcements are kept in memory: after a restart of the router the daemon announces again.

## Engine and daemon on mTLS

The engine-router and daemon-router channels use mTLS. The engine and the daemon each own an `Identity` and a `TrustStore`; the router does the same in combined mode.

- The engine opens `TrustStore(<engineDir>/trust.json)`. Its management API is mutual TLS with that store, and with a router it builds `EngineTls(identity, trustStore)` for the router channel. The engine trusts nobody except what is in that store; its subject is `CN=engine:<id>`.
- The daemon owns its own `Identity` (under `<home>/daemon`) and `TrustStore` (under `<home>/daemon/trust.json`).
- In combined mode, the embedded router runs with mTLS using its own `Identity` (under `<home>/router`) and `TrustStore` (under `<home>/router/trust.json`). At start, the router trusts the daemon (`COMPONENT`) and the daemon trusts the router (`ROUTER`) automatically.
- In separate router mode, the daemon announces engines via `PrepareEngine` over mTLS using its identity and trust store. The router must already trust the daemon (manual trust, #82).
- Before starting an engine, the daemon writes `<engineDir>/trust.json` with the daemon and every `COMPONENT` of its trust store (for the management API) and the router's fingerprint (looked up in the daemon's trust store by address). If a router is configured and not in the trust store, the start fails with a clear message.

## Tethers between engines

A `MESSAGE` tether between two engines (`spec/tether.md`, "Tethers between engines") is another link on mTLS. Each engine serves the tether service on its own port (`<engineDir>/tether.port`) with its identity. Trust for it is in a store of its own, `<engineDir>/tether-trust.json`, which starts empty with every process: when a fabric is deployed, the `fingerprint` of the `remote` of each of its tethers whose local end receives is entered as `ENGINE` (name `fabric:<id>`) and removed when the fabric is removed; two fabrics that allow the same key share the entry. A caller whose key is not in the store is refused at the handshake; a caller that is allowed but names another tether than the one that names its key is refused with an `unknown-target` error. The sending engine trusts only the key of its `remote` (one channel per address and key; the store of the channel is `<engineDir>/tether-peers/<fingerprint>.json`). The keys are the public key fingerprints of the engines, as shown by `cringle trust list`. `RemoteTetherTest` (module `engine`) shows the refusals.

A fabric that provides a service (#177) allows a list of engines instead of one sender: the ManagementServer gives their public key fingerprints with the deploy request and changes them with `SetServiceCallers` (engine management API, which the ManagementServer alone may call). Each is added to the tether trust store while it is allowed and removed with the last allowance; a removed caller is refused at its next call and its open calls are ended. The ManagementServer computes the list from its bindings (`docs/management-server.md`): the engines of the projects that are bound to the service fabric.

## All links

`ManagementTlsTest` (module `management-server`) shows that the links ManagementServer to Daemon, Engine and Repository and Daemon to Engine run only over mTLS, that a peer without a trust entry is refused, and that a removed peer is refused on its next connection. `RepositoryTlsTest` shows that a client with a valid token but without a trust entry fails at TLS, not at authentication. `NoPlaintextGuardTest` fails if `usePlaintext` returns to one of the classes `ManagementCore`, `RepositoryServer`, `RepositoryClient`, `Daemon` and `Engine`. How the management server and its trust store are described: `docs/management-server.md` (channels and trust).

## Local trust (`--trust-local`)

The daemon, the router, the engines and the management server of one machine run under one user and keep their keys below one home (`<home>/daemon`, `<home>/router`, `<home>/engines/<id>`, `<home>/management`). With the local trust they read each other's fingerprints from the public key files there, so that nobody copies a fingerprint or makes a trust entry:

- **`daemon --trust-local`** creates the identity of the management server of the home if it is missing (the management server loads the same key later) and trusts it as `COMPONENT`, in its own trust store and in the router of combined mode. The engines get it through their trust file, as for any `COMPONENT` of the daemon.
- **`management-server --trust-local`** trusts the daemon (`COMPONENT`), the router (`ROUTER`), the repository (`COMPONENT`) and every engine (`ENGINE`) whose key file is in the home. It looks again before every new connection (at most twice a second), so an engine that was created a moment ago is trusted when it is first called. It creates nothing and removes nothing.
- **`repository --trust-local`** (`runRepository`, `cringle.repository.MainKt`: the package repository as a process, identity and trust store in `<home>/repository`, packages in `<home>/repository/packages`, loopback only) trusts the management server (`COMPONENT`), every engine (`ENGINE`, they download from it) and the Gradle plugin of the home (`COMPONENT`, its identity is `<home>/certs`). It looks for new keys once a second. Engines trust the repository because the daemon (`--trust-local`) enters its key into their trust file when it starts them, so **start the repository before the engines are created**.
- **`cringle --trust-local`** pins the management server by the key file in `<home>/management` when no fingerprint is configured (`CRINGLE_FINGERPRINT` and the profile still win). The same machine and user is the reason it needs no confirmation of a fingerprint.
- **`CRINGLE_TRUST_LOCAL=1`** turns it on for all three without the option. It is off by default.

What it is not: whoever can write into the home can make a component trust a key, which is no more than that person can do with the keys in the home anyway. Components on different machines still need fingerprints entered by hand (`cringle trust`). The Gradle tasks `runDaemon`, `runManagementServer`, `runEngine`, `runCli` and `runShell` set it (`docs/running-from-the-ide.md`); the installed services do not.

## What is missing

- When a certificate is renewed and what happens at expiry (Architecture chapter 30).
- Closing connections of a peer whose trust was removed.

## Renewal of certificates (#224)

A certificate is valid for 10 years (`Identity.VALIDITY`). Trust is given to the **key**, so a certificate can be replaced by a new one for the same key pair without any trust entry changing: the fingerprint is the same, the peers need nothing.

- Every component renews its certificate when it **starts** if it ends within 30 days (or has ended; that is logged as a warning). A component that runs for a long time checks once a day (`CertificateWatcher`: daemon and its router, engine, management server) and renews the files. A running gRPC server keeps the certificate it was built with: **the new one is used from the next start** (`[Zu bestätigen]`: no hot reload, which the 30 days of notice allow for).
- By hand, on the machine of the component: `cringle [--home DIR] cert status` shows subject, key fingerprint, end date and the days left of the identities of the daemon, the router and the management server in that home; `cringle [--home DIR] cert renew [--component daemon|router|management] [--force]` renews the due ones (all with `--force`). Both read the files; they need no server.
- A new **key** (rotation) is not renewal: it needs a new trust exchange with every peer and is not done here.
- The repository has no process of its own (it gets its identity from whoever starts it) and the Gradle plugin identity is renewed when it is loaded.
