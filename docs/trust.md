# Trust foundation

Cringle has no central certificate authority. Trust comes from a manual act (Architecture 5.1). This page describes the building blocks in `kotlin/common` (#13): the identity of a component, the fingerprint trust is given to, the trust store and the TLS helper. They are not used by the servers and channels yet; see "What is missing".

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

## What is missing

- The servers and channels still use plaintext; they are switched to these contexts in #83 (router routes), #84 (the other components) and #85 (CLI and ManagementServer). `--insecure-dev-mode` is removed in #86.
- The trust API, enrollment and transitive trust through routers: #82.
- When a certificate is renewed and what happens at expiry (Architecture chapter 30).
- Closing connections of a peer whose trust was removed.
