# Security

How Cringle decides who may talk to whom and who may do what, in one place, with the limits said plainly. The mechanisms are in [trust.md](trust.md), [users.md](users.md) and [webui.md](webui.md); this page is the overview an operator and a reviewer need.

## The model in five sentences

1. Every component (daemon, router, management server, repository, engine) has an **identity**: a key pair and a self-made certificate, created on first start and kept in its folder (owner-only).
2. Trust is given to the **key** (its SHA-256 fingerprint), never "on first use": a peer that is not in the trust store is refused at the TLS handshake, before any request is looked at.
3. **Every** link is mutual TLS 1.3 — component to component, engine to engine, tether to tether. There is no unencrypted mode and no switch for one.
4. A **person or program** is authenticated by a token (and, for components, by their key); what they may do is decided by **roles and scopes**.
5. Code of a plugin runs only if the plugin is marked **trusted** in the repository; otherwise the fabric is not started (fail closed).

## Trusting a component

You exchange fingerprints by another way (in person, a call, a QR code) and enter them: `cringle trust add-component <fingerprint>` for a component, `cringle trust add <router-address>` for another site's router; `cringle trust list` shows what is trusted and where the entry came from, `cringle trust revoke <fingerprint>` removes it (an open connection may live up to 60 s). The page *Connect* shows the address and fingerprint of every component of a machine. Programs of **one** installation trust each other by their key files (`--trust-local`, what the installers use).

The CLI pins the management server by its fingerprint in the profile: `cringle login` shows the fingerprint and stops unless `--yes` or `--fingerprint` is given. A different fingerprint later ends the command with exit code 2 and shows both. Do not use `--yes` for a server on another machine unless you compared the fingerprint.

## Tokens, users, roles

- Tokens are random, stored only as a hash, and shown **once**. Give each person and each program its own, with a label and (for programs) a lifetime: `cringle token create <user> --label ... --ttl-days n`, revoke with `token revoke`. A login link (`<web-url>/login#token=...`) or QR code puts a token on a phone; an **invite link** is one-time and lets a new user choose a name and sign in without ever seeing a token ([users.md](users.md)).
- Roles: `VIEWER`, `OPERATOR`, `ADMIN`, `END_USER` (for applications, not for management). A role is global, or **scoped** to `machine:<id>`, `project:<name>`, `fabric:<id>` or `function:<name>` (`trust`, `plugin-trust`, `users`, `config`). A list shows only what the caller may read. The table of the calls is in [users.md](users.md).
- **There is no user name and password** — tokens only (open item, [acceptance-1.0.md](acceptance-1.0.md)). The admin token of the first start is in `<home>/management/bootstrap-token` and the file is deleted at the first login; make personal users and keep the admin token for emergencies.
- **Other sites (federation):** trust the registry of another site by its key (`cringle registry trust`, compare the fingerprint); its users come as `name@site` with a short-lived signed token and the rights you gave that registry. `cringle registry untrust` ends all its tokens at once; there is no revocation of one token, so give short lifetimes ([trust.md](trust.md)).

## The web interface

HTTPS only with the identity of the management server (the browser shows a warning for a self-signed certificate: compare the fingerprint with the one on the page *Connect*, or put a reverse proxy with a real certificate in front and set `management.web.url`). It listens on `127.0.0.1` by default; `bind all` opens it to the network — do that only on a network you trust (a Tailscale network, a firewall). Sessions are in memory (a restart signs everybody out), every `POST` carries a CSRF header, the pages send a strict content security policy (it needs `'unsafe-eval'` because Alpine.js evaluates expressions; no inline scripts) and no page leaves the origin. Every page and action checks the same permissions as the gRPC call it makes.

## Code that runs

- A plugin is `trusted` or `untrusted` per name (`cringle repo trust <plugin> trusted`, needs `function:plugin-trust`); the default is untrusted. A fabric with a block of an untrusted plugin is **not started**, and the message says which. There is no sandbox for untrusted code yet (isolated blocks are deferred, [acceptance-1.0.md](acceptance-1.0.md)): mark a plugin `trusted` only when you trust its code as you would trust code you run on that machine.
- Packages are immutable per version and checked by hash at the repository, in the cache and before a start; a lock file records the hashes a deploy used, and one that differs fails the deploy.
- Blocks reach the outside only through **drivers** the engine gives them; each block has its own data folder and runtime paths. Class loaders separate plugins and fabrics but a trusted block runs **in the engine's JVM**: a block can crash an engine, and fabrics of one engine share its memory. Put fabrics that must not share fate on different engines.

## Certificates

Valid 10 years, renewed by the components before they end; the key stays, so no trust entry changes ([trust.md](trust.md)). A **compromised key** needs the trust entry removed everywhere (`cringle trust revoke`) and a new identity (delete the folder of the component, start, trust the new fingerprint); there is no automatic rotation.

## What to do on a new installation

1. Keep `bind` on loopback until you have decided how the site is reached; then use a private network, not the open internet.
2. Log in with the admin token, create your own user, move to personal tokens with a lifetime.
3. Trust other machines only after comparing fingerprints. Mark plugins `trusted` one by one.
4. Back up the identity and `management` folders ([operations.md](operations.md)) and keep the copy private.
5. Update regularly ([updating.md](updating.md)).

## Reporting a problem

A weakness in Cringle is reported privately to the owner of the repository (the e-mail of the maintainer in the GitHub profile) and not in a public issue.
